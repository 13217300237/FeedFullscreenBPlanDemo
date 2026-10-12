package com.mgtv.feed

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.mgtv.feed.player.PlayerPool
import com.mgtv.feed.player.TextureFit

/**
 * Feed 流适配器，非常薄：
 * - 只管绑定数据、上报 TextureView 生命周期给 [PlayerPool]；
 * - 封面显示与否完全由 [PlayerPool.renderedPositions] 决定（首帧渲染出来才揭开）；
 * - 播放/暂停/全屏都转发出去，自己不碰播放器；
 * - 点赞/收藏是本地状态切换，分享走系统分享面板。
 */
class FeedAdapter(
    private val items: List<FeedItem>,
    private val pool: PlayerPool,
    private val onFullscreen: () -> Unit
) : RecyclerView.Adapter<FeedAdapter.VH>() {

    /** 本地互动状态（demo 不持久化）。 */
    private val likedSet = HashSet<Int>()
    private val collectedSet = HashSet<Int>()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val texture: TextureView = view.findViewById(R.id.texture)
        val cover: View = view.findViewById(R.id.cover)
        val tvAuthor: TextView = view.findViewById(R.id.tvAuthor)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val ivPause: ImageView = view.findViewById(R.id.ivPause)
        val btnFull: ImageView = view.findViewById(R.id.btnFullscreen)
        val seekBar: SeekBar = view.findViewById(R.id.seekBar)
        val btnLike: ImageView = view.findViewById(R.id.btnLike)
        val tvLikeCount: TextView = view.findViewById(R.id.tvLikeCount)
        val btnComment: ImageView = view.findViewById(R.id.btnComment)
        val tvCommentCount: TextView = view.findViewById(R.id.tvCommentCount)
        val btnCollect: ImageView = view.findViewById(R.id.btnCollect)
        val tvCollectCount: TextView = view.findViewById(R.id.tvCollectCount)
        val btnShare: ImageView = view.findViewById(R.id.btnShare)
        val tvShareCount: TextView = view.findViewById(R.id.tvShareCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_feed, parent, false)
        return VH(v)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: VH, @SuppressLint("RecyclerView") position: Int) {
        val item = items[position]
        holder.tvAuthor.text = "@创作者${item.id}号"
        holder.tvTitle.text = item.title

        // 伪互动数：由 id 派生，稳定不随机
        val likeBase = 800 + (item.id * 733 % 9000).toInt()
        val commentBase = 60 + (item.id * 97 % 800).toInt()
        val collectBase = 30 + (item.id * 53 % 2000).toInt()
        val shareBase = 10 + (item.id * 31 % 500).toInt()

        bindLike(holder, position, likeBase)
        holder.tvCommentCount.text = fmt(commentBase)
        bindCollect(holder, position, collectBase)
        holder.tvShareCount.text = fmt(shareBase)

        // 首帧渲染出来之前盖着封面，避免黑屏一闪
        holder.cover.visibility = if (position in pool.renderedPositions) View.GONE else View.VISIBLE
        holder.ivPause.visibility = View.GONE
        holder.seekBar.max = 1000
        holder.seekBar.progress = 0

        // 进度条可拖：拖动期间进度不回写（pool.isSeeking），松手才 seek
        holder.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(sb: SeekBar) {
                pool.setSeeking(position, true)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                pool.seekTo(position, sb.progress)
                pool.setSeeking(position, false)
            }
        })

        // 复用出来的 View 可能带着上一个视频的变换，先按当前视频尺寸校正；尺寸未知就清掉
        val size = pool.videoSizeOf(position)
        if (size != null) {
            TextureFit.apply(holder.texture, size.first, size.second)
        } else {
            holder.texture.setTransform(null)
        }

        holder.texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                // surface 尺寸此时才确定，补一次校正
                pool.videoSizeOf(position)?.let { (vw, vh) ->
                    TextureFit.apply(holder.texture, vw, vh)
                }
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
            if (position == pool.currentPosition) onFullscreen()
        }

        // 评论：demo 仅提示
        holder.btnComment.setOnClickListener {
            Toast.makeText(holder.itemView.context, "评论面板 demo", Toast.LENGTH_SHORT).show()
        }

        // 分享：走系统分享面板
        holder.btnShare.setOnClickListener {
            val ctx = holder.itemView.context
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${item.title}\n${item.url}")
            }
            ctx.startActivity(Intent.createChooser(intent, "分享视频"))
        }
    }

    private fun bindLike(holder: VH, position: Int, base: Int) {
        val liked = position in likedSet
        holder.btnLike.imageTintList =
            ColorStateList.valueOf(if (liked) Color.parseColor("#FE2C55") else Color.WHITE)
        holder.tvLikeCount.text = fmt(base + if (liked) 1 else 0)
        holder.btnLike.setOnClickListener {
            if (!likedSet.add(position)) likedSet.remove(position)
            bindLike(holder, position, base)
        }
    }

    private fun bindCollect(holder: VH, position: Int, base: Int) {
        val collected = position in collectedSet
        holder.btnCollect.imageTintList =
            ColorStateList.valueOf(if (collected) Color.parseColor("#FFC107") else Color.WHITE)
        holder.tvCollectCount.text = fmt(base + if (collected) 1 else 0)
        holder.btnCollect.setOnClickListener {
            if (!collectedSet.add(position)) collectedSet.remove(position)
            bindCollect(holder, position, base)
        }
    }

    private fun fmt(n: Int): String =
        if (n >= 10000) "${n / 10000}.${n % 10000 / 1000}w" else n.toString()

    override fun getItemCount(): Int = items.size
}
