package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.data.model.PlaylistSong

class PlaylistSongAdapter(
    private val onItemClick: (PlaylistSong) -> Unit,
    private val onMoreClick: (PlaylistSong) -> Unit,
    private val onLongClick: (PlaylistSong) -> Boolean
) : RecyclerView.Adapter<PlaylistSongAdapter.SongViewHolder>() {

    private var songs: List<PlaylistSong> = emptyList()
    private var isMultiSelectMode = false
    private val selectedItems = mutableSetOf<String>()

    fun submitList(newSongs: List<PlaylistSong>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedItems.clear()
        }
        notifyDataSetChanged()
    }

    fun setSelectedItems(selected: Set<String>) {
        selectedItems.clear()
        selectedItems.addAll(selected)
        notifyDataSetChanged()
    }

    fun getAllSongs(): List<PlaylistSong> = songs

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)

        fun bind(song: PlaylistSong) {
            tvSongName.text = song.name
            tvArtist.text = song.artists

            if (isMultiSelectMode) {
                checkBox.visibility = View.VISIBLE
                btnMore.visibility = View.GONE
                checkBox.isChecked = selectedItems.contains(song.id)

                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedItems.add(song.id)
                    } else {
                        selectedItems.remove(song.id)
                    }
                }

                itemView.setOnClickListener {
                    checkBox.isChecked = !checkBox.isChecked
                    onItemClick(song)
                }
            } else {
                checkBox.visibility = View.GONE
                btnMore.visibility = View.VISIBLE

                itemView.setOnClickListener { onItemClick(song) }
                btnMore.setOnClickListener { onMoreClick(song) }
            }

            itemView.setOnLongClickListener { onLongClick(song) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song_selectable, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(songs[position])
    }

    override fun getItemCount(): Int = songs.size
}
