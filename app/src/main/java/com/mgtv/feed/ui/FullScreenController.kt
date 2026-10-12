package com.mgtv.feed.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.mgtv.feed.player.PlayerPool

/**
 * 全屏协调器：把当前页的渲染 Surface 从小窗 TextureView 换到全屏 TextureView。
 *
 * 无缝的根因：解码器不重建、不重新 prepare，只对当前 [com.mgtv.feed.player.VideoPlayer]
 * 调一次 setSurface。小窗 TextureView 全程只是 INVISIBLE（不 detach），
 * 它的 SurfaceTexture 不销毁，退全屏立刻接回。
 */
class FullScreenController(
    private val activity: Activity,
    private val pool: PlayerPool
) {

    private val decor: ViewGroup = activity.window.decorView as ViewGroup
    private var host: FullScreenHost? = null

    var isFullscreen: Boolean = false
        private set

    /** 进入全屏（只切当前播放页）。 */
    fun enter() {
        if (isFullscreen) return
        val player = pool.currentPlayer() ?: return
        val anchor = pool.currentTexture() ?: return
        isFullscreen = true
        pool.isInFullscreen = true

        // 小窗留原地仅隐藏，Surface 不销毁
        anchor.visibility = View.INVISIBLE

        val h = FullScreenHost(activity)
        host = h
        h.setOnBackListener { exit() }

        // 先挂监听器再 addView，顺序反了会漏 onSurfaceTextureAvailable
        h.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, hgt: Int) {
                player.setSurfaceTexture(st)
                applyLandscapeUi()
            }

            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, hgt: Int) {}

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }

        decor.addView(h, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // addView 后 surface 可能已就绪（没走回调），兜底
        if (h.textureView.isAvailable) {
            player.setSurfaceTexture(h.textureView.surfaceTexture)
            applyLandscapeUi()
        }
    }

    fun exit() {
        if (!isFullscreen) return
        isFullscreen = false
        pool.isInFullscreen = false

        // 渲染目标切回小窗（小窗 Surface 一直没销毁，直接接回）
        pool.reattachCurrentSurface()

        host?.let { decor.removeView(it) }
        host = null

        pool.currentTexture()?.visibility = View.VISIBLE
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
