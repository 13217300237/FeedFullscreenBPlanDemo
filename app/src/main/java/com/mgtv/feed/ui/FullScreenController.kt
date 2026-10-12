package com.mgtv.feed.ui

import android.app.Activity
import android.app.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.TextureView
import android.view.Surface
import com.mgtv.feed.session.FeedPlayerSession

/**
 * B 方案协调器：管"小窗 TextureView ↔ 全屏 TextureView"之间的渲染目标切换。
 *
 * 关键不变量：
 * - 小窗 TextureView **不 detach、不 remove**，只是 visibility=INVISIBLE。
 *   这样它的 SurfaceTexture 不会销毁，切回来时可以立刻接上，不需要重新 prepare。
 * - 全屏 TextureView 是全新 View，它的 Surface available 是异步的，
 *   available 时再调 [FeedPlayerSession.switchSurface]。
 */
class FullScreenController(private val activity: Activity) {

    private val decor: ViewGroup = activity.window.decorView as ViewGroup
    private var host: FullScreenHost? = null

    /** 从哪个小窗 item 切出来的。 */
    private var anchorSmall: TextureView? = null

    var isFullscreen: Boolean = false
        private set

    /**
     * 进入全屏。
     * @param smallTexture 当前正在渲染的小窗 TextureView
     */
    fun enter(smallTexture: TextureView) {
        if (isFullscreen) return
        isFullscreen = true
        anchorSmall = smallTexture

        // 1) 小窗留原地，仅隐藏（Surface 不销毁，防止 RecyclerView 抖动）
        smallTexture.visibility = View.INVISIBLE

        // 2) 新建全屏 host 挂到 DecorView
        val h = FullScreenHost(activity)
        host = h
        h.setOnBackListener { exit() }
        decor.addView(h, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 3) 标记 session：现在渲染目标是全屏
        FeedPlayerSession.instance.isInFullscreenRender = true

        // 4) 监听全屏 TextureView 的 surface
        h.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, hgt: Int) {
                FeedPlayerSession.instance.switchSurface(Surface(st))
                applyLandscapeUi()
            }

            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, hgt: Int) {}

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                // 全屏 host 被 remove 时会走到这里。
                // 此时渲染目标已经切回小窗了，这里返回 true 允许 release surface。
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }

        // 已经 available 的话直接切
        if (h.textureView.isAvailable) {
            FeedPlayerSession.instance.switchSurface(Surface(h.textureView.surfaceTexture!!))
            applyLandscapeUi()
        }
    }

    fun exit() {
        if (!isFullscreen) return
        isFullscreen = false
        FeedPlayerSession.instance.isInFullscreenRender = false

        val small = anchorSmall
        val h = host

        // 1) 渲染目标切回小窗（小窗 Surface 一直没销毁，直接接回）
        if (small != null && small.isAvailable) {
            FeedPlayerSession.instance.switchSurface(Surface(small.surfaceTexture!!))
        } else {
            FeedPlayerSession.instance.switchSurface(null)
        }

        // 2) 移除全屏 host（会触发 onSurfaceTextureDestroyed）
        h?.let { decor.removeView(it) }
        host = null

        // 3) 小窗重新可见
        small?.visibility = View.VISIBLE

        // 4) 恢复竖屏 + 系统 UI
        applyPortraitUi()
        anchorSmall = null
    }

    /** 返回键拦截：全屏时先退出全屏而不是 finish Activity。 */
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
