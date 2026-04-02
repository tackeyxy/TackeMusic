package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.data.model.ChartSong

class ChartSongAdapter(
    private val onItemClick: (ChartSong, Int) -> Unit,
    private val onLongClick: ((ChartSong) -> Boolean)? = null,
    private val lifecycleScope: LifecycleCoroutineScope? = null
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
        private val flIndex: View = itemView.findViewById(R.id.flIndex)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
        private val tvRank: TextView = itemView.findViewById(R.id.tvRank)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val ivSource: ImageView = itemView.findViewById(R.id.ivSource)

        fun bind(song: ChartSong, rank: Int) {
            tvRank.text = rank.toString()
            tvSongName.text = song.name
            tvArtist.text = song.artist

            // 设置平台图标
            val sourceIcon = when (song.source.lowercase()) {
                "kuwo", "kw" -> R.drawable.ic_kuwo_logo
                else -> R.drawable.ic_netease_logo
            }
            ivSource.setImageResource(sourceIcon)
            ivSource.visibility = if (isMultiSelectMode) View.GONE else View.VISIBLE

            // 前三名使用特殊颜色
            val context = itemView.context
            when (rank) {
                1 -> tvRank.setTextColor(context.getColor(R.color.vip_gold))
                2 -> tvRank.setTextColor(context.getColor(R.color.text_secondary))
                3 -> tvRank.setTextColor(context.getColor(R.color.primary))
                else -> tvRank.setTextColor(context.getColor(R.color.text_primary))
            }

            if (isMultiSelectMode) {
                flIndex.visibility = View.VISIBLE
                tvRank.visibility = View.GONE
                val isSelected = selectedItems.contains(song.id)
                ivCheckbox.isSelected = isSelected

                itemView.setOnClickListener {
                    val newSelectedState = !ivCheckbox.isSelected
                    ivCheckbox.isSelected = newSelectedState
                    if (newSelectedState) {
                        selectedItems.add(song.id)
                    } else {
                        selectedItems.remove(song.id)
                    }
                    onItemClick(song, rank)
                }
            } else {
                flIndex.visibility = View.GONE
                tvRank.visibility = View.VISIBLE

                itemView.setOnClickListener {
                    onItemClick(song, rank)
                }
            }

            itemView.setOnLongClickListener {
                onLongClick?.invoke(song) ?: false
            }
        }
    }
}
