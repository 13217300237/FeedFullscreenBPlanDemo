package com.mgtv.feed

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.mgtv.feed.databinding.ActivityMainBinding
import com.mgtv.feed.player.PlayerPool
import com.mgtv.feed.ui.FullScreenController

/**
 * 短视频 Feed 流入口。
 *
 * 体验目标对齐抖音：
 * - 竖向整屏翻页（ViewPager2 vertical），一屏一条；
 * - **预加载相邻页**：滑过去基本秒开，不等缓冲；
 * - 封面盖到首帧渲染出来才揭开，不闪黑屏；
 * - 点按暂停/继续，右上角横屏全屏（无缝切 Surface，不重新缓冲）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pool: PlayerPool
    private lateinit var fullScreenController: FullScreenController

    private val onPageChange = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            pool.onPageSelected(position)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pool = PlayerPool(FeedData.items)
        fullScreenController = FullScreenController(this, pool)

        // 首帧渲染出来 → 揭封面
        pool.onFirstFrame = { position ->
            runOnUiThread {
                findVH(position)?.cover?.visibility = android.view.View.GONE
            }
        }
        pool.onPlayError = { position ->
            runOnUiThread {
                Toast.makeText(this, "第 ${position + 1} 条播放失败", Toast.LENGTH_SHORT).show()
            }
        }

        binding.feedPager.adapter = FeedAdapter(FeedData.items, pool, fullScreenController)
        // 相邻页 View 保活，与播放器池的 p-1/p/p+1 窗口对齐
        binding.feedPager.offscreenPageLimit = 1
        binding.feedPager.registerOnPageChangeCallback(onPageChange)

        // 全屏时返回键先退全屏，而不是退出 Activity
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullScreenController.onBackPressed()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        // 首屏：等 ViewPager2 首次布局完成、TextureView 就绪后再播
        binding.feedPager.post { pool.onPageSelected(binding.feedPager.currentItem) }
    }

    private fun findVH(position: Int): FeedAdapter.VH? {
        val rv = binding.feedPager.getChildAt(0) as? RecyclerView ?: return null
        return rv.findViewHolderForAdapterPosition(position) as? FeedAdapter.VH
    }

    override fun onStop() {
        super.onStop()
        // 切后台：暂停当前页但保留已 prepare 的播放器，回来继续
        pool.pauseCurrent()
    }

    override fun onResume() {
        super.onResume()
        pool.resumeCurrent()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.feedPager.unregisterOnPageChangeCallback(onPageChange)
        pool.releaseAll()
    }
}
