package com.mgtv.feed.kernel

import android.view.Surface

/**
 * 播放器核心层抽象。
 *
 * 对应你们公司已有的自定义播放器核心层：对外只暴露一个能渲染到 [Surface] 的内核，
 * 不关心 Surface 来自小窗 TextureView 还是全屏 TextureView。
 *
 * B 方案的关键就在这里：同一个内核实例在整个 App 生命周期内只创建一次，
 * 切全屏时**不 release、不重新 prepare、不重建解码器**，只是把渲染目标 Surface 换掉。
 */
interface PlayerKernel {

    fun setDataSource(url: String)

    fun prepareAsync()

    /**
     * 切换渲染目标。传 null 表示暂时不渲染（黑屏但不暂停解码）。
     * 这是 B 方案无缝切换的核心 API：MediaPlayer / ExoPlayer / 自研内核都支持。
     */
    fun setSurface(surface: Surface?)

    fun start()

    fun pause()

    fun seekTo(msec: Long)

    fun getCurrentPosition(): Long

    fun isPlaying(): Boolean

    fun release()

    fun setCallback(cb: Callback?)

    interface Callback {
        fun onPrepared()
        fun onCompletion()
        fun onError(what: Int, extra: Int): Boolean
    }
}
