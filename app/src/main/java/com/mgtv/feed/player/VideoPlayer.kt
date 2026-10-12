package com.mgtv.feed.player

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface

/**
 * 单个视频的播放器，包一层系统 [MediaPlayer]。
 *
 * 短视频滑动体验的关键都在这一层：
 * 1. **无 Surface 预加载**：prepareAsync 可以在没有渲染目标时先跑完（解码器、缓冲就绪），
 *    滑到这一页时只补一个 setSurface + start，接近秒开。
 * 2. setSurface 可随时换，解码器不重建 —— 翻页回来 / 切全屏都不用重新 prepare。
 * 3. 循环播放，符合短视频心智。
 */
class VideoPlayer(private val tag: String) {

    enum class State { IDLE, PREPARING, PREPARED }

    var state = State.IDLE
        private set

    /** 视频原始宽高（MediaPlayer onVideoSizeChanged 来的）。 */
    var videoWidth = 0
        private set
    var videoHeight = 0
        private set

    /** 首帧真正渲染出来的回调（MEDIA_INFO_VIDEO_RENDERING_START），用来揭封面。 */
    var onFirstFrame: (() -> Unit)? = null
    var onError: (() -> Unit)? = null
    /** 视频尺寸改变时通知上层调 setTextureTransform。 */
    var onVideoSizeChanged: (() -> Unit)? = null

    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var wantPlay = false
    private var firstFrameReported = false

    /** 只prepare 不播放。重复调用是 no-op。 */
    fun prepare(url: String) {
        if (state != State.IDLE) return
        state = State.PREPARING
        firstFrameReported = false
        videoWidth = 0
        videoHeight = 0
        Log.d(tag, "prepare $url")
        val player = MediaPlayer()
        mp = player
        player.setDataSource(url)
        player.isLooping = true
        player.setOnPreparedListener {
            Log.d(tag, "prepared, wantPlay=$wantPlay, hasSurface=${surface != null}")
            state = State.PREPARED
            it.setSurface(surface)
            if (wantPlay && surface != null) it.start()
        }
        player.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && !firstFrameReported) {
                firstFrameReported = true
                Log.d(tag, "first frame rendered")
                onFirstFrame?.invoke()
            }
            false
        }
        player.setOnVideoSizeChangedListener { _, w, h ->
            Log.d(tag, "video size changed w=$w h=$h")
            videoWidth = w
            videoHeight = h
            onVideoSizeChanged?.invoke()
        }
        player.setOnErrorListener { _, what, extra ->
            Log.w(tag, "onError what=$what extra=$extra")
            release()
            onError?.invoke()
            true
        }
        player.prepareAsync()
    }

    /**
     * 换渲染目标。传 null 表示暂时不渲染（SurfaceTexture 销毁时必须摘下来，
     * 否则往死 surface 上渲染会崩 native）。
     */
    fun setSurfaceTexture(st: SurfaceTexture?) {
        val old = surface
        surface = st?.let { Surface(it) }
        if (old !== surface) old?.release()
        mp?.setSurface(surface)
        // prepare 完成的瞬间可能因为没有 surface 压着没播，surface 到位补一发
        if (state == State.PREPARED && surface != null && wantPlay && mp?.isPlaying == false) {
            mp?.start()
        }
    }

    fun start() {
        wantPlay = true
        if (state == State.PREPARED && surface != null && mp?.isPlaying == false) {
            mp?.start()
        }
    }

    fun pause() {
        wantPlay = false
        if (mp?.isPlaying == true) mp?.pause()
    }

    fun isPlaying(): Boolean = mp?.isPlaying == true

    /**
     * "用户想要播放"的意图状态。
     * 区分"没在播因为用户暂停了"和"没在播因为还没缓冲好"——
     * 换 Surface（进/退全屏）时据此还原成暂停还是继续播。
     */
    fun wantsPlay(): Boolean = wantPlay

    /** 拖动进度条用。 */
    @Suppress("DEPRECATION")
    fun seekTo(ms: Int) {
        if (state == State.PREPARED) runCatching { mp?.seekTo(ms) }
    }

    /**
     * 暂停/缓冲时把当前帧强制渲染到新 Surface 上。
     * 场景：换新 Surface（如进全屏）时，暂停中的 MediaPlayer 不会自动把当前帧画过去，
     * 会一直黑屏到恢复播放。seekTo(当前位置) 会触发一次"定位+渲染"，当前帧立刻出现。
     * 还没 prepare 完（卡住/缓冲中）时没有帧可画，调用是 no-op。
     */
    @Suppress("DEPRECATION")
    fun refreshFrame() {
        if (state == State.PREPARED && mp?.isPlaying == false) {
            runCatching { mp?.seekTo(mp?.currentPosition ?: 0) }
        }
    }

    /** 播放进度查询（给进度条用），未就绪时返回 0。 */
    fun duration(): Int = if (state == State.PREPARED) mp?.duration ?: 0 else 0
    fun position(): Int = if (state == State.PREPARED) runCatching { mp?.currentPosition ?: 0 }.getOrDefault(0) else 0

    fun release() {
        wantPlay = false
        mp?.let { p ->
            p.setOnPreparedListener(null)
            p.setOnInfoListener(null)
            p.setOnVideoSizeChangedListener(null)
            p.setOnErrorListener(null)
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        mp = null
        surface?.release()
        surface = null
        state = State.IDLE
        videoWidth = 0
        videoHeight = 0
    }
}
