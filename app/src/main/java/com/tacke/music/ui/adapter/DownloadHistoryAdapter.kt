package com.tacke.music.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
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

class DownloadHistoryAdapter(
    private val onDeleteClick: (DownloadTask, Boolean) -> Unit,
    private val onItemClick: (DownloadTask) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {},
    private val onEnterMultiSelectMode: (DownloadTask) -> Unit = {},
    private val lifecycleScope: LifecycleCoroutineScope? = null
) : RecyclerView.Adapter<DownloadHistoryAdapter.HistoryViewHolder>() {

    private var tasks: List<DownloadTask> = emptyList()
    private val selectedTasks = mutableSetOf<String>()
    private var isMultiSelectMode = false
    private val resolvedCoverUrls = mutableMapOf<String, String>()

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
        private val flCheckbox: View = itemView.findViewById(R.id.flCheckbox)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
        private val contentLayout: androidx.constraintlayout.widget.ConstraintLayout = itemView.findViewById(R.id.contentLayout)
        private val deleteLayout: androidx.constraintlayout.widget.ConstraintLayout = itemView.findViewById(R.id.deleteLayout)

        private var currentTask: DownloadTask? = null
        private var isSwipeOpen = false
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        init {
            deleteLayout.setOnClickListener {
                showDeleteMenu()
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
                        // 长按时自动选中当前任务
                        toggleSelection(task.id)
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

            // 加载封面
            loadCoverImage(task)

            // 多选模式显示复选框
            flCheckbox.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            ivCheckbox.isSelected = selectedTasks.contains(task.id)

            if (isSwipeOpen && isMultiSelectMode) {
                closeSwipe()
            }
        }

        private fun loadCoverImage(task: DownloadTask) {
            val context = itemView.context
            val coverUrl = task.coverUrl

            when {
                coverUrl.isNullOrEmpty() -> {
                    // 没有封面URL，尝试从网络获取
                    ivCover.setImageResource(R.drawable.ic_album_default)
                    downloadAndCacheCover(task)
                }
                coverUrl.startsWith("http") -> {
                    // 网络图片，使用 Glide 加载
                    // 添加错误处理，如果加载失败尝试其他方式
                    Glide.with(context)
                        .load(coverUrl)
                        .placeholder(R.drawable.ic_album_default)
                        .error(R.drawable.ic_album_default)
                        .centerCrop()
                        .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                            override fun onLoadFailed(
                                e: com.bumptech.glide.load.engine.GlideException?,
                                model: Any?,
                                target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                // 如果网络图片加载失败，尝试解析或下载封面
                                if (task.platform.equals("kuwo", ignoreCase = true)) {
                                    resolveAndLoadCover(task)
                                } else {
                                    downloadAndCacheCover(task)
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
                            Glide.with(context)
                                .load(file)
                                .placeholder(R.drawable.ic_album_default)
                                .error(R.drawable.ic_album_default)
                                .centerCrop()
                                .into(ivCover)
                        } else {
                            // 本地文件不存在，尝试重新下载
                            ivCover.setImageResource(R.drawable.ic_album_default)
                            downloadAndCacheCover(task)
                        }
                    } catch (e: Exception) {
                        ivCover.setImageResource(R.drawable.ic_album_default)
                        downloadAndCacheCover(task)
                    }
                }
                else -> {
                    // 相对路径（如酷我音乐的封面URL），需要解析为完整URL
                    ivCover.setImageResource(R.drawable.ic_album_default)
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
                    .placeholder(R.drawable.ic_album_default)
                    .error(R.drawable.ic_album_default)
                    .centerCrop()
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
                            task.platform,
                            task.songName,
                            task.artist
                        )
                    }

                    if (resolvedUrl != null) {
                        // 缓存解析后的URL
                        resolvedCoverUrls[task.songId] = resolvedUrl

                        // 使用解析后的URL加载封面
                        Glide.with(context)
                            .load(resolvedUrl)
                            .placeholder(R.drawable.ic_album_default)
                            .error(R.drawable.ic_album_default)
                            .centerCrop()
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
                        task.platform,
                        "320k",
                        task.songName,
                        task.artist
                    )

                    if (localPath != null) {
                        // 下载成功，更新UI
                        Glide.with(context)
                            .load(File(localPath))
                            .placeholder(R.drawable.ic_album_default)
                            .error(R.drawable.ic_album_default)
                            .centerCrop()
                            .into(ivCover)
                    }
                } catch (e: Exception) {
                    // 下载失败，保持默认图标
                }
            }
        }
    }
}
