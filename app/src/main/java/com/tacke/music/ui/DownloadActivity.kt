package com.tacke.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.tacke.music.R
import com.tacke.music.data.model.DownloadTask
import com.tacke.music.databinding.ActivityDownloadBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.ui.adapter.DownloadHistoryAdapter
import com.tacke.music.ui.adapter.DownloadingAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var playbackManager: PlaybackManager
    private lateinit var downloadingAdapter: DownloadingAdapter
    private lateinit var historyAdapter: DownloadHistoryAdapter
    private var isMultiSelectMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloadManager = DownloadManager.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)

        setupToolbar()
        setupTabLayout()
        setupRecyclerViews()
        setupBatchActions()
        observeDownloadData()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            if (isMultiSelectMode) {
                exitMultiSelectMode()
            } else {
                finish()
            }
        }

        // 列表顶部的批量操作入口按钮
        binding.btnEnterBatchMode.setOnClickListener {
            enterMultiSelectMode()
        }
    }

    private fun setupTabLayout() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("正在下载"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("下载历史"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showDownloadingTab()
                    1 -> showHistoryTab()
                }
                if (isMultiSelectMode) {
                    exitMultiSelectMode()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerViews() {
        downloadingAdapter = DownloadingAdapter(
            onPauseClick = { task ->
                downloadManager.pauseDownload(task.id)
            },
            onResumeClick = { task ->
                downloadManager.resumeDownload(task)
            },
            onDeleteClick = { task, deleteFile ->
                downloadManager.deleteDownload(task, deleteFile)
            },
            onSelectionChanged = { count ->
                updateSelectedCount(count)
            },
            onEnterMultiSelectMode = { task ->
                enterMultiSelectMode()
                // 自动选中长按的项
                downloadingAdapter.toggleSelection(task.id)
            }
        )

        historyAdapter = DownloadHistoryAdapter(
            onDeleteClick = { task, deleteFile ->
                downloadManager.deleteDownload(task, deleteFile)
            },
            onItemClick = { task ->
                playDownloadedSong(task)
            },
            onSelectionChanged = { count ->
                updateSelectedCount(count)
            },
            onEnterMultiSelectMode = { task ->
                enterMultiSelectMode()
                // 自动选中长按的项
                historyAdapter.toggleSelection(task.id)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = downloadingAdapter

        showDownloadingTab()
    }

    private fun setupBatchActions() {
        // 关闭批量操作
        binding.btnCloseBatchBottom.setOnClickListener {
            exitMultiSelectMode()
        }

        // 正在下载标签页的全选按钮
        binding.btnSelectAllBottom.setOnClickListener {
            downloadingAdapter.selectAll()
        }

        // 正在下载标签页的批量操作
        binding.btnBatchStartBottom.setOnClickListener {
            val selectedTasks = downloadingAdapter.getSelectedTasks()
            val tasksToResume = selectedTasks.filter { it.isPaused || it.isFailed }
            if (tasksToResume.isNotEmpty()) {
                downloadManager.resumeDownloads(tasksToResume)
                Toast.makeText(this, "已开始 ${tasksToResume.size} 个任务", Toast.LENGTH_SHORT).show()
            }
            exitMultiSelectMode()
        }

        binding.btnBatchPauseBottom.setOnClickListener {
            val selectedTasks = downloadingAdapter.getSelectedTasks()
            val taskIds = selectedTasks.map { it.id }
            if (taskIds.isNotEmpty()) {
                downloadManager.pauseDownloads(taskIds)
                Toast.makeText(this, "已暂停 ${taskIds.size} 个任务", Toast.LENGTH_SHORT).show()
            }
            exitMultiSelectMode()
        }

        binding.btnBatchDeleteBottom.setOnClickListener {
            showBatchDeleteDialog { deleteFile ->
                val selectedTasks = downloadingAdapter.getSelectedTasks()
                if (selectedTasks.isNotEmpty()) {
                    downloadManager.deleteDownloads(selectedTasks, deleteFile)
                    Toast.makeText(this, "已删除 ${selectedTasks.size} 个任务", Toast.LENGTH_SHORT).show()
                }
                exitMultiSelectMode()
            }
        }

        // 下载历史标签页的全选按钮
        binding.btnSelectAllHistoryBottom.setOnClickListener {
            historyAdapter.selectAll()
        }

        // 下载历史标签页的批量添加到正在播放
        binding.btnBatchAddToNowPlayingHistoryBottom.setOnClickListener {
            val selectedTasks = historyAdapter.getSelectedTasks()
            if (selectedTasks.isNotEmpty()) {
                addTasksToNowPlaying(selectedTasks)
            }
            exitMultiSelectMode()
        }

        // 下载历史标签页的批量操作
        binding.btnBatchDeleteHistoryBottom.setOnClickListener {
            showBatchDeleteDialog { deleteFile ->
                val selectedTasks = historyAdapter.getSelectedTasks()
                if (selectedTasks.isNotEmpty()) {
                    downloadManager.deleteDownloads(selectedTasks, deleteFile)
                    Toast.makeText(this, "已删除 ${selectedTasks.size} 个记录", Toast.LENGTH_SHORT).show()
                }
                exitMultiSelectMode()
            }
        }
    }

    private fun showBatchDeleteDialog(onConfirm: (Boolean) -> Unit) {
        val options = arrayOf("仅删除记录", "删除文件及记录")
        var selectedOption = 0

        AlertDialog.Builder(this)
            .setTitle("批量删除")
            .setSingleChoiceItems(options, selectedOption) { _, which ->
                selectedOption = which
            }
            .setPositiveButton("确定") { _, _ ->
                onConfirm(selectedOption == 1)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        binding.bottomActionBar.visibility = View.VISIBLE
        binding.listHeader.visibility = View.GONE
        binding.btnEnterBatchMode.visibility = View.GONE

        // 根据当前标签页显示对应的操作按钮
        if (binding.tabLayout.selectedTabPosition == 0) {
            binding.layoutDownloadingActions.visibility = View.VISIBLE
            binding.layoutHistoryActions.visibility = View.GONE
            downloadingAdapter.setMultiSelectMode(true)
        } else {
            binding.layoutDownloadingActions.visibility = View.GONE
            binding.layoutHistoryActions.visibility = View.VISIBLE
            historyAdapter.setMultiSelectMode(true)
        }

        updateSelectedCount(0)
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        binding.bottomActionBar.visibility = View.GONE
        binding.listHeader.visibility = View.VISIBLE
        binding.btnEnterBatchMode.visibility = View.VISIBLE

        downloadingAdapter.setMultiSelectMode(false)
        historyAdapter.setMultiSelectMode(false)
    }

    private fun updateSelectedCount(count: Int) {
        binding.tvSelectedCountBottom.text = "已选择 $count 项"
    }

    private fun observeDownloadData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    downloadManager.downloadingTasks.collect { tasks ->
                        downloadingAdapter.submitList(tasks)
                        updateEmptyState(tasks.isEmpty(), true)
                    }
                }

                launch {
                    downloadManager.downloadSpeeds.collect { speeds ->
                        downloadingAdapter.updateSpeeds(speeds)
                    }
                }

                launch {
                    downloadManager.completedTasks.collect { tasks ->
                        historyAdapter.submitList(tasks)
                        if (binding.tabLayout.selectedTabPosition == 1) {
                            updateEmptyState(tasks.isEmpty(), false)
                        }
                    }
                }
            }
        }
    }

    private fun showDownloadingTab() {
        binding.recyclerView.adapter = downloadingAdapter
        val tasks = downloadManager.downloadingTasks.value
        updateEmptyState(tasks.isEmpty(), true)

        if (isMultiSelectMode) {
            binding.layoutDownloadingActions.visibility = View.VISIBLE
            binding.layoutHistoryActions.visibility = View.GONE
        }
    }

    private fun showHistoryTab() {
        binding.recyclerView.adapter = historyAdapter
        val tasks = downloadManager.completedTasks.value
        updateEmptyState(tasks.isEmpty(), false)

        if (isMultiSelectMode) {
            binding.layoutDownloadingActions.visibility = View.GONE
            binding.layoutHistoryActions.visibility = View.VISIBLE
        }
    }

    private fun updateEmptyState(isEmpty: Boolean, isDownloadingTab: Boolean) {
        if (isEmpty && !isMultiSelectMode) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            binding.tvEmpty.text = if (isDownloadingTab) "暂无下载任务" else "暂无下载记录"
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyView.visibility = View.GONE
        }
    }

    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
        }
    }

    /**
     * 下载历史点击：添加到播放列表并播放
     */
    private fun playDownloadedSong(task: DownloadTask) {
        lifecycleScope.launch {
            // 检查本地文件是否存在
            val localFile = java.io.File(task.filePath)
            val hasLocalFile = localFile.exists() && localFile.length() > 0

            if (hasLocalFile) {
                // 本地文件存在，使用带平台信息的播放方法
                playbackManager.playFromDownloadWithPlatform(
                    context = this@DownloadActivity,
                    songId = task.songId,
                    songName = task.songName,
                    artist = task.artist,
                    coverUrl = task.coverUrl,
                    playUrl = task.filePath,
                    platform = task.platform
                )
            } else {
                // 本地文件不存在，使用平台信息获取在线歌曲
                val platform = playbackManager.getValidPlatform(task.platform)

                // 获取歌曲详情（用于歌词和封面）
                val detail = withContext(Dispatchers.IO) {
                    com.tacke.music.data.repository.MusicRepository().getSongDetail(platform, task.songId, "320k")
                }

                if (detail != null) {
                    playbackManager.playFromDownload(
                        context = this@DownloadActivity,
                        songId = task.songId,
                        songName = task.songName,
                        artist = task.artist,
                        coverUrl = task.coverUrl,
                        playUrl = detail.url,
                        platform = platform,
                        songDetail = detail
                    )
                } else {
                    Toast.makeText(this@DownloadActivity, "获取歌曲信息失败，可能音源不可用", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun addTasksToNowPlaying(tasks: List<DownloadTask>) {
        val playlistManager = com.tacke.music.playlist.PlaylistManager.getInstance(this)

        lifecycleScope.launch {
            var addedCount = 0
            var duplicateCount = 0

            tasks.forEach { task ->
                // 使用任务中保存的平台信息
                val validPlatform = playbackManager.getValidPlatform(task.platform)

                val playlistSong = com.tacke.music.data.model.PlaylistSong(
                    id = task.songId,
                    name = task.songName,
                    artists = task.artist,
                    coverUrl = task.coverUrl,
                    platform = validPlatform.name
                )

                val currentList = playlistManager.currentPlaylist.value
                if (currentList.none { it.id == playlistSong.id }) {
                    playlistManager.addSong(playlistSong)
                    addedCount++
                } else {
                    duplicateCount++
                }
            }

            val message = when {
                duplicateCount > 0 -> "已添加 $addedCount 首，$duplicateCount 首已存在"
                else -> "已添加 $addedCount 首歌曲到正在播放列表"
            }
            Toast.makeText(this@DownloadActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun start(context: android.content.Context) {
            context.startActivity(Intent(context, DownloadActivity::class.java))
        }
    }
}
