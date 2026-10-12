package com.mgtv.feed

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mgtv.feed.databinding.ActivityMainBinding
import com.mgtv.feed.session.FeedPlayerSession
import com.mgtv.feed.ui.FullScreenController

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fullScreenController: FullScreenController
    private val scrollDebounce = Runnable { autoPickPlayTarget() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fullScreenController = FullScreenController(this)

        val adapter = FeedAdapter(FeedData.items, fullScreenController)
        binding.feedRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.feedRecyclerView.adapter = adapter

        // 滚动停止后，取第一个完全可见的 item 来播
        binding.feedRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    rv.removeCallbacks(scrollDebounce)
                    rv.postDelayed(scrollDebounce, 120)
                } else {
                    rv.removeCallbacks(scrollDebounce)
                }
            }
        })

        Toast.makeText(this, "滑动停稳后自动播放中央 item，点右下角按钮横屏全屏", Toast.LENGTH_LONG).show()
    }

    private fun autoPickPlayTarget() {
        val lm = binding.feedRecyclerView.layoutManager as LinearLayoutManager
        val first = lm.findFirstCompletelyVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val vh = binding.feedRecyclerView.findViewHolderForAdapterPosition(first) as? FeedAdapter.VH ?: return
        val item = FeedData.items[first]
        Log.d("MainActivity", "auto pick play pos=$first url=${item.url}")
        FeedPlayerSession.instance.attach(item.url, vh.smallTexture)
    }

    override fun onBackPressed() {
        if (fullScreenController.onBackPressed()) return
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        // 用户切后台：暂停但不释放，回来继续
        FeedPlayerSession.instance.pauseKeep()
    }

    override fun onResume() {
        super.onResume()
        if (FeedPlayerSession.instance.attachedUrl != null) {
            FeedPlayerSession.instance.resume()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.feedRecyclerView.removeCallbacks(scrollDebounce)
        FeedPlayerSession.instance.release()
    }
}
