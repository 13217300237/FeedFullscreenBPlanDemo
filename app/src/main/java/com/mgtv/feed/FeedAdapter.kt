package com.mgtv.feed

import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mgtv.feed.player.PlayerPool
import com.mgtv.feed.ui.FullScreenController

/**
 * Feed 流适配器，非常薄：
 * - 只管绑定标题、上报 TextureView 生命周期给 [PlayerPool]；
 * - 封面显示与否完全由 [PlayerPool.renderedPositions] 决定（首帧渲染出来才揭开）；
 * - 播放/暂停/全屏都转发出去，自己不碰播放器。
 */
class FeedAdapter(
    private val items: List<FeedItem>,
    private val pool: PlayerPool,
    private val fullScreenController: FullScreenController
) : RecyclerView.Adapter<FeedAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val texture: TextureView = view.findViewById(R.id.texture)
        val cover: View = view.findViewById(R.id.cover)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val ivPause: ImageView = view.findViewById(R.id.ivPause)
        val btnFull: ImageView = view.findViewById(R.id.btnFullscreen)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_feed, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvTitle.text = "${position + 1}/${items.size} · ${item.title}"

        // 首帧渲染出来之前盖着封面，避免黑屏一闪
        holder.cover.visibility = if (position in pool.renderedPositions) View.GONE else View.VISIBLE
        holder.ivPause.visibility = View.GONE

        holder.texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                pool.onTextureAvailable(position, holder.texture)
            }

            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                pool.onTextureDestroyed(position, holder.texture)
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        // TextureView 可能早就 available（ViewHolder 复用），listener 不会再回调，这里主动补报
        if (holder.texture.isAvailable) {
            pool.onTextureAvailable(position, holder.texture)
        }

        // 点按：暂停/继续
        holder.itemView.setOnClickListener {
            if (position != pool.currentPosition) return@setOnClickListener
            val playing = pool.toggleCurrent()
            holder.ivPause.visibility = if (playing) View.GONE else View.VISIBLE
        }

        // 全屏（仅当前页可点，避免把还没在播的页切上去）
        holder.btnFull.setOnClickListener {
            if (position == pool.currentPosition) fullScreenController.enter()
        }
    }

    override fun getItemCount(): Int = items.size
}
