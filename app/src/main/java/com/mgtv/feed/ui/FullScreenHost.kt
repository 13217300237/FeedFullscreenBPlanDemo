package com.mgtv.feed.ui

import android.content.Context
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 全屏容器。运行时被 [FullScreenController] add 到 DecorView 上。
 *
 * 里面是**独立的一个 TextureView**，和小窗 item 里的 TextureView 不是同一个 View。
 * B 方案的本质：两个 TextureView 各自持有自己的 SurfaceTexture，
 * 解码内核在它们之间切 setSurface()，谁都不重建。
 */
class FullScreenHost(context: Context) : FrameLayout(context) {

    // 注意：TextureView 不支持 background drawable，黑色背景由外层 FrameLayout 提供
    val textureView: TextureView = TextureView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
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

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(textureView)
        addView(backBtn)
    }

    fun setOnBackListener(l: (View) -> Unit) {
        backBtn.setOnClickListener(l)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
