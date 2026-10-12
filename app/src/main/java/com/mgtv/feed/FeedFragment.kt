package com.mgtv.feed

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.mgtv.feed.databinding.FragmentFeedBinding
import com.mgtv.feed.player.PlayerPool
import com.mgtv.feed.player.TextureFit

/**
 * 单个频道的竖向 Feed 流。
 *
 * 播放权规则：**只有"当前频道"有资格播放**（[MainActivity.isCurrentChannel]）。
 * 之所以不能只靠 Fragment 生命周期：外层频道 pager 重排（旋转、尺寸变化）时，
 * 不止一个 fragment 会短暂 RESUMED，各自的"开闸"任务与内层 pager 的 onPageSelected
 * 都会去拉播放 —— 结果就是多个频道同时出声。所以每个能触发播放的入口都过一遍频道校验。
 *
 * 生命周期：
 * - 成为当前频道 → [activateIfCurrentChannel]：抢播放权 + 播当前页 + 起进度 ticker；
 * - 被滑走 → onPause：撤销待执行任务、暂停、停 ticker；onStop：释放解码器；
 * - 滑回来 → 重新激活（有预加载，秒开）。
 */
class FeedFragment : Fragment(R.layout.fragment_feed) {

    companion object {
        private const val ARG_CHANNEL = "channel"

        fun newInstance(channel: Int) = FeedFragment().apply {
            arguments = Bundle().apply { putInt(ARG_CHANNEL, channel) }
        }
    }

    private val channel: Int get() = requireArguments().getInt(ARG_CHANNEL)

    private var binding: FragmentFeedBinding? = null

    /** 本频道独立的播放池，onViewCreated 里按频道创建。 */
    var pool: PlayerPool? = null
        private set

    private val handler = Handler(Looper.getMainLooper())

    /** ticker 是否在跑（防止重复 post 出多条链）。 */
    private var tickerRunning = false

    private val progressTick = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            updateProgress()
            handler.postDelayed(this, 200)
        }
    }

    private val onPageChange = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            // 竖滑翻页。注意：本频道不是当前频道时（视图恢复会触发这个回调）不许播放
            if (!isPlayingChannel()) return
            pool?.onPageSelected(position)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentFeedBinding.bind(view)
        binding = b

        val p = PlayerPool(FeedData.itemsForChannel(channel))
        pool = p

        // 首帧渲染出来 → 揭封面
        p.onFirstFrame = { position ->
            view.post { findVH(position)?.cover?.visibility = View.GONE }
        }
        // 拿到视频原始宽高 → 校正画面（等比完整显示，留黑边）
        p.onVideoSize = { position ->
            view.post {
                val vh = findVH(position) ?: return@post
                val size = p.videoSizeOf(position) ?: return@post
                TextureFit.apply(vh.texture, size.first, size.second)
            }
        }
        p.onPlayError = { position ->
            view.post {
                context?.let { Toast.makeText(it, "第 ${position + 1} 条播放失败", Toast.LENGTH_SHORT).show() }
            }
        }

        b.feedPager.adapter = FeedAdapter(
            FeedData.itemsForChannel(channel),
            p,
            onFullscreen = { (activity as? MainActivity)?.fullScreenController?.enter() }
        )
        // 相邻页 View 保活，与播放池的 p-1/p/p+1 窗口对齐
        b.feedPager.offscreenPageLimit = 1
        b.feedPager.registerOnPageChangeCallback(onPageChange)
    }

    override fun onResume() {
        super.onResume()
        activateIfCurrentChannel()
    }

    override fun onPause() {
        super.onPause()
        // 撤销还没执行的"开闸"任务
        pendingActivate?.let { binding?.feedPager?.removeCallbacks(it) }
        pendingActivate = null
        stopTicker()
        pool?.pauseCurrent()
    }

    override fun onStop() {
        super.onStop()
        // 被滑走（或切后台）：释放本频道解码器，避免多频道累积超过硬件上限。
        // 例外：全屏中的频道不能放——全屏 TextureView 的 Surface 还挂在本池播放器上。
        if (pool?.isInFullscreen != true) pool?.releaseAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding?.feedPager?.unregisterOnPageChangeCallback(onPageChange)
        stopTicker()
        pool?.releaseAll()
        pool = null
        binding = null
    }

    /** onResume 里 post 出去的"开闸"任务，onPause 时必须撤销，否则会把已停频道又播起来。 */
    private var pendingActivate: Runnable? = null

    /**
     * 只有"当前频道"才允许播放。由 [onResume] 和 [MainActivity] 的频道切换回调调用。
     * 延迟到下一帧执行：这时外层 pager 的 currentItem 已经落定，能准确判断自己是不是当前频道。
     */
    fun activateIfCurrentChannel() {
        val p = pool ?: return
        val pager = binding?.feedPager ?: return
        val r = Runnable {
            if (!isPlayingChannel()) return@Runnable
            val act = activity as? MainActivity ?: return@Runnable
            act.activePool = p          // 抢全局播放权（会静音上一个频道）
            p.onPageSelected(pager.currentItem)
            startTicker()
        }
        pendingActivate = r
        pager.post(r)
    }

    /** 本频道是否既处于 RESUMED、又是外层 pager 的当前页。 */
    private fun isPlayingChannel(): Boolean {
        val act = activity as? MainActivity ?: return false
        return isResumed && act.isCurrentChannel(channel)
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        handler.post(progressTick)
    }

    private fun stopTicker() {
        tickerRunning = false
        handler.removeCallbacks(progressTick)
    }

    private fun updateProgress() {
        val p = pool ?: return
        val player = p.currentPlayer() ?: return
        val vh = findVH(p.currentPosition) ?: return
        // 手指正在拖动时不回写，免得和用户打架
        if (!p.isSeeking(p.currentPosition)) {
            val dur = player.duration()
            if (dur > 0) {
                vh.seekBar.max = dur
                vh.seekBar.progress = player.position()
            }
        }
        // 播放/暂停状态同步到小窗图标（含全屏里双击切换的状态，退全屏后要一致）
        vh.ivPause.visibility = if (player.wantsPlay()) View.GONE else View.VISIBLE
    }

    private fun findVH(position: Int): FeedAdapter.VH? {
        val pager = binding?.feedPager ?: return null
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        return rv.findViewHolderForAdapterPosition(position) as? FeedAdapter.VH
    }
}
