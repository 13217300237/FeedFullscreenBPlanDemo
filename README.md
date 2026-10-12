# Feed 流视频横屏全屏 Demo（B 方案：双 Surface 无缝切换）

对应需求：Feed 流里每个 item 是一个小窗视频，点全屏按钮后**不黑屏、不重新缓冲、不重置进度**地切到横屏全屏，返回后接着播。

## 为什么是 B 方案

| | A 方案（View 迁移） | **B 方案（双 Surface）✅** | C 方案（独立 Activity） |
|---|---|---|---|
| 做法 | 把 PlayerView 整颗子树从 item remove，add 到 DecorView | 小窗和全屏各一个 TextureView，同一个解码内核 `setSurface()` 切换 | 全屏跳新 Activity，共享内核 |
| 解码/缓冲 | TextureView detach → Surface 销毁 → 黑屏重连 | **Surface 不销毁，解码器不重建** | 跨 Activity 共享 Surface 较绕 |
| 体验 | 切瞬间黑一下 | 真正无缝 | 转场难做 |

本 demo 完整实现 B 方案。

## 目录结构

```
app/src/main/java/com/mgtv/feed/
├── MainActivity.kt                 # 入口：RecyclerView + 滚动停稳自动播中央 item
├── FeedData.kt                     # 测试视频地址（Google Cast 公开 mp4）
├── FeedAdapter.kt                  # item 绑定 + 全屏按钮
├── kernel/
│   ├── PlayerKernel.kt              # ★ 抽象内核接口（对应你们已有核心层）
│   └── MediaPlayerKernel.kt        # 系统 MediaPlayer 示例实现（替换成你们自己的）
├── session/
│   └── FeedPlayerSession.kt        # ★ 全局单例：整个 App 只持有一个内核实例
└── ui/
    ├── FullScreenHost.kt            # 全屏容器（独立 TextureView + 返回键）
    └── FullScreenController.kt      # ★ B 方案核心：管两个 Surface 之间切换
```

## B 方案三个关键不变量（面试/评审讲清楚这三点就算过）

1. **解码内核全局唯一**：`FeedPlayerSession` 持有一个 `PlayerKernel`，切全屏时**不 release、不重新 setDataSource、不 prepare**。
2. **两个 TextureView，不是同一个 View**：小窗 TextureView 留在 item 里 `visibility=INVISIBLE`（Surface 不销毁）；全屏 TextureView 是新加到 DecorView 的独立 View。
3. **切换动作 = `kernel.setSurface(newSurface)`**：
   - 进入全屏：等全屏 TextureView `onSurfaceTextureAvailable` → `session.switchSurface(fullSurface)` → 切横屏 + 沉浸状态栏
   - 退出全屏：`session.switchSurface(smallSurface)`（小窗 Surface 一直在，直接接回）→ remove 全屏 host → 切竖屏

## 怎么跑

1. Android Studio（Giraffe 以上）→ **Open** → 选这个 `FeedFullscreenBPlanDemo` 目录。
2. 等 Gradle sync 完（首次会自动下载 wrapper）。
3. 连一台真机（模拟器硬解可能有问题），点 Run。
4. 操作：
   - 上下滑动 → 停稳后自动播屏幕正中间的视频
   - 点视频右下角的旋转按钮 → 无缝切横屏全屏
   - 全屏里点左上角返回 / 系统返回键 → 回到小窗，进度续上

## 替换成你们自己的播放器

你们已经有自己的 PlayerView 核心层，只需要：

1. 把 `kernel/MediaPlayerKernel.kt` 整个删掉，换成实现 `PlayerKernel` 接口的适配类：
   ```kotlin
   class YourKernelAdapter : PlayerKernel {
       private val yourPlayer = YourPlayerCore()

       override fun setSurface(s: Surface?) { yourPlayer.setRenderSurface(s) }
       override fun setDataSource(url: String) { yourPlayer.setUrl(url) }
       override fun start() = yourPlayer.play()
       override fun pause() = yourPlayer.pause()
       // ... 其余方法转发
   }
   ```
2. `FeedPlayerSession.obtainKernel()` 里把 `MediaPlayerKernel()` 换成 `YourKernelAdapter()`。
3. 其余 UI / 状态机 / Surface 切换逻辑**一行不用改**。

## 这个 Demo 故意简化、真实工程要补的点

- **全屏期间锁 RecyclerView**：当前 demo 全屏时如果用户在小窗位置滑动，item 可能被回收，小窗 Surface 会销毁。真实工程全屏期间要 `recyclerView.isEnabled = false` 或用 FLAG_KEEP_SCREEN_ON + 拦截触摸。
- **重力感应**：`FullScreenController` 只做了按钮触发。要加传感器横屏，参考 GSYVideoPlayer 的 `OrientationUtils`。
- **视频自身 rotation（90°/270° 的源）**：demo 没处理，真实工程要根据 metadata 调整 TextureView 的 rotation / scale。
- **预加载 / 边播边缓存**：`FeedPlayerSession` 是单内核，真实 feed 会预加载下一个 item 的视频，要扩展成「播放内核 + 预加载内核池」。
- **音频焦点 / 熄屏续播**：demo 没做。
- **沉浸状态栏版本兼容**：`systemUiVisibility` 在 API 30+ 被 WindowInsetsController 取代，demo 为了 minSdk 24 用了旧 API，上线要做版本分支。
