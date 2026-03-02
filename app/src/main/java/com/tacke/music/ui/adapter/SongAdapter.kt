package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.data.model.Song

class SongAdapter(
    private val onItemClick: (Song) -> Unit,
    private val onMoreClick: (Song) -> Unit,
    private val onLongClick: ((Song) -> Boolean)? = null
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var songs: List<Song> = emptyList()
    private var isMultiSelectMode = false
    private val selectedItems = mutableSetOf<String>()

    fun submitList(newSongs: List<Song>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    fun addSongs(newSongs: List<Song>) {
        val currentSize = songs.size
        songs = songs + newSongs
        notifyItemRangeInserted(currentSize, newSongs.size)
    }

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedItems.clear()
        }
        notifyDataSetChanged()
    }

    fun isMultiSelectMode(): Boolean = isMultiSelectMode

    fun setSelectedItems(selected: Set<String>) {
        selectedItems.clear()
        selectedItems.addAll(selected)
        notifyDataSetChanged()
    }

    fun getSelectedSongs(): List<Song> {
        return songs.filter { selectedItems.contains(it.id) }
    }

    fun getAllSongs(): List<Song> = songs

    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(songs.map { it.id })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)

        fun bind(song: Song) {
            tvSongName.text = song.name
            tvArtist.text = song.artists

            if (isMultiSelectMode) {
                checkBox.visibility = View.VISIBLE
                btnMore.visibility = View.GONE
                checkBox.isChecked = selectedItems.contains(song.id)
                checkBox.setOnCheckedChangeListener(null)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedItems.add(song.id)
                    } else {
                        selectedItems.remove(song.id)
                    }
                }

                itemView.setOnClickListener {
                    checkBox.isChecked = !checkBox.isChecked
                    if (checkBox.isChecked) {
                        selectedItems.add(song.id)
                    } else {
                        selectedItems.remove(song.id)
                    }
                    onItemClick(song)
                }
            } else {
                checkBox.visibility = View.GONE
                btnMore.visibility = View.VISIBLE

                itemView.setOnClickListener { onItemClick(song) }
                btnMore.setOnClickListener { onMoreClick(song) }
            }

            itemView.setOnLongClickListener {
                onLongClick?.invoke(song) ?: false
            }
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
