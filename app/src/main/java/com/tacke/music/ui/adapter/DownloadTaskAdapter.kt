package com.tacke.music.ui.adapter

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
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.DownloadStatus
import com.tacke.music.data.model.DownloadTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadTaskAdapter(
    private val isHistory: Boolean,
    private val onControlClick: (DownloadTask) -> Unit,
    private val onItemClick: (DownloadTask) -> Unit,
    private val onLongClick: (DownloadTask) -> Boolean
) : RecyclerView.Adapter<DownloadTaskAdapter.TaskViewHolder>() {

    private var tasks: List<DownloadTask> = emptyList()
    private var speeds: Map<String, Long> = emptyMap()
    private val selectedTasks = mutableSetOf<String>()
    private var isMultiSelectMode = false

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

        private val contentContainer: FrameLayout = itemView.findViewById(R.id.contentContainer)

        // 内容视图中的控件
        private lateinit var flIndex: FrameLayout
        private lateinit var ivCheckbox: ImageView
        private lateinit var ivCover: ImageView
        private lateinit var tvSongName: TextView
        private lateinit var tvArtist: TextView
        private lateinit var layoutProgress: LinearLayout
        private lateinit var progressBar: ProgressBar
        private lateinit var tvProgress: TextView
        private lateinit var tvSpeed: TextView
        private lateinit var tvSize: TextView
        private lateinit var tvStatus: TextView
        private lateinit var btnControl: ImageButton

        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        private var currentTask: DownloadTask? = null
        private var contentView: View? = null

        init {
            // 动态添加内容布局到内容容器
            if (contentContainer.childCount == 0) {
                contentView = LayoutInflater.from(itemView.context)
                    .inflate(R.layout.item_download_task_content, contentContainer, true)
                initContentViews()
            }

            itemView.setOnClickListener {
                currentTask?.let { task ->
                    if (isMultiSelectMode) {
                        toggleSelection(task.id)
                    } else {
                        onItemClick(task)
                    }
                }
            }

            itemView.setOnLongClickListener {
                currentTask?.let { task ->
                    onLongClick(task)
                } ?: false
            }
        }

        private fun initContentViews() {
            contentView?.let { view ->
                flIndex = view.findViewById(R.id.flIndex)
                ivCheckbox = view.findViewById(R.id.ivCheckbox)
                ivCover = view.findViewById(R.id.ivCover)
                tvSongName = view.findViewById(R.id.tvSongName)
                tvArtist = view.findViewById(R.id.tvArtist)
                layoutProgress = view.findViewById(R.id.layoutProgress)
                progressBar = view.findViewById(R.id.progressBar)
                tvProgress = view.findViewById(R.id.tvProgress)
                tvSpeed = view.findViewById(R.id.tvSpeed)
                tvSize = view.findViewById(R.id.tvSize)
                tvStatus = view.findViewById(R.id.tvStatus)
                btnControl = view.findViewById(R.id.btnControl)

                btnControl.setOnClickListener {
                    currentTask?.let { task ->
                        onControlClick(task)
                    }
                }
            }
        }

        fun bind(task: DownloadTask) {
            currentTask = task

            // 绑定歌曲信息
            tvSongName.text = task.songName
            tvArtist.text = task.artist

            // 加载封面
            Glide.with(itemView.context)
                .load(task.coverUrl)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .into(ivCover)

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
                contentView?.setBackgroundResource(android.R.color.transparent)
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

        private fun updateSelectedBackground(isSelected: Boolean) {
            if (isSelected) {
                contentView?.setBackgroundColor(itemView.context.getColor(R.color.light_blue_cyan))
            } else {
                contentView?.setBackgroundResource(android.R.color.transparent)
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
