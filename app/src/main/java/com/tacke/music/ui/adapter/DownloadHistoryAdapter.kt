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
            contentLayout.setOnClickListener {
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
