package com.mgtv.feed.player

import android.util.Log
import android.view.TextureView
import com.mgtv.feed.FeedItem

/**
 * 播放器池：短视频滑动的"窗口管理器"。
 *
 * 策略与抖音一致：
 * - 任何时候最多持有 [当前页-1, 当前页, 当前页+1] 三个播放器；
 * - 当前页：attach Surface + 播放；
 * - 相邻页：**提前 prepareAsync（无 Surface）**，滑过去时只剩 setSurface + start，基本零等待；
 * - 滑出窗口的页：release，释放解码器。
 *
 * TextureView 的就绪/销毁由 [com.mgtv.feed.FeedAdapter] 上报到这里统一处理，
 * 页面选中事件（ViewPager2 onPageSelected）也汇到这里 —— 所有时序问题收敛在这一个类里。
 */
class PlayerPool(private val items: List<FeedItem>) {

    companion object {
        private const val TAG = "PlayerPool"
    }

    private val players = HashMap<Int, VideoPlayer>()
    private val textures = HashMap<Int, TextureView>()

    /** 已经渲染出首帧的位置 —— Adapter 据此决定要不要盖封面。 */
    val renderedPositions = HashSet<Int>()

    var currentPosition = -1
        private set

    /** 全屏渲染期间为 true：此时翻页不允许把 surface 抢回小窗。 */
    var isInFullscreen = false

    /** 某页首帧渲染出来（揭封面）。 */
    var onFirstFrame: ((Int) -> Unit)? = null
    var onPlayError: ((Int) -> Unit)? = null
    /** 某页拿到视频原始宽高（上层拿去校正 TextureView 变换，防拉伸变形）。 */
    var onVideoSize: ((Int) -> Unit)? = null

    /** 某页的视频原始宽高；还没拿到时返回 null。 */
    fun videoSizeOf(position: Int): Pair<Int, Int>? {
        val p = players[position] ?: return null
        return if (p.videoWidth > 0 && p.videoHeight > 0) p.videoWidth to p.videoHeight else null
    }

    // ---------- 翻页 ----------

    fun onPageSelected(position: Int) {
        if (position !in items.indices) return
        Log.d(TAG, "onPageSelected $position")
        val prev = currentPosition
        currentPosition = position

        // 建窗口：当前页 + 前后各一页预加载
        for (i in position - 1..position + 1) {
            if (i in items.indices) obtainPlayer(i)
        }
        // 清窗口外
        val it = players.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key !in position - 1..position + 1) {
                Log.d(TAG, "release out-of-window player pos=${entry.key}")
                renderedPositions.remove(entry.key)
                entry.value.release()
                it.remove()
            }
        }
        // 上一页暂停（不 release，滑回来能瞬间续播）
        if (prev != position) players[prev]?.pause()

        attachAndPlay(position)
    }

    // ---------- TextureView 生命周期上报 ----------

    fun onTextureAvailable(position: Int, tv: TextureView) {
        Log.d(TAG, "texture available pos=$position")
        textures[position] = tv
        if (position == currentPosition && !isInFullscreen) {
            attachAndPlay(position)
        }
    }

    fun onTextureDestroyed(position: Int, tv: TextureView) {
        Log.d(TAG, "texture destroyed pos=$position")
        if (textures[position] === tv) textures.remove(position)
        // 渲染目标死了，先摘 surface；player 是否释放由窗口策略决定
        players[position]?.setSurfaceTexture(null)
    }

    // ---------- 当前页操作 ----------

    fun currentPlayer(): VideoPlayer? = players[currentPosition]
    fun currentTexture(): TextureView? = textures[currentPosition]

    /** 点按切换 播放/暂停。返回切换后是否在播。 */
    fun toggleCurrent(): Boolean {
        val p = currentPlayer() ?: return false
        return if (p.isPlaying()) {
            p.pause()
            false
        } else {
            p.start()
            true
        }
    }

    fun pauseCurrent() = players[currentPosition]?.pause()

    fun resumeCurrent() {
        if (!isInFullscreen) players[currentPosition]?.start()
    }

    /** 退出全屏后把小窗 surface 接回来。 */
    fun reattachCurrentSurface() {
        if (!isInFullscreen) attachAndPlay(currentPosition)
    }

    fun releaseAll() {
        players.values.forEach { it.release() }
        players.clear()
        textures.clear()
        renderedPositions.clear()
        currentPosition = -1
    }

    // ---------- 内部 ----------

    private fun obtainPlayer(position: Int): VideoPlayer {
        return players.getOrPut(position) {
            Log.d(TAG, "create player pos=$position")
            VideoPlayer("$TAG#$position").apply {
                onFirstFrame = {
                    renderedPositions.add(position)
                    this@PlayerPool.onFirstFrame?.invoke(position)
                }
                onError = { this@PlayerPool.onPlayError?.invoke(position) }
                onVideoSizeChanged = { this@PlayerPool.onVideoSize?.invoke(position) }
                prepare(items[position].url)
            }
        }
    }

    private fun attachAndPlay(position: Int) {
        val player = players[position] ?: return
        val tv = textures[position]
        if (tv != null && tv.isAvailable) {
            player.setSurfaceTexture(tv.surfaceTexture)
        }
        player.start()
    }
}
