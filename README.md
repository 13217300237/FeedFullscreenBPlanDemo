# 短视频多频道 Feed 流 Demo

抖音式体验的最小完整实现：顶部多频道 Tab，每个频道一条竖向滑动的短视频流，滑到哪条播哪条，点右下角按钮切横屏全屏。

这个 README 的重点是**全屏怎么做的、为什么这么做**。

一句话结论：全屏没有换播放器，只是换了画面往哪儿画。

---

## 一、要解决的问题

小窗播放 → 点全屏 → 横屏播 → 退出 → 接着播。听起来简单，实际会撞上三件事：

1. 切全屏时怎么做到不黑屏、不重新缓冲、不丢进度。
2. 多频道场景下，怎么保证同一时刻只有一路在播。
3. 画面怎么才能不拉伸变形。

---

## 二、地基：一套解码器，两个 Surface

先把一个容易被忽略的事实说清楚——**视频不是"画在 View 上"的，是画在 View 背后那块 SurfaceTexture 上的**。

所以"换屏"这件事，本质上就是给播放器换一块 Surface：

```
小窗 TextureView ──┐
                  ├── player.setSurfaceTexture(当前那块)
全屏 TextureView ──┘
```

解码器、缓冲、播放进度**全都不动**。这就是无缝的全部秘密。

具体做法：
- 小窗一个 TextureView，全屏另一个 TextureView，各自持有自己的 SurfaceTexture。
- 全屏时，小窗那个 TextureView **留在原地不动**，只是 `INVISIBLE`。
- 它没被 detach，SurfaceTexture 就不会销毁——这是退出全屏能"秒回"的原因。

反面例子：把 View 从 item 里 remove 出来挪到 DecorView 上，或者跳一个独立的 Activity。Surface 一销毁，就只能重新 prepare，黑屏必现。

---

## 三、进 / 退全屏的时序

进全屏（[FullScreenController.kt](app/src/main/java/com/mgtv/feed/ui/FullScreenController.kt)）：

1. 拿到当前页的播放器和它的 SurfaceTexture 凭证。
2. 小窗 TextureView 置 `INVISIBLE`。注意是 INVISIBLE，不是 GONE、更不是 remove。
3. 新建全屏容器 FullScreenHost，add 到 DecorView 上。里面是独立的 TextureView。
4. 等它 `onSurfaceTextureAvailable` 回调 → `player.setSurfaceTexture(全屏那块)` → 按视频尺寸校正画面 → 转横屏 + 进沉浸式。

退全屏就是反过来：先把 Surface 接回小窗，再 remove 全屏容器，最后转回竖屏。

三个容易踩的点：

- **先挂监听器，再 addView**。顺序反了会漏掉 `onSurfaceTextureAvailable`，画面再也接不上。
- **横屏用 Activity 旋转**（`requestedOrientation = SENSOR_LANDSCAPE`，跟随重力翻转）。但 manifest 里必须声明
  `android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"`。
  不声明的话，一旋转 Activity 就重建——整个 feed、所有播放器全没了。
- **矩阵要重算**。画面矩阵是按 View 尺寸算出来的，旋转后尺寸变了，旧矩阵就错位（表现是画面只占一部分，或者歪到一边）。所以 `onSurfaceTextureSizeChanged` 和 `OnLayoutChange` 两个时机都要调一次 `TextureFit.apply()`。

---

## 四、两个必须知道的坑

**坑一：暂停时进全屏会黑屏。**

MediaPlayer 暂停之后，**不会**主动把最后一帧重绘到新 Surface 上。新 Surface 就一直是黑的，直到恢复播放推来新帧。

解法：换完 Surface 后，如果当前没在播，就 `seekTo(当前位置)` 强制触发一次"定位 + 渲染"（`VideoPlayer.refreshFrame()`），当前帧立刻出现。还没 prepare 完的情况没有帧可画，调用是 no-op。

**坑二：卡住 / 缓冲时进全屏，本来就黑。**

一帧都没解出来，新 Surface 自然什么都没有。这个没法用重绘解决，所以全屏容器上加了个加载圈——有帧真正画上来（`onSurfaceTextureUpdated`）才撤掉。至少用户知道是在加载，而不是卡死了。

---

## 五、播放状态怎么在大小屏之间共享

**因为大小屏用的是同一个播放器实例，状态天然就是共享的**，不需要来回同步。

真正需要区分的是"没在播"的两种含义：一种是用户按了暂停，另一种是还没缓冲好。所以播放器里存了一个意图位 `wantPlay`，对外用 `wantsPlay()` 暴露。

切 Surface 分两条路走：

- **翻页翻到新的一页** → `attachAndPlay()`，强制播。
- **换 Surface、退全屏** → `attachRespectingIntent()`，按意图还原：想播的继续播，暂停的只重绘一帧。

这样退全屏不会无条件续播——暂停着进去，出来还是暂停，小窗那个播放/暂停图标也对得上。

---

## 六、多频道：怎么保证同一时刻只有一路在播

App 里只有一个"播放权"：`MainActivity.activePool`。谁拿到谁出声，新频道拿权的瞬间旧频道被静音。

但**只靠 Fragment 生命周期是不够的**。外层频道容器重排时（旋转、尺寸变化），会有不止一个 fragment 短暂进入 RESUMED；而且每个 fragment 里的竖向 feed 在视图恢复时也会触发 `onPageSelected`。这些入口都能绕过播放权关口——结果就是多个频道同时出声。

所以每个能触发播放的入口都过一道校验 `isPlayingChannel()`：

> 自己是 RESUMED，**并且**自己是外层容器的当前页。

这个判断放在 post 到下一帧执行，那时 `currentItem` 已经落定，判断才准。

还有一条：**全屏期间，播放器池的 `onPageSelected` 只维护播放器窗口，不碰 Surface、不强拉播放**。否则暂停态一进全屏就被翻页事件播起来，而且会把 Surface 从小窗抢走，全屏画面直接断。

频道释放的时机：滑走的频道 onStop 释放解码器（`releaseAll()`），滑回来重建。不释放的话多频道累积会撞硬件解码器上限。唯一例外是全屏中的那个——它的 Surface 还挂在播放器上，不能放。

---

## 七、全屏里的手势（抖音那一套）

- **单击**：显隐操作组件层。这层里现在有进度条和时间显示（当前 / 总时长），之后要加按钮就往 overlay 里塞。
- **双击**：播放 / 暂停。全屏下**不能**是单击暂停。
- **左右滑动画面**：调进度。

**为什么单击不能是暂停**：全屏容器必须自己吃掉整屏触摸（设 `isClickable = true`）。它不吃的话，触摸会穿透到底下的小窗 item，而那个 item 的单击就是暂停——这就是"全屏下单击会暂停"的来源。

**横滑调进度**的做法：
- 横向位移超过 `touchSlop`，**并且**比纵向位移明显，才判定为调进度。避免和别的滑动打架。
- 拖动过程中**只动进度条，不 seek**；松手才 `seekTo()` 一次。每帧都 seek 会把解码器打崩。
- 拖动全程强制显示操作组件层，不然滑到一半进度条自己藏了，看不见调了多少。时间显示同步跟着预览走，用户才知道松手会落在哪。
- 时长还没拿到（`max == 0`）时不响应——算不出目标位置。
- 触摸事件照旧喂给手势检测器，横滑会被判成非点击，**不会误触发单击显隐或双击暂停**。

大小屏的进度条都能拖，逻辑一样：拖动期间进度不回写（`pool.setSeeking()`），松手才 seek。

---

## 八、画面不变形

TextureView 默认把视频**拉伸铺满**自身，不管视频原始宽高比——这就是画面全都变形的根因。

[TextureFit.kt](app/src/main/java/com/mgtv/feed/player/TextureFit.kt) 用 `setTransform()` 做等比缩放：按视频真实宽高算，哪个方向放不下就以哪个方向为基准收缩，另一个方向留黑边。视频完整、不变形，代价是黑边。

要复现"变形"很容易——把 `TextureFit.apply()` 的调用去掉就行。

应用时机有三个，缺一个就会看到某条视频是歪的：
1. 拿到视频原始尺寸时（`onVideoSizeChanged`）。
2. TextureView 就绪时。
3. View 被复用时——必须按当前视频尺寸重算，否则会带着上一条视频的矩阵，串台。

---

## 九、滑动的流畅度从哪来

[PlayerPool.kt](app/src/main/java/com/mgtv/feed/player/PlayerPool.kt) 是播放器窗口管理器，只持有**当前页、上一页、下一页**三个播放器。

- 相邻页提前 `prepareAsync()`——而且**没有 Surface 也能先 prepare**，解码器和缓冲先就绪。滑过去只剩 `setSurface + start`，基本零等待。
- 滑出窗口的页 `release()`，把解码器还给系统。
- 封面一直盖到**首帧真正渲染出来**（`MEDIA_INFO_VIDEO_RENDERING_START`）才揭开，所以不会闪黑。

翻页事件、TextureView 的就绪/销毁都汇总到这一个类里——时序问题收敛在一处，是最省心的做法。

---

## 十、目录结构

```
app/src/main/java/com/mgtv/feed/
├── MainActivity.kt               # 外层横向频道容器 + Tab + 全局播放权
├── FeedFragment.kt               # 单个频道：竖向 feed + 独立播放池
├── FeedAdapter.kt                # item 绑定：封面、标题、操作列、进度条
├── FeedData.kt                   # 频道列表 + 测试视频源
├── player/
│   ├── VideoPlayer.kt            # 单个视频，包一层 MediaPlayer
│   ├── PlayerPool.kt             # 播放器窗口管理（p-1 / p / p+1）
│   └── TextureFit.kt             # 画面等比校正，防变形
└── ui/
    ├── FullScreenHost.kt         # 全屏容器：独立 TextureView + 操作层 + 手势
    └── FullScreenController.kt   # 两个 Surface 之间的切换调度
```

---

## 十一、怎么跑

1. Android Studio 打开这个目录，等 Gradle sync 完。
2. **连真机**跑。模拟器硬解可能有问题，切全屏的表现不可信。
3. 操作：
   - 顶部 Tab 切频道，横向滑动也能切
   - 竖向滑动翻视频
   - 点右下角按钮进全屏；全屏里单击显隐进度条、双击暂停、左右滑动调进度
   - 左上角返回键或系统返回键退出全屏，进度接着走

minSdk 24 / compileSdk 34，无第三方播放器依赖，只用系统 MediaPlayer。

---

## 十二、测试视频源

[FeedData.kt](app/src/main/java/com/mgtv/feed/FeedData.kt) 里 8 条源都是实测过的：全部国内 CDN、首 MB 速度 0.75MB/s ~ 2.2MB/s、支持 HTTP 206 分段（能边下边播）、内容互不重复。

换源的时候注意这两条，否则会以为是代码问题：
- 必须支持 Range 请求，否则 MediaPlayer 没法拖进度。
- 优先挑国内源。境外源在这个场景下慢到没法看，会让人误判成"预加载没生效"。

---

## 十三、这个 Demo 故意简化、真实工程要补的点

- **横屏用 Activity 旋转**，系统旋转动画期间会闪一下黑边。要彻底干净，得让 TextureView 自己转（或用 windowManager 旋转），不走 Activity。
- **沉浸式用的是旧 API**（`systemUiVisibility`）。API 30+ 官方换成了 `WindowInsetsController`，上线要做版本分支。
- **全屏期间没锁底层 feed 的滑动**。现在靠全屏容器吃掉触摸，够用；更稳的做法是直接禁掉。
- **视频自带 rotation（竖拍源，旋转 90°/270°）没处理**。要根据 metadata 调 TextureView 的 rotation，否则画面是躺着的。
- **横滑灵敏度是"整屏宽度 = 走完全片"**。短视频够用，长视频要改成按秒算。
- **没做**：音频焦点、熄屏续播、边下边缓存、预下载下一条、评论 / 弹幕之类的互动层。
