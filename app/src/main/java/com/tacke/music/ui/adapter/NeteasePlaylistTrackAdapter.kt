package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.data.api.PlaylistTrack

class NeteasePlaylistTrackAdapter(
    private val onItemClick: (PlaylistTrack) -> Unit,
    private val onLongClick: ((PlaylistTrack) -> Boolean)? = null
) : RecyclerView.Adapter<NeteasePlaylistTrackAdapter.TrackViewHolder>() {

    private var tracks: List<PlaylistTrack> = emptyList()
    private var isMultiSelectMode = false
    private val selectedItems = mutableSetOf<Long>()

    fun submitList(newTracks: List<PlaylistTrack>) {
        tracks = newTracks
        notifyDataSetChanged()
    }

    fun addTracks(newTracks: List<PlaylistTrack>) {
        val startPosition = tracks.size
        tracks = tracks + newTracks
        notifyItemRangeInserted(startPosition, newTracks.size)
    }

    fun getAllTracks(): List<PlaylistTrack> = tracks

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedItems.clear()
        }
        notifyDataSetChanged()
    }

    fun isMultiSelectMode(): Boolean = isMultiSelectMode

    fun getSelectedTracks(): List<PlaylistTrack> {
        return tracks.filter { selectedItems.contains(it.id) }
    }

    fun setSelectedItems(selected: Set<Long>) {
        selectedItems.clear()
        selectedItems.addAll(selected)
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(tracks.map { it.id })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_netease_playlist_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(tracks[position], position + 1)
    }

    override fun getItemCount(): Int = tracks.size

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvNumber: TextView = itemView.findViewById(R.id.tvNumber)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val flIndex: View = itemView.findViewById(R.id.flIndex)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
        private val ivSource: ImageView = itemView.findViewById(R.id.ivSource)

        fun bind(track: PlaylistTrack, position: Int) {
            tvNumber.text = position.toString()
            tvSongName.text = track.name
            tvArtist.text = track.ar?.joinToString(",") { it.name } ?: "未知艺人"

            // 设置音源图标为网易云音乐
            ivSource.setImageResource(R.drawable.ic_netease_logo)
            ivSource.visibility = View.VISIBLE

            if (isMultiSelectMode) {
                flIndex.visibility = View.VISIBLE
                tvNumber.visibility = View.GONE
                ivSource.visibility = View.GONE
                val isSelected = selectedItems.contains(track.id)
                ivCheckbox.isSelected = isSelected

                itemView.setOnClickListener {
                    val newSelectedState = !ivCheckbox.isSelected
                    ivCheckbox.isSelected = newSelectedState
                    if (newSelectedState) {
                        selectedItems.add(track.id)
                    } else {
                        selectedItems.remove(track.id)
                    }
                    onItemClick(track)
                }
            } else {
                flIndex.visibility = View.GONE
                tvNumber.visibility = View.VISIBLE
                ivSource.visibility = View.VISIBLE

                itemView.setOnClickListener {
                    onItemClick(track)
                }
            }

            itemView.setOnLongClickListener {
                onLongClick?.invoke(track) ?: false
            }
        }
    }
}
