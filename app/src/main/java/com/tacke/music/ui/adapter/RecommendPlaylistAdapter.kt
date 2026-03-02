package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.tacke.music.R
import com.tacke.music.data.api.HighQualityPlaylist

class RecommendPlaylistAdapter(
    private val onPlaylistClick: (HighQualityPlaylist) -> Unit
) : RecyclerView.Adapter<RecommendPlaylistAdapter.PlaylistViewHolder>() {

    private var playlists: List<HighQualityPlaylist> = emptyList()

    fun submitList(newPlaylists: List<HighQualityPlaylist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }

    fun addPlaylists(newPlaylists: List<HighQualityPlaylist>) {
        val startPosition = playlists.size
        playlists = playlists + newPlaylists
        notifyItemRangeInserted(startPosition, newPlaylists.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recommend_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(playlists[position])
    }

    override fun getItemCount(): Int = playlists.size

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCover: ImageView = itemView.findViewById(R.id.ivPlaylistCover)
        private val tvName: TextView = itemView.findViewById(R.id.tvPlaylistName)
        private val tvPlayCount: TextView = itemView.findViewById(R.id.tvPlayCount)

        fun bind(playlist: HighQualityPlaylist) {
            tvName.text = playlist.name
            tvPlayCount.text = formatPlayCount(playlist.playCount)

            // 加载封面图片
            Glide.with(itemView.context)
                .load(playlist.coverImgUrl)
                .apply(
                    RequestOptions()
                        .placeholder(R.drawable.ic_album_default)
                        .error(R.drawable.ic_album_default)
                        .transform(RoundedCorners(12))
                )
                .into(ivCover)

            itemView.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onPlaylistClick(playlists[adapterPosition])
                }
            }
        }

        private fun formatPlayCount(count: Long): String {
            return when {
                count >= 100000000 -> String.format("%.1f亿", count / 100000000.0)
                count >= 10000 -> String.format("%.1f万", count / 10000.0)
                else -> count.toString()
            }
        }
    }
}
