package com.mgtv.feed

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.mgtv.feed.databinding.ActivityMainBinding
import com.mgtv.feed.player.PlayerPool
import com.mgtv.feed.ui.FullScreenController

/**
 * 多频道短视频入口（抖音式结构）：
 * - 外层横向 ViewPager2 + TabLayout：关注 / 推荐 / 朋友 / 同城 四个频道；
 * - 每个频道是一个 [FeedFragment]，内嵌竖向整屏翻页 feed，各持独立播放池；
 * - FragmentStateAdapter 的 setMaxLifecycle 保证只有当前频道处于 RESUMED，
 *   [FeedFragment.onResume] 时才播放，互不抢画面。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * 当前活动频道的播放池，由 [FeedFragment.onResume] 注册。
     * 全局唯一播放权关口：新频道注册时立刻静音上一个频道，
     * 保证任何时刻最多只有一路在出声（不依赖 Fragment 生命周期回调顺序）。
     */
    var activePool: PlayerPool? = null
        set(value) {
            if (field === value) return
            field?.pauseCurrent()
            field = value
        }

    val fullScreenController by lazy { FullScreenController(this) { activePool } }

    /** 外层频道容器的当前页 —— 判断"谁有播放权"的唯一依据。 */
    fun isCurrentChannel(channel: Int): Boolean = binding.channelPager.currentItem == channel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.channelPager.adapter = ChannelAdapter()
        binding.channelPager.offscreenPageLimit = 1

        // 切频道时主动把播放权交给新频道（旧频道由 activePool 的 setter 立刻静音）
        binding.channelPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                (supportFragmentManager.findFragmentByTag("f$position") as? FeedFragment)
                    ?.activateIfCurrentChannel()
            }
        })

        TabLayoutMediator(binding.tabLayout, binding.channelPager) { tab, position ->
            tab.text = FeedData.channels[position]
        }.attach()

        // 全屏时返回键先退全屏，而不是退出 Activity
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullScreenController.onBackPressed()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    private inner class ChannelAdapter : FragmentStateAdapter(this) {
        override fun getItemCount(): Int = FeedData.channels.size
        override fun createFragment(position: Int): Fragment = FeedFragment.newInstance(position)
    }
}
