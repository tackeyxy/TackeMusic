package com.tacke.music.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.DownloadStatus
import com.tacke.music.data.model.DownloadTask
import com.tacke.music.utils.CoverImageManager
import com.tacke.music.utils.CoverUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadTaskAdapter(
    private val isHistory: Boolean,
    private val onControlClick: (DownloadTask) -> Unit,
    private val onItemClick: (DownloadTask) -> Unit,
    private val onLongClick: (DownloadTask) -> Boolean,
    private val lifecycleScope: LifecycleCoroutineScope? = null
) : RecyclerView.Adapter<DownloadTaskAdapter.TaskViewHolder>() {

    private var tasks: List<DownloadTask> = emptyList()
    private var speeds: Map<String, Long> = emptyMap()
    private val selectedTasks = mutableSetOf<String>()
    private var isMultiSelectMode = false
    private val resolvedCoverUrls = mutableMapOf<String, String>()

    fun submitList(newTasks: List<DownloadTask>) {
        tasks = newTasks
        notifyDataSetChanged()
    }

    fun updateSpeeds(newSpeeds: Map<String, Long>) {
        if (!isHistory) {
            speeds = newSpeeds
            notifyDataSetChanged()
        }
    }

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedTasks.clear()
        }
        notifyDataSetChanged()
    }

    fun toggleSelection(taskId: String) {
        if (selectedTasks.contains(taskId)) {
            selectedTasks.remove(taskId)
        } else {
            selectedTasks.add(taskId)
        }
        val position = tasks.indexOfFirst { it.id == taskId }
        if (position != -1) {
            notifyItemChanged(position)
        }
    }

    fun selectAll() {
        selectedTasks.clear()
        selectedTasks.addAll(tasks.map { it.id })
        notifyDataSetChanged()
    }

    fun getSelectedTasks(): List<DownloadTask> {
        return tasks.filter { selectedTasks.contains(it.id) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(tasks[position])
    }

    override fun getItemCount(): Int = tasks.size

    inner class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val flIndex: FrameLayout = itemView.findViewById(R.id.flIndex)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val layoutProgress: LinearLayout = itemView.findViewById(R.id.layoutProgress)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val tvProgress: TextView = itemView.findViewById(R.id.tvProgress)
        private val tvSpeed: TextView = itemView.findViewById(R.id.tvSpeed)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val btnControl: ImageButton = itemView.findViewById(R.id.btnControl)

        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        private var currentTask: DownloadTask? = null

        init {
            btnControl.setOnClickListener {
                currentTask?.let { task ->
                    onControlClick(task)
                }
            }

            itemView.setOnClickListener {
                currentTask?.let { task ->
                    onItemClick(task)
                }
            }

            itemView.setOnLongClickListener {
                currentTask?.let { task ->
                    onLongClick(task)
                } ?: false
            }
        }

        fun bind(task: DownloadTask) {
            currentTask = task

            // 绑定歌曲信息
            tvSongName.text = task.songName
            tvArtist.text = task.artist

            // 加载封面 - 处理酷我等平台的相对路径
            loadCoverImage(task)

            // 多选模式处理
            if (isMultiSelectMode) {
                flIndex.visibility = View.VISIBLE
                ivCheckbox.visibility = View.VISIBLE
                ivCheckbox.isSelected = selectedTasks.contains(task.id)
                btnControl.visibility = View.GONE
                updateSelectedBackground(selectedTasks.contains(task.id))
            } else {
                flIndex.visibility = View.GONE
                ivCheckbox.visibility = View.GONE
                btnControl.visibility = View.VISIBLE
                itemView.setBackgroundResource(android.R.color.transparent)
            }

            if (isHistory) {
                // 历史记录布局
                layoutProgress.visibility = View.GONE
                tvSize.visibility = View.VISIBLE
                tvStatus.visibility = View.VISIBLE
                tvSize.text = task.getFormattedSize()
                tvStatus.text = dateFormat.format(Date(task.completeTime))
                btnControl.setImageResource(R.drawable.ic_play)
            } else {
                // 正在下载布局
                layoutProgress.visibility = View.VISIBLE
                tvSize.visibility = View.GONE
                tvStatus.visibility = View.GONE
                progressBar.progress = task.progress
                tvProgress.text = "${task.progress}%"

                val speed = speeds[task.id] ?: 0
                tvSpeed.text = if (task.isDownloading && speed > 0) {
                    formatSpeed(speed)
                } else {
                    getStatusText(task.status)
                }

                // 控制按钮图标
                val iconRes = when {
                    task.isPaused || task.isFailed -> R.drawable.ic_play
                    task.isDownloading -> R.drawable.ic_pause
                    else -> R.drawable.ic_pause
                }
                btnControl.setImageResource(iconRes)
            }
        }

        private fun loadCoverImage(task: DownloadTask) {
            val context = itemView.context
            val coverUrl = task.coverUrl

            when {
                coverUrl.isNullOrEmpty() -> {
                    // 没有封面URL，尝试从网络获取
                    ivCover.setImageResource(R.drawable.ic_music_note)
                    downloadAndCacheCover(task)
                }
                coverUrl.startsWith("http") -> {
                    // 网络图片，使用 Glide 加载
                    Glide.with(context)
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
                            Glide.with(context)
                                .load(file)
                                .placeholder(R.drawable.ic_music_note)
                                .error(R.drawable.ic_music_note)
                                .into(ivCover)
                        } else {
                            // 本地文件不存在，尝试重新下载
                            ivCover.setImageResource(R.drawable.ic_music_note)
                            downloadAndCacheCover(task)
                        }
                    } catch (e: Exception) {
                        ivCover.setImageResource(R.drawable.ic_music_note)
                        downloadAndCacheCover(task)
                    }
                }
                else -> {
                    // 相对路径（如酷我音乐的封面URL），需要解析为完整URL
                    ivCover.setImageResource(R.drawable.ic_music_note)
                    resolveAndLoadCover(task)
                }
            }
        }

        private fun resolveAndLoadCover(task: DownloadTask) {
            // 首先检查是否有已解析的URL缓存
            val cachedResolvedUrl = resolvedCoverUrls[task.songId]
            if (cachedResolvedUrl != null) {
                Glide.with(itemView.context)
                    .load(cachedResolvedUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(ivCover)
                return
            }

            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val resolvedUrl = withContext(Dispatchers.IO) {
                        CoverUrlResolver.resolveCoverUrl(
                            context,
                            task.coverUrl,
                            task.songId,
                            task.platform
                        )
                    }

                    if (resolvedUrl != null) {
                        // 缓存解析后的URL
                        resolvedCoverUrls[task.songId] = resolvedUrl

                        // 使用解析后的URL加载封面
                        Glide.with(context)
                            .load(resolvedUrl)
                            .placeholder(R.drawable.ic_music_note)
                            .error(R.drawable.ic_music_note)
                            .into(ivCover)
                    } else {
                        // 解析失败，尝试下载缓存
                        downloadAndCacheCover(task)
                    }
                } catch (e: Exception) {
                    // 解析失败，尝试下载缓存
                    downloadAndCacheCover(task)
                }
            }
        }

        private fun downloadAndCacheCover(task: DownloadTask) {
            lifecycleScope?.launch {
                try {
                    val context = itemView.context
                    val localPath = CoverImageManager.downloadAndCacheCover(
                        context,
                        task.songId,
                        task.platform
                    )

                    if (localPath != null) {
                        // 下载成功，更新UI
                        Glide.with(context)
                            .load(File(localPath))
                            .placeholder(R.drawable.ic_music_note)
                            .error(R.drawable.ic_music_note)
                            .into(ivCover)
                    }
                } catch (e: Exception) {
                    // 下载失败，保持默认图标
                }
            }
        }

        private fun updateSelectedBackground(isSelected: Boolean) {
            if (isSelected) {
                itemView.setBackgroundColor(itemView.context.getColor(R.color.light_blue_cyan))
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
            }
        }

        private fun getStatusText(status: DownloadStatus): String {
            return when (status) {
                DownloadStatus.PENDING -> "等待中"
                DownloadStatus.DOWNLOADING -> "下载中"
                DownloadStatus.PAUSED -> "已暂停"
                DownloadStatus.COMPLETED -> "已完成"
                DownloadStatus.FAILED -> "下载失败"
            }
        }

        private fun formatSpeed(bytesPerSecond: Long): String {
            return when {
                bytesPerSecond < 1024 -> "${bytesPerSecond}B/s"
                bytesPerSecond < 1024 * 1024 -> "${bytesPerSecond / 1024}KB/s"
                else -> "${bytesPerSecond / (1024 * 1024)}MB/s"
            }
        }
    }
}
