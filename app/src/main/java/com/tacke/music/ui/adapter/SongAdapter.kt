package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.Song
import com.tacke.music.utils.CoverImageManager
import com.tacke.music.utils.CoverUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SongAdapter(
    private val onItemClick: (Song) -> Unit,
    private val onLongClick: ((Song) -> Boolean)? = null,
    private val onSelectionChange: ((String, Boolean) -> Unit)? = null,
    private val lifecycleScope: LifecycleCoroutineScope? = null,
    private val onCoverLoaded: ((String, String) -> Unit)? = null
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
        private val ivCover: ImageView? = itemView.findViewById(R.id.ivCover)

        fun bind(song: Song) {
            tvSongName.text = song.name
            tvArtist.text = song.artists

            // 设置音源标签 - 使用歌曲自己的平台信息
            setupSourceTag(song.platform)

            // 加载封面（如果布局中有封面ImageView）
            ivCover?.let { loadCover(song, it) }

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

        private fun loadCover(song: Song, ivCover: ImageView) {
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
                        // 没有封面URL，尝试从网络获取
                        ivCover.setImageResource(R.drawable.ic_music_note)
                        downloadAndCacheCover(song, ivCover)
                    }
                    coverUrl.startsWith("http") -> {
                        // 网络图片，使用 Glide 加载
                        Glide.with(itemView.context)
                            .load(coverUrl)
                            .placeholder(R.drawable.ic_music_note)
                            .error(R.drawable.ic_music_note)
                            .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                                override fun onLoadFailed(
                                    e: com.bumptech.glide.load.engine.GlideException?,
                                    model: Any?,
                                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                                    isFirstResource: Boolean
                                ): Boolean {
                                    // 如果网络图片加载失败，尝试解析或下载封面
                                    if (song.platform.equals("kuwo", ignoreCase = true)) {
                                        resolveAndLoadCover(song, ivCover, coverUrl)
                                    } else {
                                        downloadAndCacheCover(song, ivCover)
                                    }
                                    return false
                                }

                                override fun onResourceReady(
                                    resource: android.graphics.drawable.Drawable,
                                    model: Any,
                                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                                    dataSource: com.bumptech.glide.load.DataSource,
                                    isFirstResource: Boolean
                                ): Boolean {
                                    return false
                                }
                            })
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
                                downloadAndCacheCover(song, ivCover)
                            }
                        } catch (e: Exception) {
                            ivCover.setImageResource(R.drawable.ic_music_note)
                            downloadAndCacheCover(song, ivCover)
                        }
                    }
                    else -> {
                        // 相对路径（如酷我音乐的封面URL），需要解析为完整URL
                        ivCover.setImageResource(R.drawable.ic_music_note)
                        resolveAndLoadCover(song, ivCover, coverUrl)
                    }
                }
            }
        }

        private fun resolveAndLoadCover(song: Song, ivCover: ImageView, relativeUrl: String) {
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val resolvedUrl = withContext(Dispatchers.IO) {
                        CoverUrlResolver.resolveCoverUrl(
                            context,
                            relativeUrl,
                            song.id,
                            song.platform,
                            song.name,
                            song.artists
                        )
                    }

                    if (resolvedUrl != null) {
                        // 使用解析后的URL加载封面
                        withContext(Dispatchers.Main) {
                            Glide.with(context)
                                .load(resolvedUrl)
                                .placeholder(R.drawable.ic_music_note)
                                .error(R.drawable.ic_music_note)
                                .into(ivCover)
                        }

                        // 通知外部封面已加载，更新数据
                        onCoverLoaded?.invoke(song.id, resolvedUrl as String)
                    } else {
                        // 解析失败，尝试下载缓存
                        downloadAndCacheCover(song, ivCover)
                    }
                } catch (e: Exception) {
                    // 解析失败，尝试下载缓存
                    downloadAndCacheCover(song, ivCover)
                }
            }
        }

        private fun downloadAndCacheCover(song: Song, ivCover: ImageView) {
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val localPath = CoverImageManager.downloadAndCacheCover(
                        context,
                        song.id,
                        song.platform,
                        "320k",
                        song.name,
                        song.artists
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

                        // 通知外部封面已加载，更新数据
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
            .inflate(R.layout.item_song_selectable, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(songs[position])
    }

    override fun getItemCount(): Int = songs.size
}
