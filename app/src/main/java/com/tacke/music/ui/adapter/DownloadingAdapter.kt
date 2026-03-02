package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.DownloadStatus
import com.tacke.music.data.model.DownloadTask

class DownloadingAdapter(
    private val onPauseClick: (DownloadTask) -> Unit,
    private val onResumeClick: (DownloadTask) -> Unit,
    private val onDeleteClick: (DownloadTask, Boolean) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {},
    private val onEnterMultiSelectMode: (DownloadTask) -> Unit = {}
) : RecyclerView.Adapter<DownloadingAdapter.DownloadingViewHolder>() {

    private var tasks: List<DownloadTask> = emptyList()
    private var speeds: Map<String, Long> = emptyMap()
    private val selectedTasks = mutableSetOf<String>()
    private var isMultiSelectMode = false

    fun submitList(newTasks: List<DownloadTask>) {
        tasks = newTasks
        notifyDataSetChanged()
    }

    fun updateSpeeds(newSpeeds: Map<String, Long>) {
        speeds = newSpeeds
        tasks.forEachIndexed { index, task ->
            if (speeds.containsKey(task.id)) {
                notifyItemChanged(index, PAYLOAD_SPEED)
            }
        }
    }

    fun isMultiSelectMode(): Boolean = isMultiSelectMode

    fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedTasks.clear()
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedTasks.size)
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
        onSelectionChanged(selectedTasks.size)
    }

    fun selectAll() {
        selectedTasks.clear()
        selectedTasks.addAll(tasks.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedTasks.size)
    }

    fun clearSelection() {
        selectedTasks.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun getSelectedTasks(): List<DownloadTask> {
        return tasks.filter { selectedTasks.contains(it.id) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_downloading, parent, false)
        return DownloadingViewHolder(view)
    }

    override fun onBindViewHolder(holder: DownloadingViewHolder, position: Int) {
        holder.bind(tasks[position])
    }

    override fun onBindViewHolder(
        holder: DownloadingViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_SPEED)) {
            holder.updateSpeed(tasks[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = tasks.size

    inner class DownloadingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val tvProgress: TextView = itemView.findViewById(R.id.tvProgress)
        private val tvSpeed: TextView = itemView.findViewById(R.id.tvSpeed)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val btnControl: ImageButton = itemView.findViewById(R.id.btnControl)
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        private val contentLayout: androidx.constraintlayout.widget.ConstraintLayout = itemView.findViewById(R.id.contentLayout)
        private val deleteLayout: androidx.constraintlayout.widget.ConstraintLayout = itemView.findViewById(R.id.deleteLayout)

        private var currentTask: DownloadTask? = null
        private var isSwipeOpen = false

        init {
            btnControl.setOnClickListener {
                currentTask?.let { task ->
                    when (task.status) {
                        DownloadStatus.DOWNLOADING -> onPauseClick(task)
                        DownloadStatus.PAUSED, DownloadStatus.FAILED -> onResumeClick(task)
                        else -> {}
                    }
                }
            }

            deleteLayout.setOnClickListener {
                showDeleteMenu()
            }

            contentLayout.setOnClickListener {
                if (isMultiSelectMode) {
                    currentTask?.let { task ->
                        toggleSelection(task.id)
                    }
                } else if (isSwipeOpen) {
                    closeSwipe()
                }
            }

            contentLayout.setOnLongClickListener {
                if (!isMultiSelectMode) {
                    currentTask?.let { task ->
                        onEnterMultiSelectMode(task)
                    }
                    true
                } else {
                    false
                }
            }

            cbSelect.setOnCheckedChangeListener { _, isChecked ->
                currentTask?.let { task ->
                    if (isChecked && !selectedTasks.contains(task.id)) {
                        selectedTasks.add(task.id)
                        onSelectionChanged(selectedTasks.size)
                    } else if (!isChecked && selectedTasks.contains(task.id)) {
                        selectedTasks.remove(task.id)
                        onSelectionChanged(selectedTasks.size)
                    }
                }
            }

            setupSwipe()
        }

        private fun showDeleteMenu() {
            currentTask?.let { task ->
                val popupMenu = PopupMenu(itemView.context, deleteLayout)
                popupMenu.menuInflater.inflate(R.menu.menu_delete_options, popupMenu.menu)
                popupMenu.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_delete_record -> {
                            onDeleteClick(task, false)
                            closeSwipe()
                            true
                        }
                        R.id.action_delete_file_and_record -> {
                            onDeleteClick(task, true)
                            closeSwipe()
                            true
                        }
                        else -> false
                    }
                }
                popupMenu.show()
            }
        }

        private fun setupSwipe() {
            var startX = 0f
            var currentX = 0f
            val deleteWidth = itemView.context.resources.getDimensionPixelSize(R.dimen.delete_button_width)

            contentLayout.setOnTouchListener { _, event ->
                if (isMultiSelectMode) return@setOnTouchListener false

                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        currentX = event.rawX
                        val deltaX = currentX - startX
                        if (deltaX < 0) {
                            val translation = maxOf(deltaX, -deleteWidth.toFloat())
                            contentLayout.translationX = translation
                            true
                        } else {
                            false
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val deltaX = currentX - startX
                        if (deltaX < -deleteWidth / 2) {
                            openSwipe()
                        } else {
                            closeSwipe()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        private fun openSwipe() {
            val deleteWidth = itemView.context.resources.getDimensionPixelSize(R.dimen.delete_button_width)
            contentLayout.animate()
                .translationX(-deleteWidth.toFloat())
                .setDuration(200)
                .start()
            isSwipeOpen = true
        }

        private fun closeSwipe() {
            contentLayout.animate()
                .translationX(0f)
                .setDuration(200)
                .start()
            isSwipeOpen = false
        }

        fun bind(task: DownloadTask) {
            currentTask = task

            tvSongName.text = task.songName
            tvArtist.text = task.artist
            progressBar.progress = task.progress
            tvProgress.text = "${task.progress}%"
            tvSize.text = "${task.getFormattedDownloadedSize()} / ${task.getFormattedSize()}"

            updateSpeed(task)
            updateControlButton(task.status)

            Glide.with(itemView.context)
                .load(task.coverUrl)
                .placeholder(R.drawable.ic_album_default)
                .error(R.drawable.ic_album_default)
                .centerCrop()
                .into(ivCover)

            // 多选模式显示复选框
            cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            cbSelect.isChecked = selectedTasks.contains(task.id)

            // 控制按钮在多选模式下隐藏
            btnControl.visibility = if (isMultiSelectMode) View.GONE else View.VISIBLE

            if (isSwipeOpen && isMultiSelectMode) {
                closeSwipe()
            }
        }

        fun updateSpeed(task: DownloadTask) {
            val speed = speeds[task.id] ?: 0
            tvSpeed.text = if (task.isDownloading && speed > 0) {
                formatSpeed(speed)
            } else {
                getStatusText(task.status)
            }
        }

        private fun updateControlButton(status: DownloadStatus) {
            val iconRes = when (status) {
                DownloadStatus.DOWNLOADING -> R.drawable.ic_pause
                DownloadStatus.PAUSED -> R.drawable.ic_play
                DownloadStatus.FAILED -> R.drawable.ic_play
                else -> R.drawable.ic_pause
            }
            btnControl.setImageResource(iconRes)
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

    companion object {
        private const val PAYLOAD_SPEED = "speed"
    }
}
