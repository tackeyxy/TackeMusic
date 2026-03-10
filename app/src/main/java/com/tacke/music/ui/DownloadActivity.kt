package com.tacke.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.model.DownloadTask
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityDownloadBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.ui.adapter.DownloadTaskAdapter
import kotlinx.coroutines.launch

class DownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var playbackManager: PlaybackManager
    private lateinit var downloadingAdapter: DownloadTaskAdapter
    private lateinit var historyAdapter: DownloadTaskAdapter
    private var isMultiSelectMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16: 适配 Edge-to-Edge 模式
        setupEdgeToEdge()

        downloadManager = DownloadManager.getInstance(this)
        favoriteRepository = FavoriteRepository(this)
        playlistRepository = PlaylistRepository(this)
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
        // 正在下载适配器
        downloadingAdapter = DownloadTaskAdapter(
            isHistory = false,
            onControlClick = { task ->
                when {
                    task.isPaused || task.isFailed -> downloadManager.resumeDownload(task)
                    task.isDownloading -> downloadManager.pauseDownload(task.id)
                    else -> {}
                }
            },
            onItemClick = { task ->
                if (isMultiSelectMode) {
                    downloadingAdapter.toggleSelection(task.id)
                    updateSelectedCount(downloadingAdapter.getSelectedTasks().size)
                }
            },
            onLongClick = { task ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    downloadingAdapter.toggleSelection(task.id)
                    updateSelectedCount(1)
                    true
                } else {
                    false
                }
            }
        )

        // 下载历史适配器
        historyAdapter = DownloadTaskAdapter(
            isHistory = true,
            onControlClick = { task ->
                playDownloadedSong(task)
            },
            onItemClick = { task ->
                if (isMultiSelectMode) {
                    historyAdapter.toggleSelection(task.id)
                    updateSelectedCount(historyAdapter.getSelectedTasks().size)
                } else {
                    playDownloadedSong(task)
                }
            },
            onLongClick = { task ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    historyAdapter.toggleSelection(task.id)
                    updateSelectedCount(1)
                    true
                } else {
                    false
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = downloadingAdapter

        showDownloadingTab()
    }

    private fun setupBatchActions() {
        setupBatchActionListeners()
    }

    private fun setupBatchActionListeners() {
        // 关闭按钮
        binding.batchActionBarContainer.btnCloseBatch?.setOnClickListener {
            exitMultiSelectMode()
        }

        // 全选按钮
        binding.batchActionBarContainer.btnSelectAll.setOnClickListener {
            if (binding.tabLayout.selectedTabPosition == 0) {
                downloadingAdapter.selectAll()
                updateSelectedCount(downloadingAdapter.getSelectedTasks().size)
            } else {
                historyAdapter.selectAll()
                updateSelectedCount(historyAdapter.getSelectedTasks().size)
            }
        }

        // 删除按钮 - 两个标签页都使用删除功能
        binding.batchActionBarContainer.btnBatchDownload.setOnClickListener {
            showBatchDeleteDialog { deleteFile ->
                if (binding.tabLayout.selectedTabPosition == 0) {
                    val selectedTasks = downloadingAdapter.getSelectedTasks()
                    if (selectedTasks.isNotEmpty()) {
                        downloadManager.deleteDownloads(selectedTasks, deleteFile)
                        Toast.makeText(this, "已删除 ${selectedTasks.size} 个任务", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val selectedTasks = historyAdapter.getSelectedTasks()
                    if (selectedTasks.isNotEmpty()) {
                        downloadManager.deleteDownloads(selectedTasks, deleteFile)
                        Toast.makeText(this, "已删除 ${selectedTasks.size} 个记录", Toast.LENGTH_SHORT).show()
                    }
                }
                exitMultiSelectMode()
            }
        }

        // 添加到喜欢按钮 - 仅在下载历史标签页有效
        binding.batchActionBarContainer.btnAddToFavorite.setOnClickListener {
            if (binding.tabLayout.selectedTabPosition == 1) {
                val selectedTasks = historyAdapter.getSelectedTasks()
                if (selectedTasks.isNotEmpty()) {
                    addTasksToFavorites(selectedTasks)
                }
                exitMultiSelectMode()
            } else {
                Toast.makeText(this, "此操作仅在下载历史页面可用", Toast.LENGTH_SHORT).show()
            }
        }

        // 添加到播放按钮 - 仅在下载历史标签页有效
        binding.batchActionBarContainer.btnAddToNowPlaying.setOnClickListener {
            if (binding.tabLayout.selectedTabPosition == 1) {
                val selectedTasks = historyAdapter.getSelectedTasks()
                if (selectedTasks.isNotEmpty()) {
                    addTasksToNowPlaying(selectedTasks)
                }
                exitMultiSelectMode()
            } else {
                Toast.makeText(this, "此操作仅在下载历史页面可用", Toast.LENGTH_SHORT).show()
            }
        }

        // 添加到歌单按钮 - 仅在下载历史标签页有效
        binding.batchActionBarContainer.btnAddToPlaylist.setOnClickListener {
            if (binding.tabLayout.selectedTabPosition == 1) {
                val selectedTasks = historyAdapter.getSelectedTasks()
                if (selectedTasks.isNotEmpty()) {
                    showBatchPlaylistSelectionDialog(selectedTasks)
                }
                exitMultiSelectMode()
            } else {
                Toast.makeText(this, "此操作仅在下载历史页面可用", Toast.LENGTH_SHORT).show()
            }
        }

        // 清空列表按钮
        binding.batchActionBarContainer.btnClearAll.setOnClickListener {
            showClearAllConfirmDialog()
        }
    }

    private fun showClearAllConfirmDialog() {
        val isDownloadingTab = binding.tabLayout.selectedTabPosition == 0
        val title = if (isDownloadingTab) "清空正在下载" else "清空下载历史"
        val message = if (isDownloadingTab) {
            "确定要清空所有正在下载的任务吗？"
        } else {
            "确定要清空所有下载历史记录吗？"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("清空") { _, _ ->
                if (isDownloadingTab) {
                    clearAllDownloading()
                } else {
                    clearAllHistory()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAllDownloading() {
        val allTasks = downloadManager.downloadingTasks.value
        if (allTasks.isEmpty()) {
            Toast.makeText(this, "没有正在下载的任务", Toast.LENGTH_SHORT).show()
            exitMultiSelectMode()
            return
        }
        downloadManager.deleteDownloads(allTasks, deleteFile = false)
        Toast.makeText(this, "已清空所有下载任务", Toast.LENGTH_SHORT).show()
        exitMultiSelectMode()
    }

    private fun clearAllHistory() {
        val allTasks = downloadManager.completedTasks.value
        if (allTasks.isEmpty()) {
            Toast.makeText(this, "没有下载历史记录", Toast.LENGTH_SHORT).show()
            exitMultiSelectMode()
            return
        }
        showBatchDeleteDialog { deleteFile ->
            downloadManager.deleteDownloads(allTasks, deleteFile)
            Toast.makeText(this, "已清空所有下载历史", Toast.LENGTH_SHORT).show()
            exitMultiSelectMode()
        }
    }

    private fun showBatchPlaylistSelectionDialog(tasks: List<DownloadTask>) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@DownloadActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(tasks)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@DownloadActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addTasksToPlaylist(playlists[which].id, tasks)
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(tasks)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(tasks: List<DownloadTask>) {
        val editText = android.widget.EditText(this).apply {
            hint = "歌单名称"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("新建歌单")
            .setView(editText)
            .setPositiveButton("创建") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val playlist = playlistRepository.createPlaylist(name)
                        addTasksToPlaylist(playlist.id, tasks)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addTasksToPlaylist(playlistId: String, tasks: List<DownloadTask>) {
        lifecycleScope.launch {
            try {
                val songList = tasks.map { task ->
                    Song(
                        index = 0,
                        id = task.songId,
                        name = task.songName,
                        artists = task.artist,
                        coverUrl = task.coverUrl
                    )
                }

                playlistRepository.addSongsToPlaylist(playlistId, songList, tasks.firstOrNull()?.platform ?: "kuwo")

                Toast.makeText(
                    this@DownloadActivity,
                    "已添加 ${tasks.size} 首歌曲到歌单",
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DownloadActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
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
        binding.batchActionBarContainer.root.visibility = View.VISIBLE

        // 根据当前标签页设置适配器
        if (binding.tabLayout.selectedTabPosition == 0) {
            downloadingAdapter.setMultiSelectMode(true)
            // 正在下载页面显示下载按钮（用于删除任务）
            binding.batchActionBarContainer.btnBatchDownload.visibility = View.VISIBLE
        } else {
            historyAdapter.setMultiSelectMode(true)
            // 下载历史页面隐藏下载按钮（已下载的文件不需要再下载）
            binding.batchActionBarContainer.btnBatchDownload.visibility = View.GONE
        }

        updateSelectedCount(0)
        setupBatchActionListeners()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        binding.batchActionBarContainer.root.visibility = View.GONE

        downloadingAdapter.setMultiSelectMode(false)
        historyAdapter.setMultiSelectMode(false)

        // 恢复下载按钮的可见性
        binding.batchActionBarContainer.btnBatchDownload.visibility = View.VISIBLE
    }

    private fun updateSelectedCount(count: Int) {
        binding.batchActionBarContainer.tvSelectedCount.text = count.toString()
    }

    private fun observeDownloadData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    downloadManager.downloadingTasks.collect { tasks ->
                        downloadingAdapter.submitList(tasks)
                        if (binding.tabLayout.selectedTabPosition == 0) {
                            updateEmptyState(tasks.isEmpty())
                        }
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
                            updateEmptyState(tasks.isEmpty())
                        }
                    }
                }
            }
        }
    }

    private fun showDownloadingTab() {
        binding.recyclerView.adapter = downloadingAdapter
        val tasks = downloadManager.downloadingTasks.value
        updateEmptyState(tasks.isEmpty())
    }

    private fun showHistoryTab() {
        binding.recyclerView.adapter = historyAdapter
        val tasks = downloadManager.completedTasks.value
        updateEmptyState(tasks.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty && !isMultiSelectMode) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            binding.tvEmpty.text = if (binding.tabLayout.selectedTabPosition == 0) "暂无下载任务" else "暂无下载记录"
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
                val detail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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

    private fun addTasksToFavorites(tasks: List<DownloadTask>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var alreadyFavoriteCount = 0

                tasks.forEach { task ->
                    val isFavorite = favoriteRepository.isFavorite(task.songId)
                    if (!isFavorite) {
                        val song = Song(
                            index = 0,
                            id = task.songId,
                            name = task.songName,
                            artists = task.artist,
                            coverUrl = task.coverUrl
                        )
                        val platform = playbackManager.getValidPlatform(task.platform).name
                        favoriteRepository.addToFavorites(song, platform)
                        addedCount++
                    } else {
                        alreadyFavoriteCount++
                    }
                }

                val message = when {
                    alreadyFavoriteCount > 0 -> "已添加 $addedCount 首，$alreadyFavoriteCount 首已在喜欢列表"
                    else -> "已添加 $addedCount 首歌曲到我喜欢"
                }
                Toast.makeText(this@DownloadActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@DownloadActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleFavoriteFromHistory(task: DownloadTask, isCurrentlyFavorite: Boolean) {
        lifecycleScope.launch {
            try {
                if (isCurrentlyFavorite) {
                    favoriteRepository.removeFromFavorites(task.songId)
                    Toast.makeText(this@DownloadActivity, "已从我喜欢移除", Toast.LENGTH_SHORT).show()
                } else {
                    val song = Song(
                        index = 0,
                        id = task.songId,
                        name = task.songName,
                        artists = task.artist,
                        coverUrl = task.coverUrl
                    )
                    val platform = playbackManager.getValidPlatform(task.platform).name
                    favoriteRepository.addToFavorites(song, platform)
                    Toast.makeText(this@DownloadActivity, "已添加到我喜欢", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@DownloadActivity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        fun start(context: android.content.Context) {
            context.startActivity(Intent(context, DownloadActivity::class.java))
        }
    }

    /**
     * Android 16: 设置 Edge-to-Edge 模式
     * 处理系统栏（状态栏和导航栏）的 insets
     * 注意：布局中已添加 fitsSystemWindows="true"，这里处理额外的 insets 需求
     */
    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 只为底部设置 padding，顶部由 fitsSystemWindows 处理
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }
}
