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
import com.tacke.music.data.api.PlaylistTrack
import com.tacke.music.utils.CoverImageManager
import com.tacke.music.utils.CoverUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NeteasePlaylistTrackAdapter(
    private val onItemClick: (PlaylistTrack) -> Unit,
    private val onLongClick: ((PlaylistTrack) -> Boolean)? = null,
    private val lifecycleScope: LifecycleCoroutineScope? = null,
    private val onCoverLoaded: ((Long, String) -> Unit)? = null
) : RecyclerView.Adapter<NeteasePlaylistTrackAdapter.TrackViewHolder>() {

    private var tracks: List<PlaylistTrack> = emptyList()
    private var isMultiSelectMode = false
    private val selectedItems = mutableSetOf<Long>()

    fun submitList(newTracks: List<PlaylistTrack>) {
        tracks = newTracks
        notifyDataSetChanged()
    }

    fun addTracks(newTracks: List<PlaylistTrack>) {
        val startPosition = tracks.size
        tracks = tracks + newTracks
        notifyItemRangeInserted(startPosition, newTracks.size)
    }

    fun getAllTracks(): List<PlaylistTrack> = tracks

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedItems.clear()
        }
        notifyDataSetChanged()
    }

    fun isMultiSelectMode(): Boolean = isMultiSelectMode

    fun getSelectedTracks(): List<PlaylistTrack> {
        return tracks.filter { selectedItems.contains(it.id) }
    }

    fun setSelectedItems(selected: Set<Long>) {
        selectedItems.clear()
        selectedItems.addAll(selected)
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(tracks.map { it.id })
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_netease_playlist_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(tracks[position], position + 1)
    }

    override fun getItemCount(): Int = tracks.size

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvNumber: TextView = itemView.findViewById(R.id.tvNumber)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val flIndex: View = itemView.findViewById(R.id.flIndex)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
        private val ivCover: ImageView? = itemView.findViewById(R.id.ivCover)

        fun bind(track: PlaylistTrack, position: Int) {
            tvNumber.text = position.toString()
            tvSongName.text = track.name
            tvArtist.text = track.ar?.joinToString(",") { it.name } ?: "未知艺人"

            // 加载封面（如果布局中有封面ImageView）
            ivCover?.let { loadCover(track, it) }

            if (isMultiSelectMode) {
                flIndex.visibility = View.VISIBLE
                tvNumber.visibility = View.GONE
                val isSelected = selectedItems.contains(track.id)
                ivCheckbox.isSelected = isSelected

                itemView.setOnClickListener {
                    val newSelectedState = !ivCheckbox.isSelected
                    ivCheckbox.isSelected = newSelectedState
                    if (newSelectedState) {
                        selectedItems.add(track.id)
                    } else {
                        selectedItems.remove(track.id)
                    }
                    onItemClick(track)
                }
            } else {
                flIndex.visibility = View.GONE
                tvNumber.visibility = View.VISIBLE

                itemView.setOnClickListener {
                    onItemClick(track)
                }
            }

            itemView.setOnLongClickListener {
                onLongClick?.invoke(track) ?: false
            }
        }

        private fun loadCover(track: PlaylistTrack, ivCover: ImageView) {
            val coverUrl = track.al?.picUrl

            when {
                coverUrl.isNullOrEmpty() -> {
                    // 没有封面URL，尝试从网络获取
                    ivCover.setImageResource(R.drawable.ic_music_note)
                    downloadAndCacheCover(track, ivCover)
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
                            downloadAndCacheCover(track, ivCover)
                        }
                    } catch (e: Exception) {
                        ivCover.setImageResource(R.drawable.ic_music_note)
                        downloadAndCacheCover(track, ivCover)
                    }
                }
                else -> {
                    // 相对路径（如酷我音乐的封面URL），需要解析为完整URL
                    ivCover.setImageResource(R.drawable.ic_music_note)
                    resolveAndLoadCover(track, ivCover, coverUrl)
                }
            }
        }

        private fun resolveAndLoadCover(track: PlaylistTrack, ivCover: ImageView, relativeUrl: String) {
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val resolvedUrl = withContext(Dispatchers.IO) {
                        CoverUrlResolver.resolveCoverUrl(
                            context,
                            relativeUrl,
                            track.id.toString(),
                            "netease"
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
                        onCoverLoaded?.invoke(track.id, resolvedUrl)
                    } else {
                        // 解析失败，尝试下载缓存
                        downloadAndCacheCover(track, ivCover)
                    }
                } catch (e: Exception) {
                    // 解析失败，尝试下载缓存
                    downloadAndCacheCover(track, ivCover)
                }
            }
        }

        private fun downloadAndCacheCover(track: PlaylistTrack, ivCover: ImageView) {
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val localPath = CoverImageManager.downloadAndCacheCover(
                        context,
                        track.id.toString(),
                        "netease"
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
                        onCoverLoaded?.invoke(track.id, localPath)
                    }
                } catch (e: Exception) {
                    // 下载失败，保持默认图标
                }
            }
        }
    }
}
