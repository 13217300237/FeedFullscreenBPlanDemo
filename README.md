# 短视频多频道 Feed 流 Demo

顶部多频道 Tab，每个频道一条竖向滑动的短视频流，滑到哪条播哪条。点按钮切横屏全屏，退出后进度接着走。

全屏部分是全篇重点。一句话结论：**全屏没有换播放器，只是换了画面往哪儿画。**

---

## 一、原理：一套解码器，两个 Surface

先记住一件事——**视频不是画在 View 上，是画在 View 背后那块 SurfaceTexture 上**。

所以"换屏"就是给播放器换一块 Surface，别的什么都不用动：

```
小窗 TextureView ──┐
                  ├── player.setSurfaceTexture(当前那块)
全屏 TextureView ──┘
```

解码器不重建、缓冲不清空、进度不归零。这就是无缝的全部秘密。

关键做法：全屏时，小窗那个 TextureView **留在原地不动**，只置 `INVISIBLE`。它没被 detach，SurfaceTexture 就不会销毁——这是退出全屏能秒回的原因。

反面例子：把 View 从 item 里 remove 出来挪到 DecorView 上，或者跳一个独立 Activity。Surface 一销毁就只能重新 prepare，黑屏必现。

---

## 二、进 / 退全屏的时序

进全屏（[FullScreenController.kt](app/src/main/java/com/mgtv/feed/ui/FullScreenController.kt)）：

1. 拿到当前页的播放器和它的 SurfaceTexture 凭证。
2. 小窗 TextureView 置 `INVISIBLE`——不是 GONE，更不是 remove。
3. 新建全屏容器 FullScreenHost，add 到 DecorView 上。它里面是**另一个独立**的 TextureView。
4. 等它 `onSurfaceTextureAvailable` → `player.setSurfaceTexture(全屏那块)` → 按视频尺寸校正画面 → 转横屏 + 进沉浸式。

退出就是反过来：先把 Surface 接回小窗，再 remove 全屏容器，最后转回竖屏。

三个必须注意的点：

- **先挂监听器，再 addView**。顺序反了会漏掉 `onSurfaceTextureAvailable`，那画面就再也接不上了。
- **横屏走 Activity 旋转**（`SENSOR_LANDSCAPE`，跟随重力翻转），manifest 里必须声明
  `android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"`。
  不声明的话，一旋转 Activity 就重建，整个 feed 和所有播放器全没了。
- **矩阵要重算**。画面矩阵是按 View 尺寸算的，旋转后尺寸一变旧矩阵就错位（表现是画面只占一角或歪到一边）。所以 `onSurfaceTextureSizeChanged` 和 `OnLayoutChange` 两个时机都要重算一次。

---

## 三、两个黑屏坑

**暂停时进全屏会黑。** MediaPlayer 暂停后不会主动把最后一帧重绘到新 Surface 上，新 Surface 就一直黑着，直到恢复播放推来新帧。解法：换完 Surface 后如果当前没在播，就 `seekTo(当前位置)` 强制渲染一次（`VideoPlayer.refreshFrame()`）。还没 prepare 完时没有帧可画，是 no-op。

**卡住 / 缓冲时进全屏，本来就黑。** 一帧都没解出来，新 Surface 自然什么都没有。这个没法用重绘解决，所以在全屏容器上加了个加载圈，有帧真正画上来（`onSurfaceTextureUpdated`）才撤掉。至少用户知道是在加载，而不是卡死了。

---

## 四、多频道：怎么保证同一时刻只播一路

App 里只有一个"播放权"：`MainActivity.activePool`。谁拿到谁出声，新频道拿权的瞬间旧频道被静音。

但**只靠 Fragment 生命周期是不够的**。外层频道容器重排时（旋转、尺寸变化），会有不止一个 fragment 短暂进入 RESUMED；而且每个 fragment 里的竖向 feed 在视图恢复时也会触发 `onPageSelected`。这些入口都能绕过播放权关口——结果就是多个频道同时出声。

所以每个能触发播放的入口都过一道校验 `isPlayingChannel()`：

> 自己是 RESUMED，**并且**自己是外层容器的当前页。

这个判断放在 post 到下一帧执行，那时 `currentItem` 已经落定，判断才准。

另一条容易漏的：**全屏期间，播放器池的 `onPageSelected` 只维护播放器窗口，不碰 Surface、不强拉播放**。否则暂停态一进全屏就被翻页事件播起来，而且会把 Surface 从小窗抢走，全屏画面直接断。

频道滑走时 onStop 释放解码器（`releaseAll()`），滑回来重建——不释放的话多频道累积会撞硬件解码器上限。唯一例外是全屏中的那个，它的 Surface 还挂在播放器上，不能放。

---

## 五、全屏交互

- **单击**：显隐操作组件层（overlay）。这层里现在是进度条 + 时间显示，以后加按钮就往里塞。
- **双击**：播放 / 暂停。
- **左右滑动画面**：调进度。
- 暂停时画面中间有播放图标，提示"点两下继续"。

**为什么单击不能是暂停**：全屏容器必须自己吃掉整屏触摸（`isClickable = true`）。它不吃的话，触摸会穿透到底下的小窗 item，而那个 item 的单击就是暂停——这就是"全屏下单击会暂停"的来源。

**横滑调进度**的几条规则：

- 横向位移超过 `touchSlop`，**并且**比纵向明显，才判定为调进度，免得和别的滑动打架。
- 拖动过程中**只动进度条，不 seek**；松手才 `seekTo()` 一次。每帧都 seek 会把解码器打崩。
- 拖动全程强制显示 overlay，不让它自动隐藏。时间显示同步跟着预览走，用户才知道松手会落在哪。
- 时长还没拿到（`max == 0`）时不响应——算不出目标位置。
- 触摸事件照旧喂给手势检测器，横滑会被判成非点击，**不会误触发单击显隐或双击暂停**。

时间格式：`00:12 / 01:30`，超过一小时才带小时位。

大小屏的进度条都能拖，逻辑一样：拖动期间进度不回写（`pool.setSeeking()`），松手才 seek。

---

## 六、另外两个必要细节

**画面不变形。** TextureView 默认把视频拉伸铺满，不管原始宽高比——这就是画面变形的根因。[TextureFit.kt](app/src/main/java/com/mgtv/feed/player/TextureFit.kt) 用 `setTransform()` 做等比缩放：按视频真实宽高算，哪个方向放不下就以哪个方向为基准收缩，另一个方向留黑边。视频完整、不变形，代价是黑边。

应用时机有三个，少一个就会看到某条视频是歪的：拿到视频尺寸时、TextureView 就绪时、View 被复用时（必须按当前视频尺寸重算，否则带着上一条的矩阵串台）。

**滑动为什么流畅。** [PlayerPool.kt](app/src/main/java/com/mgtv/feed/player/PlayerPool.kt) 只持有**当前页、上一页、下一页**三个播放器。相邻页提前 `prepareAsync()`——而且**没有 Surface 也能先 prepare**，解码器和缓冲先就绪，滑过去只剩 `setSurface + start`，基本零等待。滑出窗口的页 release，把解码器还给系统。封面一直盖到**首帧真正渲染出来**（`MEDIA_INFO_VIDEO_RENDERING_START`）才揭开，所以不会闪黑。

翻页事件和 TextureView 的就绪/销毁都汇总到 PlayerPool 这一个类里，时序问题收敛在一处。

---

## 七、目录结构

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

## 八、怎么跑

1. Android Studio 打开这个目录，等 Gradle sync 完。
2. **连真机**跑。模拟器硬解可能不稳，切全屏的表现不可信。
3. 操作：Tab 或横滑切频道，竖滑翻视频，右下角按钮进全屏。全屏里单击显隐进度条、双击暂停、左右滑动调进度；返回键退出全屏，进度接着走。

minSdk 24 / compileSdk 34，无第三方播放器依赖，只用系统 MediaPlayer。

---

## 九、注意事项

- 换视频源要挑**支持 HTTP Range** 的（否则拖不动进度），并且优先国内源——境外源慢到没法看，容易让人误判成"预加载没生效"。当前 8 条都是实测过的国内源。
- 横屏走 Activity 旋转，系统旋转动画期间会闪一下黑边。要彻底干净得让 TextureView 自己转，不走 Activity。
- 沉浸式用的是旧 API（`systemUiVisibility`），API 30+ 要换成 `WindowInsetsController`。
- 视频自带 rotation（竖拍源，旋转 90°/270°）没处理，要根据 metadata 调 TextureView 的 rotation，否则画面是躺着的。
- 横滑灵敏度是"整屏宽度 = 走完整片"，短视频够用，长视频要改成按秒算。
- 没做：音频焦点、熄屏续播、边下边缓存、预下载下一条、评论 / 弹幕这类互动层。
