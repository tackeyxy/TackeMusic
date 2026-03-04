package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.data.model.PlaylistSong

class PlaylistDialogAdapter(
    private val currentPlayingIndex: Int,
    private val onItemClick: (Int) -> Unit,
    private val onRemoveClick: (Int, PlaylistSong) -> Unit
) : RecyclerView.Adapter<PlaylistDialogAdapter.ViewHolder>() {

    private var songs: List<PlaylistSong> = emptyList()

    fun submitList(newSongs: List<PlaylistSong>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    fun updateCurrentPlayingIndex(newIndex: Int) {
        val oldIndex = currentPlayingIndex
        notifyItemChanged(oldIndex)
        notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_dialog_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.bind(song, position, position == currentPlayingIndex)
    }

    override fun getItemCount(): Int = songs.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        private val ivPlaying: ImageView = itemView.findViewById(R.id.ivPlaying)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)

        fun bind(song: PlaylistSong, position: Int, isPlaying: Boolean) {
            tvSongName.text = song.name
            tvArtist.text = song.artists

            if (isPlaying) {
                tvIndex.visibility = View.GONE
                ivPlaying.visibility = View.VISIBLE
            } else {
                tvIndex.visibility = View.VISIBLE
                ivPlaying.visibility = View.GONE
                tvIndex.text = (position + 1).toString()
            }

            itemView.setOnClickListener {
                onItemClick(adapterPosition)
            }

            btnMore.setOnClickListener {
                onRemoveClick(adapterPosition, song)
            }
        }
    }
}