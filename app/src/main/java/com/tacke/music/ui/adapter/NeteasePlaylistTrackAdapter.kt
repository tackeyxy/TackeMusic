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
    private val onMoreClick: (PlaylistTrack) -> Unit
) : RecyclerView.Adapter<NeteasePlaylistTrackAdapter.TrackViewHolder>() {

    private var tracks: List<PlaylistTrack> = emptyList()

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
        private val btnMore: ImageView = itemView.findViewById(R.id.btnMore)

        fun bind(track: PlaylistTrack, position: Int) {
            tvNumber.text = position.toString()
            tvSongName.text = track.name
            tvArtist.text = track.ar?.joinToString(",") { it.name } ?: "未知艺人"

            itemView.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onItemClick(tracks[adapterPosition])
                }
            }

            btnMore.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onMoreClick(tracks[adapterPosition])
                }
            }
        }
    }
}
