package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.DownloadTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadHistoryAdapter(
    private val onDeleteClick: (DownloadTask, Boolean) -> Unit,
    private val onItemClick: (DownloadTask) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {},
    private val onEnterMultiSelectMode: (DownloadTask) -> Unit = {}
) : RecyclerView.Adapter<DownloadHistoryAdapter.HistoryViewHolder>() {

    private var tasks: List<DownloadTask> = emptyList()
    private val selectedTasks = mutableSetOf<String>()
    private var isMultiSelectMode = false

    fun submitList(newTasks: List<DownloadTask>) {
        tasks = newTasks
        notifyDataSetChanged()
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(tasks[position])
    }

    override fun getItemCount(): Int = tasks.size

    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
        private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        private val contentLayout: androidx.constraintlayout.widget.ConstraintLayout = itemView.findViewById(R.id.contentLayout)
        private val deleteLayout: androidx.constraintlayout.widget.ConstraintLayout = itemView.findViewById(R.id.deleteLayout)

        private var currentTask: DownloadTask? = null
        private var isSwipeOpen = false
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        init {
            deleteLayout.setOnClickListener {
                showDeleteMenu()
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

            setupSwipeAndClick()
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

        private fun setupSwipeAndClick() {
            var startX = 0f
            var startY = 0f
            var currentX = 0f
            var currentY = 0f
            var isDragging = false
            val deleteWidth = itemView.context.resources.getDimensionPixelSize(R.dimen.delete_button_width)
            val touchSlop = android.view.ViewConfiguration.get(itemView.context).scaledTouchSlop

            contentLayout.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        currentX = startX
                        currentY = startY
                        isDragging = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        currentX = event.rawX
                        currentY = event.rawY
                        val deltaX = currentX - startX
                        val deltaY = currentY - startY

                        // 判断是否开始滑动
                        if (!isDragging && (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop)) {
                            isDragging = true
                        }

                        // 水平滑动处理
                        if (isDragging && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) && deltaX < 0 && !isMultiSelectMode) {
                            val translation = maxOf(deltaX, -deleteWidth.toFloat())
                            contentLayout.translationX = translation
                            true
                        } else {
                            false
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val deltaX = currentX - startX
                        val deltaY = currentY - startY
                        val absDeltaX = kotlin.math.abs(deltaX)
                        val absDeltaY = kotlin.math.abs(deltaY)

                        if (!isDragging || (absDeltaX <= touchSlop && absDeltaY <= touchSlop)) {
                            // 点击事件处理
                            if (isMultiSelectMode) {
                                currentTask?.let { task ->
                                    toggleSelection(task.id)
                                }
                            } else if (isSwipeOpen) {
                                closeSwipe()
                            } else {
                                currentTask?.let { task ->
                                    onItemClick(task)
                                }
                            }
                        } else if (absDeltaX > absDeltaY && deltaX < 0) {
                            // 滑动结束处理
                            if (deltaX < -deleteWidth / 2) {
                                openSwipe()
                            } else {
                                closeSwipe()
                            }
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            closeSwipe()
                        }
                        true
                    }
                    else -> false
                }
            }

            // 长按进入多选模式
            contentLayout.setOnLongClickListener {
                if (!isMultiSelectMode && !isSwipeOpen) {
                    currentTask?.let { task ->
                        onEnterMultiSelectMode(task)
                    }
                    true
                } else {
                    false
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
            tvSize.text = task.getFormattedSize()
            tvDate.text = dateFormat.format(Date(task.completeTime))

            Glide.with(itemView.context)
                .load(task.coverUrl)
                .placeholder(R.drawable.ic_album_default)
                .error(R.drawable.ic_album_default)
                .centerCrop()
                .into(ivCover)

            // 多选模式显示复选框
            cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            cbSelect.isChecked = selectedTasks.contains(task.id)

            if (isSwipeOpen && isMultiSelectMode) {
                closeSwipe()
            }
        }
    }
}
