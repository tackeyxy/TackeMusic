package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.RecentPlay
import com.tacke.music.utils.CoverImageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentPlayAdapter(
    private val onItemClick: (RecentPlay) -> Unit,
    private val onLongClick: (RecentPlay) -> Boolean,
    private val lifecycleScope: LifecycleCoroutineScope? = null,
    private val onCoverLoaded: ((String, String) -> Unit)? = null
) : RecyclerView.Adapter<RecentPlayAdapter.ViewHolder>() {

    private var recentPlays: List<RecentPlay> = emptyList()
    private var isMultiSelectMode = false
    private val selectedItems = mutableSetOf<String>()

    fun submitList(newRecentPlays: List<RecentPlay>) {
        recentPlays = newRecentPlays
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

    fun getAllRecentPlays(): List<RecentPlay> = recentPlays

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val ivSource: ImageView = itemView.findViewById(R.id.ivSource)
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
        private val flIndex: View = itemView.findViewById(R.id.flIndex)
        private val tvPlayTime: TextView = itemView.findViewById(R.id.tvPlayTime)

        fun bind(recentPlay: RecentPlay) {
            tvSongName.text = recentPlay.name
            tvArtist.text = recentPlay.artists
            tvPlayTime.text = formatTime(recentPlay.playedAt)

            // 设置音源图标
            setupSourceIcon(recentPlay.platform)

            // 加载封面
            loadCover(recentPlay)

            // 检查是否被选中
            val isSelected = selectedItems.contains(recentPlay.id)

            if (isMultiSelectMode) {
                // 显示复选框区域
                flIndex.visibility = View.VISIBLE
                ivCheckbox.visibility = View.VISIBLE
                ivSource.visibility = View.GONE
                tvPlayTime.visibility = View.GONE

                // 设置复选框状态
                ivCheckbox.isSelected = isSelected

                // 设置选中状态的视觉反馈
                updateSelectedBackground(isSelected)

                itemView.setOnClickListener {
                    // 切换选中状态
                    val newSelectedState = !ivCheckbox.isSelected
                    ivCheckbox.isSelected = newSelectedState
                    if (newSelectedState) {
                        selectedItems.add(recentPlay.id)
                    } else {
                        selectedItems.remove(recentPlay.id)
                    }
                    // 更新当前项的背景
                    updateSelectedBackground(newSelectedState)
                    // 通知外部选中状态变化
                    onItemClick(recentPlay)
                }
            } else {
                // 非多选模式
                flIndex.visibility = View.GONE
                ivCheckbox.visibility = View.GONE
                ivSource.visibility = View.VISIBLE
                tvPlayTime.visibility = View.VISIBLE

                // 重置背景
                itemView.setBackgroundResource(android.R.color.transparent)

                itemView.setOnClickListener { onItemClick(recentPlay) }
            }

            itemView.setOnLongClickListener { onLongClick(recentPlay) }
        }

        private fun updateSelectedBackground(isSelected: Boolean) {
            if (isSelected) {
                // 使用浅色主题的主色调作为选中背景
                itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.light_blue_cyan))
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
            }
        }

        private fun setupSourceIcon(platform: String) {
            val iconRes = when (platform.uppercase()) {
                "KUWO" -> R.drawable.ic_kuwo_logo
                "NETEASE" -> R.drawable.ic_netease_logo
                "LOCAL" -> R.drawable.ic_local_music
                else -> R.drawable.ic_music_note
            }
            ivSource.setImageResource(iconRes)
        }

        private fun loadCover(recentPlay: RecentPlay) {
            lifecycleScope?.launch {
                // 1. 首先尝试从本地缓存获取封面图片（最高优先级）
                // 使用小写的平台名称（与CoverImageManager缓存键一致）
                val cachePlatform = recentPlay.platform.lowercase()
                val localCoverPath = withContext(Dispatchers.IO) {
                    CoverImageManager.getCoverPath(itemView.context, recentPlay.id, cachePlatform)
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
                val coverUrl = recentPlay.coverUrl
                when {
                    coverUrl.isNullOrEmpty() -> {
                        // 没有封面URL，尝试从网络获取
                        ivCover.setImageResource(R.drawable.ic_music_note)
                        downloadAndCacheCover(recentPlay)
                    }
                    coverUrl.startsWith("http") -> {
                        // 网络图片，使用 Glide 加载
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
                                downloadAndCacheCover(recentPlay)
                            }
                        } catch (e: Exception) {
                            ivCover.setImageResource(R.drawable.ic_music_note)
                            downloadAndCacheCover(recentPlay)
                        }
                    }
                    else -> {
                        // 相对路径（如酷我音乐的封面URL），需要解析为完整URL
                        ivCover.setImageResource(R.drawable.ic_music_note)
                        resolveAndLoadCover(recentPlay, coverUrl)
                    }
                }
            }
        }

        private fun resolveAndLoadCover(recentPlay: RecentPlay, relativeUrl: String) {
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val resolvedUrl = withContext(Dispatchers.IO) {
                        com.tacke.music.utils.CoverUrlResolver.resolveCoverUrl(
                            context,
                            relativeUrl,
                            recentPlay.id,
                            recentPlay.platform
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
                        onCoverLoaded?.invoke(recentPlay.id, resolvedUrl)
                    } else {
                        // 解析失败，尝试下载缓存
                        downloadAndCacheCover(recentPlay)
                    }
                } catch (e: Exception) {
                    // 解析失败，尝试下载缓存
                    downloadAndCacheCover(recentPlay)
                }
            }
        }

        private fun downloadAndCacheCover(recentPlay: RecentPlay) {
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    // 使用小写的平台名称（与CoverImageManager缓存键一致）
                    val cachePlatform = recentPlay.platform.lowercase()
                    val localPath = CoverImageManager.downloadAndCacheCover(
                        context,
                        recentPlay.id,
                        cachePlatform
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
                        onCoverLoaded?.invoke(recentPlay.id, localPath)
                    }
                } catch (e: Exception) {
                    // 下载失败，保持默认图标
                }
            }
        }

        private fun formatTime(timestamp: Long): String {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_play, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(recentPlays[position])
    }

    override fun getItemCount(): Int = recentPlays.size
}
