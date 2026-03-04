package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvCount: TextView = itemView.findViewById(R.id.tvCount)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)

        fun bind(playlist: Playlist) {
            tvName.text = playlist.name
            tvCount.visibility = View.GONE

            itemView.setOnClickListener { onItemClick(playlist) }
            btnMore.setOnClickListener { onMoreClick(playlist) }
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
