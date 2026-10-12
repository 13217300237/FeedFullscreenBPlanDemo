package com.mgtv.feed.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.mgtv.feed.player.PlayerPool
import com.mgtv.feed.player.TextureFit

/**
 * 全屏协调器：把当前页的渲染 Surface 从小窗 TextureView 换到全屏 TextureView。
 *
 * 无缝的根因：解码器不重建、不重新 prepare，只对当前 [com.mgtv.feed.player.VideoPlayer]
 * 调一次 setSurface。小窗 TextureView 全程只是 INVISIBLE（不 detach），
 * 它的 SurfaceTexture 不销毁，退全屏立刻接回。
 *
 * 横屏用 **Activity 旋转**（requestedOrientation = SENSOR_LANDSCAPE），跟随手机重力翻转。
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

    var isFullscreen: Boolean = false
        private set

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

        // 先挂监听器再 addView，顺序反了会漏 onSurfaceTextureAvailable
        h.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, hgt: Int) {
                player.setSurfaceTexture(st)
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

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
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
            h.textureView.post {
                TextureFit.apply(h.textureView, player.videoWidth, player.videoHeight)
            }
            applyLandscapeUi()
        }
    }

    fun exit() {
        if (!isFullscreen) return
        isFullscreen = false
        val p = pool
        pool = null
        p?.isInFullscreen = false

        // 渲染目标切回小窗（小窗 Surface 一直没销毁，直接接回）
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
