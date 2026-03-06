package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.utils.CoverImageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlaylistSongAdapter(
    private val onItemClick: (PlaylistSong) -> Unit,
    private val onLongClick: (PlaylistSong) -> Boolean,
    private val lifecycleScope: LifecycleCoroutineScope? = null,
    private val onCoverLoaded: ((String, String) -> Unit)? = null
) : RecyclerView.Adapter<PlaylistSongAdapter.SongViewHolder>() {

    private var songs: List<PlaylistSong> = emptyList()
    private var isMultiSelectMode = false
    private val selectedItems = mutableSetOf<String>()

    fun submitList(newSongs: List<PlaylistSong>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedItems.clear()
        }
        notifyDataSetChanged()
    }

    fun setSelectedItems(selected: Set<String>) {
        selectedItems.clear()
        selectedItems.addAll(selected)
        notifyDataSetChanged()
    }

    fun getAllSongs(): List<PlaylistSong> = songs

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val itemContainer: FrameLayout = itemView.findViewById(R.id.itemContainer)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val ivSource: ImageView = itemView.findViewById(R.id.ivSource)
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val cvCover: CardView = itemView.findViewById(R.id.cvCover)
        private val flCheckbox: FrameLayout = itemView.findViewById(R.id.flCheckbox)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
        private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
        private val coverOverlay: View = itemView.findViewById(R.id.coverOverlay)

        private var currentSong: PlaylistSong? = null

        init {
            itemView.setOnClickListener {
                currentSong?.let { song ->
                    if (isMultiSelectMode) {
                        toggleSelection(song.id)
                        updateCheckboxState(selectedItems.contains(song.id))
                        updateSelectedVisuals(selectedItems.contains(song.id))
                    }
                    onItemClick(song)
                }
            }

            itemView.setOnLongClickListener {
                currentSong?.let { onLongClick(it) } ?: false
            }
        }

        fun bind(song: PlaylistSong, position: Int) {
            currentSong = song
            tvSongName.text = song.name
            tvArtist.text = song.artists
            tvIndex.text = (position + 1).toString()

            // 设置音源图标
            setupSourceIcon(song.platform)

            // 加载封面
            loadCover(song)

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
                // 选中状态：背景变柔和蓝色，封面添加遮罩
                itemContainer.setBackgroundResource(R.drawable.bg_item_selected)
                coverOverlay.visibility = View.VISIBLE
                coverOverlay.alpha = 0.3f
                
                // 封面轻微缩小动画
                cvCover.scaleX = 0.95f
                cvCover.scaleY = 0.95f
            } else {
                // 未选中状态
                itemContainer.setBackgroundResource(android.R.color.transparent)
                coverOverlay.visibility = View.GONE
                
                // 封面恢复原始大小
                cvCover.scaleX = 1f
                cvCover.scaleY = 1f
            }
        }

        private fun resetVisuals() {
            itemContainer.setBackgroundResource(android.R.color.transparent)
            coverOverlay.visibility = View.GONE
            cvCover.scaleX = 1f
            cvCover.scaleY = 1f
        }

        private fun setupSourceIcon(platform: String) {
            val iconRes = when (platform.uppercase()) {
                "KUWO" -> R.drawable.ic_kuwo_logo
                "NETEASE" -> R.drawable.ic_netease_logo
                else -> R.drawable.ic_music_note
            }
            ivSource.setImageResource(iconRes)
        }

        private fun loadCover(song: PlaylistSong) {
            val coverUrl = song.coverUrl

            when {
                coverUrl.isNullOrEmpty() -> {
                    // 没有封面URL，尝试从网络获取
                    ivCover.setImageResource(R.drawable.ic_music_note)
                    downloadAndCacheCover(song)
                }
                coverUrl.startsWith("http") -> {
                    // 网络图片，使用 Glide 加载
                    Glide.with(itemView.context)
                        .load(coverUrl)
                        .placeholder(R.drawable.ic_music_note)
                        .error(R.drawable.ic_music_note)
                        .into(ivCover)
                }
                else -> {
                    // 本地图片路径
                    try {
                        val file = File(coverUrl)
                        if (file.exists()) {
                            Glide.with(itemView.context)
                                .load(file)
                                .placeholder(R.drawable.ic_music_note)
                                .error(R.drawable.ic_music_note)
                                .into(ivCover)
                        } else {
                            // 本地文件不存在，尝试重新下载
                            ivCover.setImageResource(R.drawable.ic_music_note)
                            downloadAndCacheCover(song)
                        }
                    } catch (e: Exception) {
                        ivCover.setImageResource(R.drawable.ic_music_note)
                        downloadAndCacheCover(song)
                    }
                }
            }
        }

        private fun downloadAndCacheCover(song: PlaylistSong) {
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val localPath = CoverImageManager.downloadAndCacheCover(
                        context,
                        song.id,
                        song.platform
                    )

                    if (localPath != null) {
                        // 下载成功，更新UI
                        withContext(Dispatchers.Main) {
                            Glide.with(context)
                                .load(File(localPath))
                                .placeholder(R.drawable.ic_music_note)
                                .error(R.drawable.ic_music_note)
                                .into(ivCover)
                        }

                        // 通知外部封面已加载，更新数据库
                        onCoverLoaded?.invoke(song.id, localPath)
                    }
                } catch (e: Exception) {
                    // 下载失败，保持默认图标
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song_modern, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(songs[position], position)
    }

    override fun getItemCount(): Int = songs.size
}
