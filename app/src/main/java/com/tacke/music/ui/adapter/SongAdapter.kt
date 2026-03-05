package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.data.model.Song

class SongAdapter(
    private val onItemClick: (Song) -> Unit,
    private val onLongClick: ((Song) -> Boolean)? = null,
    private val onSelectionChange: ((String, Boolean) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var songs: List<Song> = emptyList()
    private var isMultiSelectMode = false
    
    // 选择状态由外部管理，解决跨页选择丢失问题
    private var selectedItems: Set<String> = emptySet()

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
        val wasEnabled = isMultiSelectMode
        isMultiSelectMode = enabled
        if (wasEnabled != enabled) {
            notifyDataSetChanged()
        }
    }

    fun isMultiSelectMode(): Boolean = isMultiSelectMode

    /**
     * 设置选中的歌曲ID集合
     * 由外部（Activity）管理选择状态，解决跨页选择丢失问题
     */
    fun setSelectedItems(selected: Set<String>) {
        selectedItems = selected
        notifyDataSetChanged()
    }

    fun getSelectedItems(): Set<String> = selectedItems

    fun getSelectedSongs(): List<Song> {
        return songs.filter { selectedItems.contains(it.id) }
    }

    fun getAllSongs(): List<Song> = songs

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val ivSource: ImageView = itemView.findViewById(R.id.ivSource)
        private val flIndex: View = itemView.findViewById(R.id.flIndex)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)

        fun bind(song: Song) {
            tvSongName.text = song.name
            tvArtist.text = song.artists

            // 设置音源标签 - 使用歌曲自己的平台信息
            setupSourceTag(song.platform)

            if (isMultiSelectMode) {
                flIndex.visibility = View.VISIBLE
                ivSource.visibility = View.GONE

                val isSelected = selectedItems.contains(song.id)
                ivCheckbox.isSelected = isSelected

                itemView.setOnClickListener {
                    val newSelectedState = !ivCheckbox.isSelected
                    ivCheckbox.isSelected = newSelectedState
                    onSelectionChange?.invoke(song.id, newSelectedState)
                }
            } else {
                flIndex.visibility = View.GONE
                ivSource.visibility = View.VISIBLE

                itemView.setOnClickListener { onItemClick(song) }
            }

            itemView.setOnLongClickListener {
                onLongClick?.invoke(song) ?: false
            }
        }

        private fun setupSourceTag(platform: String) {
            val logoResId = when (platform.uppercase()) {
                "KUWO" -> R.drawable.ic_kuwo_logo
                "NETEASE" -> R.drawable.ic_netease_logo
                else -> R.drawable.ic_kuwo_logo
            }
            ivSource.setImageResource(logoResId)
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
