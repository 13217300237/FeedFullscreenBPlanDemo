package com.mgtv.feed.player

import android.graphics.Matrix
import android.view.TextureView

/**
 * TextureView 画面校正。
 *
 * TextureView 默认把视频**拉伸铺满**自身，不管视频原始宽高比 —— 这就是画面变形的根因。
 * 这里用 setTransform 做 **等比缩放完整显示**（类似 ImageView.FIT_CENTER）：
 * 视频完整可见、不变形，放不下的方向留黑边（页面底色本来就是黑的）。
 */
object TextureFit {

    /**
     * 按视频原始宽高比校正 [textureView] 的渲染区域。
     * 视频尺寸未知（还没 onVideoSizeChanged）时传 0，会直接清掉旧变换。
     */
    fun apply(textureView: TextureView, videoW: Int, videoH: Int) {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (videoW <= 0 || videoH <= 0) {
            textureView.setTransform(null)
            return
        }
        if (viewW <= 0f || viewH <= 0f) return

        val videoAspect = videoW.toFloat() / videoH
        val viewAspect = viewW / viewH

        // fit：哪个方向放不下，就以哪个方向为基准收缩
        val contentW: Float
        val contentH: Float
        if (videoAspect > viewAspect) {
            // 视频比视图更宽 → 顶满宽度，上下留黑边
            contentW = viewW
            contentH = viewW / videoAspect
        } else {
            // 视频比视图更高 → 顶满高度，左右留黑边
            contentH = viewH
            contentW = viewH * videoAspect
        }

        // TextureView 的变换坐标系里，视频内容默认占满整个 view，
        // 先缩到 contentW×contentH，再平移居中
        val matrix = Matrix()
        matrix.setScale(contentW / viewW, contentH / viewH)
        matrix.postTranslate((viewW - contentW) / 2f, (viewH - contentH) / 2f)
        textureView.setTransform(matrix)
    }
}
