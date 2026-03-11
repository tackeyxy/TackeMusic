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

            // 加载歌单封面（优先显示最新歌曲封面）
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

        private fun loadCoverImage(playlist: Playlist) {
            val coverPath = playlist.coverUrl

            when {
                coverPath.isNullOrEmpty() -> {
                    // 无封面，显示默认背景和图标
                    showDefaultCover(playlist)
                }
                coverPath.startsWith("http") -> {
                    // 网络图片 - 使用 Glide
                    showCoverImage()
                    Glide.with(itemView.context)
                        .load(coverPath)
                        .placeholder(R.drawable.ic_playlist)
                        .error(R.drawable.ic_playlist)
                        .centerCrop()
                        .into(ivCover)
                }
                else -> {
                    // 本地文件 - 使用 Glide 加载本地文件
                    showCoverImage()
                    try {
                        val file = java.io.File(coverPath)
                        if (file.exists()) {
                            Glide.with(itemView.context)
                                .load(file)
                                .placeholder(R.drawable.ic_playlist)
                                .error(R.drawable.ic_playlist)
                                .centerCrop()
                                .into(ivCover)
                        } else {
                            showDefaultCover(playlist)
                        }
                    } catch (e: Exception) {
                        showDefaultCover(playlist)
                    }
                }
            }
        }

        private fun showCoverImage() {
            ivCover.visibility = View.VISIBLE
            ivCover.setImageDrawable(null) // 清除之前的图片
            cvIconBackground.setCardBackgroundColor(Color.TRANSPARENT)
        }

        private fun showDefaultCover(playlist: Playlist) {
            ivCover.visibility = View.GONE
            ivCover.setImageDrawable(null) // 清除之前的图片
            cvIconBackground.visibility = View.VISIBLE
            cvIconBackground.setCardBackgroundColor(getIconColor(playlist))
        }

        private fun getIconColor(playlist: Playlist): Int {
            val colorString = playlist.iconColor
            return if (!colorString.isNullOrEmpty()) {
                try {
                    Color.parseColor(colorString)
                } catch (e: IllegalArgumentException) {
                    Color.parseColor("#2D2D4A")
                }
            } else {
                Color.parseColor("#2D2D4A")
            }
        }
    }
}
