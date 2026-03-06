package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.data.model.ChartSong

class ChartSongAdapter(
    private val onItemClick: (ChartSong, Int) -> Unit,
    private val onLongClick: ((ChartSong) -> Boolean)? = null
) : RecyclerView.Adapter<ChartSongAdapter.ViewHolder>() {

    private var songs: List<ChartSong> = emptyList()
    private var isMultiSelectMode = false
    private val selectedItems = mutableSetOf<String>()

    fun submitList(newList: List<ChartSong>) {
        songs = newList
        notifyDataSetChanged()
    }

    fun getSongs(): List<ChartSong> = songs

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedItems.clear()
        }
        notifyDataSetChanged()
    }

    fun isMultiSelectMode(): Boolean = isMultiSelectMode

    fun getSelectedSongs(): List<ChartSong> {
        return songs.filter { selectedItems.contains(it.id) }
    }

    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(songs.map { it.id })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    fun setSelectedItems(selected: Set<String>) {
        selectedItems.clear()
        selectedItems.addAll(selected)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chart_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(songs[position], position + 1)
    }

    override fun getItemCount(): Int = songs.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val itemContainer: FrameLayout = itemView.findViewById(R.id.itemContainer)
        private val flCheckbox: FrameLayout = itemView.findViewById(R.id.flCheckbox)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
        private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val ivSource: ImageView = itemView.findViewById(R.id.ivSource)

        private var currentSong: ChartSong? = null
        private var currentRank: Int = 0

        init {
            itemView.setOnClickListener {
                currentSong?.let { song ->
                    if (isMultiSelectMode) {
                        toggleSelection(song.id)
                        updateCheckboxState(selectedItems.contains(song.id))
                        updateSelectedVisuals(selectedItems.contains(song.id))
                    }
                    onItemClick(song, currentRank)
                }
            }

            itemView.setOnLongClickListener {
                currentSong?.let { onLongClick?.invoke(it) } ?: false
            }
        }

        fun bind(song: ChartSong, rank: Int) {
            currentSong = song
            currentRank = rank
            tvIndex.text = rank.toString()
            tvSongName.text = song.name
            tvArtist.text = song.artist

            // 设置音源图标
            val sourceIcon = when (song.source.lowercase()) {
                "kuwo" -> R.drawable.ic_kuwo_logo
                "netease" -> R.drawable.ic_netease_logo
                else -> R.drawable.ic_music_note
            }
            ivSource.setImageResource(sourceIcon)

            // 前三名使用特殊颜色
            val context = itemView.context
            when (rank) {
                1 -> tvIndex.setTextColor(context.getColor(R.color.vip_gold))
                2 -> tvIndex.setTextColor(context.getColor(R.color.text_secondary))
                3 -> tvIndex.setTextColor(context.getColor(R.color.primary))
                else -> tvIndex.setTextColor(context.getColor(R.color.text_primary))
            }

            // 检查是否被选中
            val isSelected = selectedItems.contains(song.id)

            if (isMultiSelectMode) {
                // 显示圆形复选框，隐藏序号
                flCheckbox.visibility = View.VISIBLE
                tvIndex.visibility = View.GONE

                // 设置复选框状态
                updateCheckboxState(isSelected)

                // 设置选中状态的视觉反馈
                updateSelectedVisuals(isSelected)
            } else {
                // 非多选模式
                flCheckbox.visibility = View.GONE
                tvIndex.visibility = View.VISIBLE

                // 重置视觉状态
                resetVisuals()
            }
        }

        private fun toggleSelection(songId: String) {
            if (selectedItems.contains(songId)) {
                selectedItems.remove(songId)
            } else {
                selectedItems.add(songId)
            }
        }

        private fun updateCheckboxState(isSelected: Boolean) {
            ivCheckbox.isSelected = isSelected
        }

        private fun updateSelectedVisuals(isSelected: Boolean) {
            if (isSelected) {
                // 选中状态：背景变柔和蓝色
                itemContainer.setBackgroundResource(R.drawable.bg_item_selected)
            } else {
                // 未选中状态
                itemContainer.setBackgroundResource(android.R.color.transparent)
            }
        }

        private fun resetVisuals() {
            itemContainer.setBackgroundResource(android.R.color.transparent)
        }
    }
}
