package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.Playlist

class PlaylistGridAdapter(
    private val onItemClick: (Playlist) -> Unit,
    private val onItemLongClick: (Playlist) -> Unit = {}
) : RecyclerView.Adapter<PlaylistGridAdapter.PlaylistViewHolder>() {

    private var playlists: List<Playlist> = emptyList()

    fun submitList(newPlaylists: List<Playlist>?) {
        playlists = newPlaylists ?: emptyList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_grid, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(playlists[position], position)
    }

    override fun getItemCount(): Int = playlists.size

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)

        fun bind(playlist: Playlist, position: Int) {
            tvName.text = playlist.name

            // 加载歌单封面（只使用歌曲封面）
            loadCoverImage(playlist)

            itemView.setOnClickListener { onItemClick(playlist) }
            itemView.setOnLongClickListener {
                onItemLongClick(playlist)
                true
            }
        }

        private fun loadCoverImage(playlist: Playlist) {
            val coverPath = playlist.coverUrl

            when {
                coverPath.isNullOrEmpty() -> {
                    // 无封面，使用默认图标
                    ivCover.setImageResource(R.drawable.ic_music_note)
                }
                coverPath.startsWith("http") -> {
                    // 网络图片 - 使用 Glide
                    Glide.with(itemView.context)
                        .load(coverPath)
                        .placeholder(R.drawable.ic_music_note)
                        .into(ivCover)
                }
                else -> {
                    // 本地文件 - 直接使用 BitmapFactory 加载
                    try {
                        val file = java.io.File(coverPath)
                        if (file.exists()) {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(coverPath)
                            if (bitmap != null) {
                                ivCover.setImageBitmap(bitmap)
                            } else {
                                ivCover.setImageResource(R.drawable.ic_music_note)
                            }
                        } else {
                            ivCover.setImageResource(R.drawable.ic_music_note)
                        }
                    } catch (e: Exception) {
                        ivCover.setImageResource(R.drawable.ic_music_note)
                    }
                }
            }
        }
    }
}
