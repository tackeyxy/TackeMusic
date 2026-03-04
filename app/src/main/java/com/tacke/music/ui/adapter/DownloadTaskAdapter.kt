package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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

        private val flIndex: FrameLayout = itemView.findViewById(R.id.flIndex)
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)
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

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                currentTask?.let { task ->
                    if (isChecked && !selectedTasks.contains(task.id)) {
                        selectedTasks.add(task.id)
                    } else if (!isChecked && selectedTasks.contains(task.id)) {
                        selectedTasks.remove(task.id)
                    }
                    updateSelectedBackground(isChecked)
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
                checkBox.visibility = View.VISIBLE
                checkBox.isChecked = selectedTasks.contains(task.id)
                btnControl.visibility = View.GONE
                updateSelectedBackground(selectedTasks.contains(task.id))
            } else {
                flIndex.visibility = View.GONE
                checkBox.visibility = View.GONE
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
