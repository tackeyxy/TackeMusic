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
import com.tacke.music.utils.CoverUrlResolver
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

        fun bind(song: PlaylistSong, position: Int) {
            tvSongName.text = song.name
            tvArtist.text = song.artists
            tvIndex.text = (position + 1).toString()

            // 设置音源图标
            setupSourceIcon(song)
            ivSource.visibility = View.VISIBLE

            // 加载封面
            loadCover(song)

            // 检查是否被选中
            val isSelected = selectedItems.contains(song.id)

            if (isMultiSelectMode) {
                // 显示圆形复选框，隐藏序号和音源图标
                flCheckbox.visibility = View.VISIBLE
                tvIndex.visibility = View.GONE
                ivSource.visibility = View.GONE

                // 设置复选框状态
                updateCheckboxState(isSelected)

                // 设置选中状态的视觉反馈
                updateSelectedVisuals(isSelected)

                // 点击切换选中状态
                itemView.setOnClickListener {
                    toggleSelection(song.id)
                    updateCheckboxState(selectedItems.contains(song.id))
                    updateSelectedVisuals(selectedItems.contains(song.id))
                    onItemClick(song)
                }

                // 点击复选框区域也切换
                flCheckbox.setOnClickListener {
                    toggleSelection(song.id)
                    updateCheckboxState(selectedItems.contains(song.id))
                    updateSelectedVisuals(selectedItems.contains(song.id))
                    onItemClick(song)
                }
            } else {
                // 非多选模式
                flCheckbox.visibility = View.GONE
                tvIndex.visibility = View.VISIBLE
                ivSource.visibility = View.VISIBLE

                // 重置视觉状态
                resetVisuals()

                itemView.setOnClickListener { onItemClick(song) }
            }

            itemView.setOnLongClickListener { onLongClick(song) }
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

        private fun isLocalSong(song: PlaylistSong): Boolean {
            return song.platform.equals("LOCAL", ignoreCase = true) || song.id.startsWith("local_")
        }

        private fun setupSourceIcon(song: PlaylistSong) {
            val iconRes = when {
                isLocalSong(song) -> R.drawable.ic_local_music
                song.platform.uppercase() == "KUWO" -> R.drawable.ic_kuwo_logo
                song.platform.uppercase() == "NETEASE" -> R.drawable.ic_netease_logo
                else -> R.drawable.ic_music_note
            }
            ivSource.setImageResource(iconRes)
        }

        private fun loadCover(song: PlaylistSong) {
            val isLocalSong = isLocalSong(song)
            lifecycleScope?.launch {
                // 1. 首先尝试从本地缓存获取封面图片（最高优先级）
                val localCoverPath = withContext(Dispatchers.IO) {
                    CoverImageManager.getCoverPath(itemView.context, song.id, song.platform)
                }

                if (localCoverPath != null) {
                    // 本地有缓存，直接使用本地图片
                    Glide.with(itemView.context)
                        .load(File(localCoverPath))
                        .placeholder(R.drawable.ic_music_note)
                        .error(R.drawable.ic_music_note)
                        .into(ivCover)
                    return@launch
                }

                // 2. 本地没有缓存，使用传入的coverUrl
                val coverUrl = song.coverUrl
                when {
                    coverUrl.isNullOrEmpty() -> {
                        if (isLocalSong) {
                            ivCover.setImageResource(R.drawable.ic_music_note)
                            return@launch
                        }
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
                    coverUrl.startsWith("content://") || coverUrl.startsWith("file://") -> {
                        // 本地 URI（MediaStore/SAF/file）
                        Glide.with(itemView.context)
                            .load(coverUrl)
                            .placeholder(R.drawable.ic_music_note)
                            .error(R.drawable.ic_music_note)
                            .into(ivCover)
                    }
                    coverUrl.startsWith("/") -> {
                        // 本地图片路径（以/开头的绝对路径）
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
                    else -> {
                        if (isLocalSong) {
                            ivCover.setImageResource(R.drawable.ic_music_note)
                            return@launch
                        }
                        // 相对路径（如酷我音乐的封面URL），需要解析为完整URL
                        ivCover.setImageResource(R.drawable.ic_music_note)
                        resolveAndLoadCover(song, coverUrl)
                    }
                }
            }
        }

        private fun resolveAndLoadCover(song: PlaylistSong, relativeUrl: String) {
            if (isLocalSong(song)) {
                return
            }
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val resolvedUrl = withContext(Dispatchers.IO) {
                        CoverUrlResolver.resolveCoverUrl(
                            context,
                            relativeUrl,
                            song.id,
                            song.platform
                        )
                    }

                    if (resolvedUrl != null) {
                        // 使用解析后的URL加载封面
                        Glide.with(context)
                            .load(resolvedUrl)
                            .placeholder(R.drawable.ic_music_note)
                            .error(R.drawable.ic_music_note)
                            .into(ivCover)

                        // 通知外部封面已加载，更新数据库
                        onCoverLoaded?.invoke(song.id, resolvedUrl as String)
                    } else {
                        // 解析失败，尝试下载缓存
                        downloadAndCacheCover(song)
                    }
                } catch (e: Exception) {
                    // 解析失败，尝试下载缓存
                    downloadAndCacheCover(song)
                }
            }
        }

        private fun downloadAndCacheCover(song: PlaylistSong) {
            if (isLocalSong(song)) {
                return
            }
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
