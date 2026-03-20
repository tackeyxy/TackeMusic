package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.ui.LocalMusic

class LocalMusicAdapter(
    private val onItemClick: (LocalMusic) -> Unit,
    private val onItemLongClick: (LocalMusic) -> Boolean,
    private val onMoreClick: (LocalMusic) -> Unit
) : ListAdapter<LocalMusic, LocalMusicAdapter.ViewHolder>(DiffCallback()) {

    private val selectedItems = mutableSetOf<Long>()
    private var isMultiSelectMode = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_local_music, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val music = getItem(position)
        holder.bind(music, position + 1)
    }

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedItems.clear()
        }
        notifyDataSetChanged()
    }

    fun toggleSelection(musicId: Long) {
        if (selectedItems.contains(musicId)) {
            selectedItems.remove(musicId)
        } else {
            selectedItems.add(musicId)
        }
        val position = currentList.indexOfFirst { it.id == musicId }
        if (position != -1) {
            notifyItemChanged(position)
        }
    }

    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(currentList.map { it.id })
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<LocalMusic> {
        return currentList.filter { selectedItems.contains(it.id) }
    }

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val flIndex: FrameLayout = itemView.findViewById(R.id.flIndex)
        private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val music = getItem(position)
                    if (isMultiSelectMode) {
                        toggleSelection(music.id)
                    } else {
                        onItemClick(music)
                    }
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
                    onMoreClick(getItem(position))
                }
            }
        }

        fun bind(music: LocalMusic, index: Int) {
            tvTitle.text = music.title
            tvArtist.text = music.artist

            // 多选模式处理
            if (isMultiSelectMode) {
                flIndex.visibility = View.VISIBLE
                ivCheckbox.visibility = View.VISIBLE
                tvIndex.visibility = View.GONE
                ivCheckbox.isSelected = selectedItems.contains(music.id)
                btnMore.visibility = View.GONE
                updateSelectedBackground(selectedItems.contains(music.id))
            } else {
                flIndex.visibility = View.GONE
                ivCheckbox.visibility = View.GONE
                tvIndex.visibility = View.VISIBLE
                tvIndex.text = index.toString()
                btnMore.visibility = View.VISIBLE
                itemView.setBackgroundResource(android.R.color.transparent)
            }
        }

        private fun updateSelectedBackground(isSelected: Boolean) {
            if (isSelected) {
                itemView.setBackgroundColor(itemView.context.getColor(R.color.light_blue_cyan))
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
            }
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
