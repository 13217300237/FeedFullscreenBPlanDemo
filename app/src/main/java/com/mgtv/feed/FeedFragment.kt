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
 * 生命周期与 FragmentStateAdapter 的 setMaxLifecycle 对齐：
 * - 成为当前频道 → onResume：注册为活动播放池，开始播放当前页，启动进度 ticker；
 * - 被滑走 → onPause：暂停；onStop：释放本频道的播放器（防止多频道累积解码器）；
 * - 滑回来 → onResume：重建并直接续播当前页（有预加载，秒开）。
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
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 200)
        }
    }

    private val onPageChange = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
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
        // 成为当前频道：注册到 Activity 供全屏用，播放当前页，启动进度条 ticker
        val p = pool ?: return
        (activity as? MainActivity)?.activePool = p
        val pager = binding?.feedPager ?: return
        pager.post { p.onPageSelected(pager.currentItem) }
        handler.post(progressTick)
    }

    override fun onPause() {
        super.onPause()
        pool?.pauseCurrent()
        handler.removeCallbacks(progressTick)
    }

    override fun onStop() {
        super.onStop()
        // 被滑走（或切后台）：释放本频道解码器，避免多频道累积超过硬件上限
        pool?.releaseAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding?.feedPager?.unregisterOnPageChangeCallback(onPageChange)
        handler.removeCallbacks(progressTick)
        pool?.releaseAll()
        pool = null
        binding = null
    }

    private fun updateProgress() {
        val p = pool ?: return
        val player = p.currentPlayer() ?: return
        val vh = findVH(p.currentPosition) ?: return
        val dur = player.duration()
        if (dur > 0) {
            vh.progressBar.max = dur
            vh.progressBar.progress = player.position()
        }
    }

    private fun findVH(position: Int): FeedAdapter.VH? {
        val pager = binding?.feedPager ?: return null
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        return rv.findViewHolderForAdapterPosition(position) as? FeedAdapter.VH
    }
}
