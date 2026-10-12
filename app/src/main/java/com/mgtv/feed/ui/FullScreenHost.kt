package com.mgtv.feed.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar

/**
 * 全屏容器。运行时被 [FullScreenController] add 到 DecorView 上。
 *
 * 里面是**独立的一个 TextureView**，和小窗 item 里的 TextureView 不是同一个 View。
 * B 方案的本质：两个 TextureView 各自持有自己的 SurfaceTexture，
 * 解码内核在它们之间切 setSurface()，谁都不重建。
 *
 * 手势（抖音式）：
 * - 单击：显隐"操作组件层"（目前只有进度条），不改变播放状态；
 * - 双击：播放/暂停（全屏下不能是单击暂停）。
 * 另外本 View 是 clickable 的，会吃掉整屏触摸 —— 否则单击会穿透到底下的小窗 item，
 * 变成"单击暂停"。
 */
class FullScreenHost(context: Context) : FrameLayout(context) {

    // 注意：TextureView 不支持 background drawable，黑色背景由外层 FrameLayout 提供
    val textureView: TextureView = TextureView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    /** 操作组件层：目前只有一个进度条，之后的操作按钮都往这里加。 */
    private val overlay: FrameLayout = FrameLayout(context).apply {
        background = ColorDrawable(0x66000000.toInt())
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(16)
        }
        visibility = GONE
    }

    /** 进度条：全屏下同样可拖。 */
    val seekBar: SeekBar = SeekBar(context).apply {
        max = 1000
        progress = 0
        progressTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        progressBackgroundTintList = ColorStateList.valueOf(0x4DFFFFFF)
        thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL
        ).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
        }
    }

    private val backBtn: ImageView = ImageView(context).apply {
        setImageResource(android.R.drawable.ic_menu_revert)
        setPadding(24, 24, 24, 24)
        val size = dp(48)
        layoutParams = LayoutParams(size, size, Gravity.START or Gravity.TOP).apply {
            topMargin = dp(12)
            leftMargin = dp(12)
        }
    }

    /** 缓冲/卡住时的等待圈：帧没渲染上来之前给用户一个"在加载"的视觉，而不是纯黑。 */
    private val loadingView: ProgressBar = ProgressBar(context).apply {
        val size = dp(40)
        layoutParams = LayoutParams(size, size, Gravity.CENTER)
        visibility = GONE
    }

    /** 双击：播放/暂停切换。由 [FullScreenController] 接管。 */
    var onDoubleTapTogglePlay: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hideOverlay() }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            toggleOverlay()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTapTogglePlay?.invoke()
            return true
        }
    })

    init {
        setBackgroundColor(0xFF000000.toInt())
        // 必须 clickable：确保整屏触摸都被本层吃掉，不再穿透到下面的小窗 feed
        isClickable = true
        addView(textureView)
        addView(loadingView)
        overlay.addView(seekBar)
        addView(overlay)
        addView(backBtn)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (detector.onTouchEvent(event)) return true
        return super.onTouchEvent(event)
    }

    fun setLoading(visible: Boolean) {
        loadingView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setOnBackListener(l: (View) -> Unit) {
        backBtn.setOnClickListener(l)
    }

    // ---------- 操作组件层显隐 ----------

    /** 显示操作层，[autoHideMs] 毫秒后自动隐藏（给 0 表示不自动隐藏）。 */
    fun showOverlay(autoHideMs: Long = 3500L) {
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(autoHide)
        if (autoHideMs > 0) handler.postDelayed(autoHide, autoHideMs)
    }

    fun hideOverlay() {
        overlay.visibility = View.GONE
        handler.removeCallbacks(autoHide)
    }

    fun toggleOverlay() {
        if (overlay.visibility == View.VISIBLE) hideOverlay() else showOverlay()
    }

    /** 拖进度条期间取消自动隐藏，别拖到一半整层消失。 */
    fun cancelAutoHide() {
        handler.removeCallbacks(autoHide)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
