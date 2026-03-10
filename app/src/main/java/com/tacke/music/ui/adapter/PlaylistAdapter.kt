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

class PlaylistAdapter(
    private val onItemClick: (Playlist) -> Unit,
    private val onMoreClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    private var playlists: List<Playlist> = emptyList()

    fun submitList(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cvCoverContainer: CardView = itemView.findViewById(R.id.cvCoverContainer)
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val vDefaultCover: View = itemView.findViewById(R.id.vDefaultCover)
        private val ivDefaultIcon: ImageView = itemView.findViewById(R.id.ivDefaultIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvCount: TextView = itemView.findViewById(R.id.tvCount)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)

        fun bind(playlist: Playlist) {
            tvName.text = playlist.name
            tvCount.visibility = View.GONE

            // 加载歌单封面
            loadCoverImage(playlist)

            itemView.setOnClickListener { onItemClick(playlist) }
            btnMore.setOnClickListener { onMoreClick(playlist) }
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
                        .placeholder(R.drawable.ic_music_note)
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
                                .placeholder(R.drawable.ic_music_note)
                                .error(R.drawable.ic_music_note)
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
            vDefaultCover.visibility = View.GONE
            ivDefaultIcon.visibility = View.GONE
        }

        private fun showDefaultCover(playlist: Playlist) {
            ivCover.visibility = View.GONE
            vDefaultCover.visibility = View.VISIBLE
            ivDefaultIcon.visibility = View.VISIBLE

            // 设置默认背景颜色
            val colorString = playlist.iconColor
            if (!colorString.isNullOrEmpty()) {
                try {
                    val color = Color.parseColor(colorString)
                    vDefaultCover.setBackgroundColor(color)
                } catch (e: IllegalArgumentException) {
                    vDefaultCover.setBackgroundColor(Color.parseColor("#2D2D4A"))
                }
            } else {
                vDefaultCover.setBackgroundColor(Color.parseColor("#2D2D4A"))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(playlists[position])
    }

    override fun getItemCount(): Int = playlists.size
}
