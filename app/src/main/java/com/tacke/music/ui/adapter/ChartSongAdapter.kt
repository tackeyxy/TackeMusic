package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.data.model.ChartSong

class ChartSongAdapter(
    private val onItemClick: (ChartSong, Int) -> Unit,
    private val onMoreClick: (ChartSong, View) -> Unit,
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
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)
        private val tvRank: TextView = itemView.findViewById(R.id.tvRank)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val ivMore: ImageView = itemView.findViewById(R.id.ivMore)

        fun bind(song: ChartSong, rank: Int) {
            tvRank.text = rank.toString()
            tvSongName.text = song.name
            tvArtist.text = song.artist

            // 前三名使用特殊颜色
            val context = itemView.context
            when (rank) {
                1 -> tvRank.setTextColor(context.getColor(R.color.vip_gold))
                2 -> tvRank.setTextColor(context.getColor(R.color.text_secondary))
                3 -> tvRank.setTextColor(context.getColor(R.color.primary))
                else -> tvRank.setTextColor(context.getColor(R.color.text_primary))
            }

            if (isMultiSelectMode) {
                checkBox.visibility = View.VISIBLE
                ivMore.visibility = View.GONE
                tvRank.visibility = View.GONE
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
                    onItemClick(song, rank)
                }
            } else {
                checkBox.visibility = View.GONE
                ivMore.visibility = View.VISIBLE
                tvRank.visibility = View.VISIBLE

                itemView.setOnClickListener {
                    onItemClick(song, rank)
                }
                ivMore.setOnClickListener {
                    onMoreClick(song, it)
                }
            }

            itemView.setOnLongClickListener {
                onLongClick?.invoke(song) ?: false
            }
        }
    }
}
