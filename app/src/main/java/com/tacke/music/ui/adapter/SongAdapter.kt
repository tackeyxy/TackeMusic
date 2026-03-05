package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.R
import com.tacke.music.data.model.Song

class SongAdapter(
    private val onItemClick: (Song) -> Unit,
    private val onMoreClick: (Song) -> Unit,
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
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)

        fun bind(song: Song) {
            tvSongName.text = song.name
            tvArtist.text = song.artists

            // 设置音源标签 - 使用歌曲自己的平台信息
            setupSourceTag(song.platform)

            if (isMultiSelectMode) {
                checkBox.visibility = View.VISIBLE
                btnMore.visibility = View.GONE
                ivSource.visibility = View.GONE
                
                // 移除监听器避免循环触发
                checkBox.setOnCheckedChangeListener(null)
                checkBox.isChecked = selectedItems.contains(song.id)
                
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    onSelectionChange?.invoke(song.id, isChecked)
                }

                itemView.setOnClickListener {
                    val newCheckedState = !checkBox.isChecked
                    checkBox.isChecked = newCheckedState
                    onSelectionChange?.invoke(song.id, newCheckedState)
                }
            } else {
                checkBox.visibility = View.GONE
                btnMore.visibility = View.VISIBLE
                ivSource.visibility = View.VISIBLE

                itemView.setOnClickListener { onItemClick(song) }
                btnMore.setOnClickListener { onMoreClick(song) }
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
