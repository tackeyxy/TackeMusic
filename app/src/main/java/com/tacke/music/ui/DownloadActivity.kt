package com.tacke.music.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
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
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.ListCoverRepairManager
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityDownloadBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.DownloadTaskAdapter
import com.tacke.music.util.NavigationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var playbackManager: PlaybackManager
    private lateinit var listCoverRepairManager: ListCoverRepairManager
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
        listCoverRepairManager = ListCoverRepairManager.getInstance(this)

        setupToolbar()
        setupTabLayout()
        setupRecyclerViews()
        setupBatchActions()
        setupSideNavigation()
        observeDownloadData()
    }

    override fun onResume() {
        super.onResume()
        listCoverRepairManager.repairDownloadingListAsync()
        listCoverRepairManager.repairDownloadHistoryAsync()
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
            },
            lifecycleScope = lifecycleScope
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
            },
            lifecycleScope = lifecycleScope
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
        findViewById<View>(R.id.btnCloseBatch)?.setOnClickListener {
            exitMultiSelectMode()
        }

        // 全选按钮
        findViewById<View>(R.id.btnSelectAll)?.setOnClickListener {
            if (binding.tabLayout.selectedTabPosition == 0) {
                downloadingAdapter.selectAll()
                updateSelectedCount(downloadingAdapter.getSelectedTasks().size)
            } else {
                historyAdapter.selectAll()
                updateSelectedCount(historyAdapter.getSelectedTasks().size)
            }
        }

        // 删除按钮 - 两个标签页都使用删除功能
        findViewById<View>(R.id.btnBatchDownload)?.setOnClickListener {
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
        findViewById<View>(R.id.btnAddToFavorite)?.setOnClickListener {
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
        findViewById<View>(R.id.btnAddToNowPlaying)?.setOnClickListener {
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
        findViewById<View>(R.id.btnAddToPlaylist)?.setOnClickListener {
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
        findViewById<View>(R.id.btnClearAll)?.setOnClickListener {
            showClearAllConfirmDialog()
        }

        // 移除所选按钮 - 仅移除选中项，不删除文件
        findViewById<View>(R.id.btnBatchRemove)?.setOnClickListener {
            showBatchRemoveDialog()
        }
    }

    private fun showBatchRemoveDialog() {
        val isDownloadingTab = binding.tabLayout.selectedTabPosition == 0
        val selectedTasks = if (isDownloadingTab) {
            downloadingAdapter.getSelectedTasks()
        } else {
            historyAdapter.getSelectedTasks()
        }

        if (selectedTasks.isEmpty()) {
            Toast.makeText(this, "请先选择要移除的项目", Toast.LENGTH_SHORT).show()
            return
        }

        // 正在下载页面：直接移除，不删除文件
        if (isDownloadingTab) {
            val title = "移除下载任务"
            val message = "确定要移除选中的 ${selectedTasks.size} 个下载任务吗？\n（不会删除已下载的文件）"

            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("移除") { _, _ ->
                    downloadManager.deleteDownloads(selectedTasks, deleteFile = false)
                    Toast.makeText(this, "已移除 ${selectedTasks.size} 个任务", Toast.LENGTH_SHORT).show()
                    exitMultiSelectMode()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 下载历史页面：显示选项让用户选择操作方式
        val options = arrayOf("仅清除记录", "同时删除文件")
        var selectedOption = 0

        MaterialAlertDialogBuilder(this)
            .setTitle("移除下载记录 (${selectedTasks.size} 项)")
            .setSingleChoiceItems(options, selectedOption) { _, which ->
                selectedOption = which
            }
            .setPositiveButton("确定") { _, _ ->
                val deleteFile = selectedOption == 1
                downloadManager.deleteDownloads(selectedTasks, deleteFile)
                val message = if (deleteFile) {
                    "已删除 ${selectedTasks.size} 个记录及文件"
                } else {
                    "已清除 ${selectedTasks.size} 个记录"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            }
            .setNegativeButton("取消", null)
            .show()
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
        findViewById<View>(R.id.batchActionBar)?.visibility = View.VISIBLE

        // 根据当前标签页设置适配器
        if (binding.tabLayout.selectedTabPosition == 0) {
            downloadingAdapter.setMultiSelectMode(true)
            // 正在下载页面：隐藏下载按钮，显示移除按钮
            findViewById<View>(R.id.btnBatchDownload)?.visibility = View.GONE
            findViewById<View>(R.id.btnBatchRemove)?.visibility = View.VISIBLE
        } else {
            historyAdapter.setMultiSelectMode(true)
            // 下载历史页面：隐藏下载按钮，显示移除按钮
            findViewById<View>(R.id.btnBatchDownload)?.visibility = View.GONE
            findViewById<View>(R.id.btnBatchRemove)?.visibility = View.VISIBLE
        }

        updateSelectedCount(0)
        setupBatchActionListeners()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        findViewById<View>(R.id.batchActionBar)?.visibility = View.GONE

        downloadingAdapter.setMultiSelectMode(false)
        historyAdapter.setMultiSelectMode(false)

        // 恢复按钮的默认可见性
        findViewById<View>(R.id.btnBatchDownload)?.visibility = View.VISIBLE
        findViewById<View>(R.id.btnBatchRemove)?.visibility = View.GONE
    }

    private fun updateSelectedCount(count: Int) {
        findViewById<TextView>(R.id.tvSelectedCount)?.text = count.toString()
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
                // 优先使用任务保存的音质
                val quality = task.quality.takeIf { it.isNotBlank() }
                    ?: SettingsActivity.getPlaybackQuality(this@DownloadActivity)
                playbackManager.playFromDownloadWithPlatform(
                    context = this@DownloadActivity,
                    songId = task.songId,
                    songName = task.songName,
                    artist = task.artist,
                    coverUrl = task.coverUrl,
                    playUrl = task.filePath,
                    platform = task.platform,
                    quality = quality
                )
            } else {
                // 本地文件不存在，使用平台信息获取在线歌曲
                val platform = playbackManager.getValidPlatform(task.platform)

                // 使用带缓存的Repository获取歌曲详情（用于歌词和封面）
                // 优先使用任务保存的音质，如果没有则使用用户设置的听音质
                val quality = task.quality.takeIf { it.isNotBlank() }
                    ?: SettingsActivity.getPlaybackQuality(this@DownloadActivity)
                val cachedRepository = CachedMusicRepository(this@DownloadActivity)
                val detail = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cachedRepository.getSongDetail(
                        platform = platform,
                        songId = task.songId,
                        quality = quality,
                        songName = task.songName,
                        artists = task.artist,
                        coverUrlFromSearch = task.coverUrl
                    )
                }

                if (detail != null) {
                    // 优先使用任务保存的音质
                    val quality = task.quality.takeIf { it.isNotBlank() }
                        ?: SettingsActivity.getPlaybackQuality(this@DownloadActivity)
                    playbackManager.playFromDownload(
                        context = this@DownloadActivity,
                        songId = task.songId,
                        songName = task.songName,
                        artist = task.artist,
                        coverUrl = detail.cover ?: task.coverUrl,
                        playUrl = detail.url,
                        platform = platform,
                        songDetail = detail,
                        quality = quality
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
            val addedSongs = mutableListOf<Pair<PlaylistSong, MusicRepository.Platform>>()

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
                    addedSongs.add(Pair(playlistSong, validPlatform))
                    addedCount++
                } else {
                    duplicateCount++
                }
            }

            // 关键修复：预获取第一首新增歌曲的URL并缓存
            // 这样当用户进入播放页时，第一首歌的URL已经准备好了
            if (addedSongs.isNotEmpty()) {
                val (firstSong, firstPlatform) = addedSongs.first()
                val cachedRepository = CachedMusicRepository(this@DownloadActivity)
                // 获取用户设置的试听音质
                val playbackQuality = SettingsActivity.getPlaybackQuality(this@DownloadActivity)
                withContext(Dispatchers.IO) {
                    try {
                        Log.d("DownloadActivity", "预获取第一首歌曲URL: ${firstSong.name}")
                        cachedRepository.getSongUrlWithCache(
                            platform = firstPlatform,
                            songId = firstSong.id,
                            quality = playbackQuality,
                            songName = firstSong.name,
                            artists = firstSong.artists,
                            useCache = true,
                            coverUrlFromSearch = firstSong.coverUrl
                        )
                        Log.d("DownloadActivity", "预获取URL完成: ${firstSong.name}")
                    } catch (e: Exception) {
                        Log.e("DownloadActivity", "预获取URL失败: ${firstSong.name}, ${e.message}")
                    }
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
     * 为状态栏占位视图设置高度，防止内容被状态栏遮挡
     */
    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // 判断当前屏幕方向
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

            if (isLandscape) {
                // 横屏模式：状态栏在左侧，为侧边导航栏设置顶部 padding
                val sideNav = binding.root.findViewById<android.view.View>(R.id.sideNavContainer)
                sideNav?.findViewById<android.view.View>(R.id.sideNav)?.let { navView ->
                    navView.setPadding(navView.paddingLeft, insets.top, navView.paddingRight, navView.paddingBottom)
                }
            } else {
                // 竖屏模式：状态栏在顶部，为状态栏占位视图设置高度
                binding.statusBarPlaceholder?.layoutParams?.height = insets.top
                binding.statusBarPlaceholder?.requestLayout()
            }

            // 为底部设置 padding
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }

    /**
     * 设置左侧导航栏（横屏模式）
     */
    private fun setupSideNavigation() {
        // 检查是否存在左侧导航栏（横屏模式）
        val sideNavContainer = binding.root.findViewById<android.view.View>(R.id.sideNavContainer)
        if (sideNavContainer != null) {
            val navHelper = NavigationHelper(this)
            navHelper.setupSideNavigation(
                navHome = sideNavContainer.findViewById(R.id.navHome),
                navDiscover = sideNavContainer.findViewById(R.id.navDiscover),
                navProfile = sideNavContainer.findViewById(R.id.navProfile),
                navSettings = sideNavContainer.findViewById(R.id.navSettings),
                ivNavHome = sideNavContainer.findViewById(R.id.ivNavHome),
                ivNavDiscover = sideNavContainer.findViewById(R.id.ivNavDiscover),
                ivNavProfile = sideNavContainer.findViewById(R.id.ivNavProfile),
                tvNavHome = sideNavContainer.findViewById(R.id.tvNavHome),
                tvNavDiscover = sideNavContainer.findViewById(R.id.tvNavDiscover),
                tvNavProfile = sideNavContainer.findViewById(R.id.tvNavProfile),
                currentNavIndex = -1 // 下载管理页面不在主导航栏中
            )
        }
    }
}
