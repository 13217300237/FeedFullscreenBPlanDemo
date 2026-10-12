package com.mgtv.feed.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import com.mgtv.feed.player.PlayerPool
import com.mgtv.feed.player.TextureFit

/**
 * 全屏协调器：把当前页的渲染 Surface 从小窗 TextureView 换到全屏 TextureView。
 *
 * 无缝的根因：解码器不重建、不重新 prepare，只对当前 [com.mgtv.feed.player.VideoPlayer]
 * 调一次 setSurface。小窗 TextureView 全程只是 INVISIBLE（不 detach），
 * 它的 SurfaceTexture 不销毁，退全屏立刻接回。
 *
 * 状态共享：全屏和小窗用的是**同一个 VideoPlayer 实例**，所以播放/暂停状态天然一致。
 * 进/退全屏只做两件与状态有关的事：
 * - 换完 Surface 后按"用户意图"决定是继续播还是只重绘当前帧（[PlayerPool.reattachCurrentSurface]）；
 * - 暂停态换 Surface 不会自动重绘，靠 refreshFrame() 补一帧，否则全屏是黑的。
 *
 * 横屏用 **Activity 旋转**（requestedOrientation = SENSOR_LANDSCAPE），跟随重力翻转。
 * 注意这会触发系统旋转动画：旋转瞬间画面矩阵是按旧尺寸算的，
 * 所以 onSurfaceTextureSizeChanged / OnLayoutChange 里都要按新尺寸重算矩阵。
 */
class FullScreenController(
    private val activity: Activity,
    private val poolProvider: () -> PlayerPool?
) {

    private val decor: ViewGroup = activity.window.decorView as ViewGroup
    private var host: FullScreenHost? = null
    private var pool: PlayerPool? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 用户正在拖全屏里的进度条：这期间进度不回写。 */
    private var seeking = false

    var isFullscreen: Boolean = false
        private set

    /** 全屏进度条刷新。 */
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 200)
        }
    }

    /** 进入全屏（只切当前播放页）。 */
    fun enter() {
        if (isFullscreen) return
        val p = poolProvider() ?: return
        val player = p.currentPlayer() ?: return
        val anchor = p.currentTexture() ?: return
        isFullscreen = true
        pool = p
        p.isInFullscreen = true

        // 小窗留原地仅隐藏，Surface 不销毁
        anchor.visibility = View.INVISIBLE

        val h = FullScreenHost(activity)
        host = h
        h.setOnBackListener { exit() }
        // 双击 = 播放/暂停（全屏下不能是单击暂停）。图标立刻跟着切，别等 ticker
        h.onDoubleTapTogglePlay = { h.setPaused(!p.toggleCurrent()) }
        // 进全屏时同步当前状态：暂停着进来，图标就要在
        h.setPaused(!player.wantsPlay())
        h.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(sb: SeekBar) {
                seeking = true
                h.cancelAutoHide()
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                p.seekTo(p.currentPosition, sb.progress)
                seeking = false
                h.showOverlay()
            }
        })
        // 在画面上左右滑动调进度：滑动中只预览（seeking 挡住 ticker 回写），松手才真正 seek
        h.onSeekDragStart = { seeking = true }
        h.onSeekDragEnd = { targetMs ->
            p.seekTo(p.currentPosition, targetMs)
            seeking = false
            h.showOverlay()
        }
        // 先亮等待圈：新 Surface 还没渲染出任何帧之前，不让人对着纯黑干看
        h.setLoading(true)
        // 操作组件层先露一下，3.5s 后自动隐藏；之后单击可随时唤出
        h.showOverlay()

        // 先挂监听器再 addView，顺序反了会漏 onSurfaceTextureAvailable
        h.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, hgt: Int) {
                player.setSurfaceTexture(st)
                // 暂停/卡住时新 Surface 拿不到帧 → 强制重绘当前帧；没就绪则等 onSurfaceTextureUpdated
                player.refreshFrame()
                // 全屏 TextureView 是新 View，变换矩阵要重新给
                TextureFit.apply(h.textureView, player.videoWidth, player.videoHeight)
                applyLandscapeUi()
            }

            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, hgt: Int) {
                // 横屏旋转后尺寸变了：矩阵是按旧尺寸算的，必须按新尺寸重算
                h.textureView.post {
                    TextureFit.apply(h.textureView, player.videoWidth, player.videoHeight)
                }
            }

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                // 只要有一帧真正画上来（播放中持续触发；暂停时 refreshFrame 的 seek 渲染也会触发一次），就撤掉等待圈
                h.setLoading(false)
            }
        }

        // 旋转导致 view 尺寸变化时，按最新尺寸重算矩阵（双保险，和 onSurfaceTextureSizeChanged 互补）
        h.textureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            TextureFit.apply(h.textureView, player.videoWidth, player.videoHeight)
        }

        decor.addView(h, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // addView 后 surface 可能已就绪（没走回调），兜底
        if (h.textureView.isAvailable) {
            player.setSurfaceTexture(h.textureView.surfaceTexture)
            player.refreshFrame()
            h.textureView.post {
                TextureFit.apply(h.textureView, player.videoWidth, player.videoHeight)
            }
            applyLandscapeUi()
        }

        handler.post(progressTick)
    }

    fun exit() {
        if (!isFullscreen) return
        isFullscreen = false
        handler.removeCallbacks(progressTick)
        seeking = false

        val p = pool
        pool = null
        // 先解除全屏态，再让小窗 surface 接回去（接回时按用户意图还原播放/暂停）
        p?.isInFullscreen = false
        p?.reattachCurrentSurface()

        host?.let { decor.removeView(it) }
        host = null

        p?.currentTexture()?.visibility = View.VISIBLE
        applyPortraitUi()
    }

    /** 返回键拦截：全屏时先退全屏而不是 finish Activity。 */
    fun onBackPressed(): Boolean {
        if (isFullscreen) {
            exit()
            return true
        }
        return false
    }

    private fun updateProgress() {
        val h = host ?: return
        val p = pool ?: return
        val player = p.currentPlayer() ?: return
        // 暂停状态同步（含小窗里暂停着进全屏、全屏里双击切换）；拖进度条期间也要跟着
        h.setPaused(!player.wantsPlay())
        if (seeking) return
        val dur = player.duration()
        if (dur > 0) {
            h.seekBar.max = dur
            h.seekBar.progress = player.position()
        }
    }

    private fun applyLandscapeUi() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyPortraitUi() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }
}
