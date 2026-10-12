package com.mgtv.feed.session

import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.mgtv.feed.kernel.MediaPlayerKernel
import com.mgtv.feed.kernel.PlayerKernel

/**
 * 全局播放会话单例。
 *
 * 设计要点（B 方案核心）：
 * 1. 整个进程只持有一个 [PlayerKernel] 实例，列表里同时只有一个 item 在播。
 * 2. 切全屏时**不调用 release / 不重新 setDataSource**，只把内核的渲染 Surface 从小窗 TextureView
 *    换到全屏 TextureView，所以解码器、缓冲、进度全部保留 —— 这就是无缝的根因。
 * 3. 离开 feed（Activity onStop）才真正 release。
 */
class FeedPlayerSession private constructor() {

    private var kernel: PlayerKernel? = null

    /** 当前绑定的小窗 TextureView（item 里那个）。 */
    var attachedSmallTexture: TextureView? = null
        private set

    /** 当前正在播放的 url。 */
    var attachedUrl: String? = null
        private set

    private var pendingSurface: Surface? = null

    companion object {
        private const val TAG = "FeedPlayerSession"

        @JvmStatic
        val instance = FeedPlayerSession()
    }

    private fun obtainKernel(): PlayerKernel {
        return kernel ?: MediaPlayerKernel().also { k ->
            k.setCallback(object : PlayerKernel.Callback {
                override fun onPrepared() {
                    Log.d(TAG, "onPrepared, surface=${pendingSurface}")
                    pendingSurface?.let { k.setSurface(it) }
                    k.start()
                }

                override fun onCompletion() {
                    Log.d(TAG, "onCompletion")
                }

                override fun onError(what: Int, extra: Int): Boolean {
                    Log.w(TAG, "onError what=$what extra=$extra")
                    return false
                }
            })
            kernel = k
        }
    }

    /**
     * 让 [smallTexture] 这个小窗 item 开始播放 [url]。
     * 如果之前在播别的 url，会先切数据源。
     *
     * 注意：[smallTexture] 的 SurfaceTexture 必须已经 available，否则先存起来等 onSurfaceTextureAvailable 再绑。
     */
    fun attach(url: String, smallTexture: TextureView, startPosition: Long = 0L) {
        val needReload = (attachedUrl != url)
        attachedUrl = url
        attachedSmallTexture = smallTexture

        val k = obtainKernel()
        val surface = if (smallTexture.isAvailable) Surface(smallTexture.surfaceTexture!!) else null

        if (needReload) {
            pendingSurface = surface
            k.setDataSource(url)
            k.prepareAsync()
            // onPrepared 回调里会 setSurface + start
        } else {
            // 同一个 url，只是换了个 item（比如 RecyclerView 复用）—— 直接挂 surface 继续播
            k.setSurface(surface)
            if (!k.isPlaying()) k.start()
        }
    }

    /** 小窗 SurfaceTexture 新就绪时调用（TextureView 刚 attach 到窗口）。 */
    fun bindSmallSurfaceIfNeeded(smallTexture: TextureView) {
        if (smallTexture !== attachedSmallTexture) return
        if (!smallTexture.isAvailable) return
        val k = kernel ?: return
        // 如果当前没有在全屏（也就是应该渲染在小窗），就把 surface 切回来
        if (!isInFullscreenRender) {
            k.setSurface(Surface(smallTexture.surfaceTexture!!))
            if (!k.isPlaying()) k.start()
        }
    }

    /**
     * B 方案核心：切换渲染目标。
     * 全屏 host 的 TextureView surface ready 时调用这里，解码器不重建。
     */
    fun switchSurface(surface: Surface?) {
        kernel?.setSurface(surface)
    }

    /** 用户滑走 / 切到别的 item：暂停但保留内核，不释放。 */
    fun pauseKeep() {
        kernel?.pause()
    }

    fun resume() {
        kernel?.start()
    }

    fun release() {
        kernel?.release()
        kernel = null
        attachedSmallTexture = null
        attachedUrl = null
    }

    // —— 由 FullScreenController 维护的标志 ——
    @Volatile
    var isInFullscreenRender: Boolean = false
}
