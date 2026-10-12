package com.mgtv.feed.kernel

import android.media.MediaPlayer
import android.view.Surface

/**
 * 用系统 [MediaPlayer] 作为示例内核，仅用于演示 B 方案的 Surface 切换机制。
 * 真实工程里把这个类替换成你们自己的内核实现即可，上层不用改。
 */
class MediaPlayerKernel : PlayerKernel {

    private var mp: MediaPlayer? = null
    private var callback: PlayerKernel.Callback? = null
    private var pendingUrl: String? = null
    private var prepared = false

    override fun setDataSource(url: String) {
        releaseInternal()
        pendingUrl = url
        prepared = false
        mp = MediaPlayer().apply {
            setDataSource(url)
            setOnPreparedListener {
                prepared = true
                callback?.onPrepared()
            }
            setOnCompletionListener { callback?.onCompletion() }
            setOnErrorListener { _, what, extra -> callback?.onError(what, extra) ?: false }
        }
    }

    override fun prepareAsync() {
        mp?.prepareAsync()
    }

    override fun setSurface(surface: Surface?) {
        // MediaPlayer 允许在播放中随时换 Surface，解码器不重建 —— 这就是 B 方案无缝的根因。
        mp?.setSurface(surface)
    }

    override fun start() {
        mp?.start()
    }

    override fun pause() {
        mp?.pause()
    }

    override fun seekTo(msec: Long) {
        mp?.seekTo(msec)
    }

    override fun getCurrentPosition(): Long = mp?.currentPosition?.toLong() ?: 0L

    override fun isPlaying(): Boolean = mp?.isPlaying == true

    override fun release() {
        releaseInternal()
    }

    override fun setCallback(cb: PlayerKernel.Callback?) {
        callback = cb
    }

    private fun releaseInternal() {
        mp?.setOnPreparedListener(null)
        mp?.setOnCompletionListener(null)
        mp?.setOnErrorListener(null)
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        mp = null
        prepared = false
    }
}
