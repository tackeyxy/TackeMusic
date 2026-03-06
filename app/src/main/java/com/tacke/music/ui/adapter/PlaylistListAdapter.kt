package com.tacke.music.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.Playlist

class PlaylistListAdapter(
    private val onItemClick: (Playlist) -> Unit,
    private val onItemLongClick: (Playlist) -> Unit = {},
    private val onMoreClick: ((Playlist) -> Unit)? = null
) : RecyclerView.Adapter<PlaylistListAdapter.PlaylistViewHolder>() {

    private var playlists: List<Playlist> = emptyList()

    fun submitList(newPlaylists: List<Playlist>?) {
        playlists = newPlaylists ?: emptyList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_list, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(playlists[position], position)
    }

    override fun getItemCount(): Int = playlists.size

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cvIconBackground: CardView = itemView.findViewById(R.id.cvIconBackground)
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvCount: TextView = itemView.findViewById(R.id.tvCount)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)

        fun bind(playlist: Playlist, position: Int) {
            tvName.text = playlist.name
            tvCount.text = "${playlist.songCount}首"

            // 设置图标背景颜色
            setupIconBackground(playlist)

            // 加载歌单封面（只使用歌曲封面）
            loadCoverImage(playlist)

            itemView.setOnClickListener { onItemClick(playlist) }
            itemView.setOnLongClickListener {
                onItemLongClick(playlist)
                true
            }

            btnMore.setOnClickListener {
                onMoreClick?.invoke(playlist)
            }
        }

        private fun setupIconBackground(playlist: Playlist) {
            val colorString = playlist.iconColor
            if (!colorString.isNullOrEmpty()) {
                try {
                    val color = Color.parseColor(colorString)
                    cvIconBackground.setCardBackgroundColor(color)
                } catch (e: IllegalArgumentException) {
                    // 颜色解析失败，使用默认颜色
                    cvIconBackground.setCardBackgroundColor(Color.parseColor("#2D2D4A"))
                }
            } else {
                // 没有颜色时使用默认颜色
                cvIconBackground.setCardBackgroundColor(Color.parseColor("#2D2D4A"))
            }
        }

        private fun loadCoverImage(playlist: Playlist) {
            val coverPath = playlist.coverUrl

            when {
                coverPath.isNullOrEmpty() -> {
                    // 无封面，使用默认图标（白色），需要padding让图标看起来合适
                    ivCover.setImageResource(R.drawable.ic_playlist)
                    ivCover.setColorFilter(android.graphics.Color.WHITE)
                    ivCover.setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 14.dpToPx())
                    ivCover.visibility = android.view.View.VISIBLE
                }
                coverPath.startsWith("http") -> {
                    // 网络图片 - 使用 Glide
                    ivCover.clearColorFilter()
                    // 封面图片不需要padding，让它填满整个区域
                    ivCover.setPadding(0, 0, 0, 0)
                    Glide.with(itemView.context)
                        .load(coverPath)
                        .placeholder(R.drawable.ic_playlist)
                        .centerCrop()
                        .into(ivCover)
                }
                else -> {
                    // 本地文件 - 直接使用 BitmapFactory 加载
                    try {
                        val file = java.io.File(coverPath)
                        if (file.exists()) {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(coverPath)
                            if (bitmap != null) {
                                ivCover.clearColorFilter()
                                // 封面图片不需要padding，让它填满整个区域
                                ivCover.setPadding(0, 0, 0, 0)
                                ivCover.setImageBitmap(bitmap)
                            } else {
                                ivCover.setImageResource(R.drawable.ic_playlist)
                                ivCover.setColorFilter(android.graphics.Color.WHITE)
                                ivCover.setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 14.dpToPx())
                            }
                        } else {
                            ivCover.setImageResource(R.drawable.ic_playlist)
                            ivCover.setColorFilter(android.graphics.Color.WHITE)
                            ivCover.setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 14.dpToPx())
                        }
                    } catch (e: Exception) {
                        ivCover.setImageResource(R.drawable.ic_playlist)
                        ivCover.setColorFilter(android.graphics.Color.WHITE)
                        ivCover.setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 14.dpToPx())
                    }
                }
            }
        }

        private fun Int.dpToPx(): Int {
            return (this * itemView.context.resources.displayMetrics.density).toInt()
        }
    }
}
