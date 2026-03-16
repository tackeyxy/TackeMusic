package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.ui.LocalMusic

class LocalMusicAdapter(
    private val onItemClick: (LocalMusic) -> Unit,
    private val onItemLongClick: (LocalMusic) -> Boolean
) : ListAdapter<LocalMusic, LocalMusicAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_local_music, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val music = getItem(position)
        holder.bind(music, position + 1)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(position))
                }
            }

            itemView.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick(getItem(position))
                } else {
                    false
                }
            }

            btnMore.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick(getItem(position))
                }
            }
        }

        fun bind(music: LocalMusic, index: Int) {
            tvIndex.text = index.toString()
            tvTitle.text = music.title
            tvArtist.text = music.artist
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<LocalMusic>() {
        override fun areItemsTheSame(oldItem: LocalMusic, newItem: LocalMusic): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LocalMusic, newItem: LocalMusic): Boolean {
            return oldItem == newItem
        }
    }
}
