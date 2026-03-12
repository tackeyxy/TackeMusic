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
import com.tacke.music.data.model.ChartSong
import com.tacke.music.utils.CoverImageManager
import com.tacke.music.utils.CoverUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChartSongAdapter(
    private val onItemClick: (ChartSong, Int) -> Unit,
    private val onLongClick: ((ChartSong) -> Boolean)? = null,
    private val lifecycleScope: LifecycleCoroutineScope? = null,
    private val onCoverLoaded: ((String, String) -> Unit)? = null
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
        private val ivCover: ImageView? = itemView.findViewById(R.id.ivCover)

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

            // 加载封面（如果布局中有封面ImageView）
            ivCover?.let { loadCover(song, it) }

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

        private fun loadCover(song: ChartSong, ivCover: ImageView) {
            val coverUrl = song.cover

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

        private fun resolveAndLoadCover(song: ChartSong, ivCover: ImageView, relativeUrl: String) {
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val platform = when (song.source.uppercase()) {
                        "KUWO" -> "kuwo"
                        "NETEASE" -> "netease"
                        else -> "kuwo"
                    }
                    val resolvedUrl = withContext(Dispatchers.IO) {
                        CoverUrlResolver.resolveCoverUrl(
                            context,
                            relativeUrl,
                            song.id,
                            platform
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
                        onCoverLoaded?.invoke(song.id, resolvedUrl)
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

        private fun downloadAndCacheCover(song: ChartSong, ivCover: ImageView) {
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val platform = when (song.source.uppercase()) {
                        "KUWO" -> "kuwo"
                        "NETEASE" -> "netease"
                        else -> "kuwo"
                    }
                    val localPath = CoverImageManager.downloadAndCacheCover(
                        context,
                        song.id,
                        platform
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
}
