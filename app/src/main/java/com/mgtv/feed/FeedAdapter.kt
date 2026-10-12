package com.mgtv.feed

import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mgtv.feed.session.FeedPlayerSession
import com.mgtv.feed.ui.FullScreenController

class FeedAdapter(
    private val items: List<FeedItem>,
    private val fullScreenController: FullScreenController
) : RecyclerView.Adapter<FeedAdapter.VH>() {

    companion object {
        private const val TAG = "FeedAdapter"
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val smallTexture: TextureView = view.findViewById(R.id.smallTexture)
        val cover: View = view.findViewById(R.id.coverPlaceholder)
        val btnFull: ImageView = view.findViewById(R.id.btnFullscreen)
        val tvTip: TextView = view.findViewById(R.id.tvTip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_feed, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvTip.text = "item #${item.id}\n点右下角按钮全屏"

        // 关键：小窗 TextureView 的 SurfaceTexture 生命周期
        holder.smallTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                Log.d(TAG, "small surface available pos=$position")
                FeedPlayerSession.instance.bindSmallSurfaceIfNeeded(holder.smallTexture)
            }

            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                // 这个小窗要被回收了：如果它正好是当前在播的，先把内核 surface 摘掉，避免崩
                val session = FeedPlayerSession.instance
                if (session.attachedSmallTexture === holder.smallTexture &&
                    !session.isInFullscreenRender
                ) {
                    session.switchSurface(null)
                }
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }

        holder.btnFull.setOnClickListener {
            // 只有当前正在播的那个 item 才能全屏（避免点错）
            if (FeedPlayerSession.instance.attachedSmallTexture === holder.smallTexture) {
                fullScreenController.enter(holder.smallTexture)
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
