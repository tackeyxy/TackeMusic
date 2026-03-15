package com.tacke.music.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.RecentPlay
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.data.repository.RecentPlayRepository
import com.tacke.music.databinding.ActivityRecentPlayBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.RecentPlayAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecentPlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecentPlayBinding
    private lateinit var recentPlayRepository: RecentPlayRepository
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var playbackManager: PlaybackManager
    private lateinit var playlistManager: PlaylistManager
    private lateinit var downloadManager: DownloadManager
    private lateinit var adapter: RecentPlayAdapter

    private var isMultiSelectMode = false
    private val selectedItems = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecentPlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16: 适配 Edge-to-Edge 模式
        setupEdgeToEdge()

        recentPlayRepository = RecentPlayRepository(this)
        favoriteRepository = FavoriteRepository(this)
        playlistRepository = PlaylistRepository(this)
        playbackManager = PlaybackManager.getInstance(this)
        playlistManager = PlaylistManager.getInstance(this)
        downloadManager = DownloadManager.getInstance(this)

        setupRecyclerView()
        setupClickListeners()
        observeRecentPlays()
    }

    private fun setupRecyclerView() {
        adapter = RecentPlayAdapter(
            onItemClick = { recentPlay ->
                if (isMultiSelectMode) {
                    toggleSelection(recentPlay.id)
                } else {
                    playSong(recentPlay)
                }
            },
            onLongClick = { recentPlay ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    toggleSelection(recentPlay.id)
                    true
                } else {
                    false
                }
            },
            lifecycleScope = lifecycleScope,
            onCoverLoaded = { id, coverPath ->
                // 封面加载完成后的回调
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            if (isMultiSelectMode) {
                exitMultiSelectMode()
            } else {
                finish()
            }
        }

        binding.btnMore.setOnClickListener {
            showMoreOptions()
        }

        binding.btnPlayAll.setOnClickListener {
            playAllSongs()
        }

        setupBatchActionListeners()
    }

    private fun setupBatchActionListeners() {
        // 关闭按钮
        binding.batchActionBarContainer.btnCloseBatch?.setOnClickListener {
            exitMultiSelectMode()
        }

        // 全选按钮
        binding.batchActionBarContainer.btnSelectAll.setOnClickListener {
            if (selectedItems.size == adapter.itemCount) {
                selectedItems.clear()
            } else {
                selectedItems.clear()
                adapter.getAllRecentPlays().forEach { selectedItems.add(it.id) }
            }
            adapter.setSelectedItems(selectedItems)
            updateSelectedCount()
        }

        // 添加到喜欢按钮
        binding.batchActionBarContainer.btnAddToFavorite.setOnClickListener {
            val selectedSongs = adapter.getAllRecentPlays().filter { selectedItems.contains(it.id) }
            if (selectedSongs.isNotEmpty()) {
                addSongsToFavorites(selectedSongs)
            }
            exitMultiSelectMode()
        }

        // 添加到歌单按钮
        binding.batchActionBarContainer.btnAddToPlaylist.setOnClickListener {
            val selectedSongs = adapter.getAllRecentPlays().filter { selectedItems.contains(it.id) }
            if (selectedSongs.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchPlaylistSelectionDialog(selectedSongs)
        }

        // 添加到播放按钮
        binding.batchActionBarContainer.btnAddToNowPlaying.setOnClickListener {
            val selectedSongs = adapter.getAllRecentPlays().filter { selectedItems.contains(it.id) }
            if (selectedSongs.isNotEmpty()) {
                addSongsToNowPlaying(selectedSongs)
            }
            exitMultiSelectMode()
        }

        // 下载按钮 - 真正的下载功能
        binding.batchActionBarContainer.btnBatchDownload.setOnClickListener {
            val selectedSongs = adapter.getAllRecentPlays().filter { selectedItems.contains(it.id) }
            if (selectedSongs.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchDownloadQualityDialog(selectedSongs)
        }

        // 移除按钮 - 删除播放记录
        binding.batchActionBarContainer.btnBatchRemove.setOnClickListener {
            showDeleteConfirm()
        }

        // 清空列表按钮
        binding.batchActionBarContainer.btnClearAll.setOnClickListener {
            showClearAllConfirmDialog()
        }
    }

    private fun showClearAllConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空播放记录")
            .setMessage("确定要清空所有播放记录吗？")
            .setPositiveButton("清空") { _, _ ->
                clearAllRecentPlays()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAllRecentPlays() {
        lifecycleScope.launch {
            try {
                val allRecords = adapter.getAllRecentPlays()
                if (allRecords.isEmpty()) {
                    Toast.makeText(this@RecentPlayActivity, "没有播放记录", Toast.LENGTH_SHORT).show()
                    exitMultiSelectMode()
                    return@launch
                }
                recentPlayRepository.clearAll()
                Toast.makeText(this@RecentPlayActivity, "已清空所有播放记录", Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeRecentPlays() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                recentPlayRepository.getRecentPlays().collect { recentPlays ->
                    adapter.submitList(recentPlays)
                    updateEmptyState(recentPlays.isEmpty())
                    updateRecordCount(recentPlays.size)

                    if (isMultiSelectMode) {
                        val validSelections = selectedItems.intersect(recentPlays.map { it.id }.toSet())
                        if (validSelections.size != selectedItems.size) {
                            selectedItems.clear()
                            selectedItems.addAll(validSelections)
                            adapter.setSelectedItems(selectedItems)
                            updateSelectedCount()
                        }
                    }
                }
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateRecordCount(count: Int) {
        binding.tvSongListTitle.text = "播放记录（共${count}首）"
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        binding.batchActionBarContainer.root.visibility = View.VISIBLE
        binding.btnPlayAll.visibility = View.GONE
        adapter.setMultiSelectMode(true)
        selectedItems.clear()
        updateSelectedCount()
        // 显示"移除"按钮
        binding.batchActionBarContainer.btnBatchRemove.visibility = View.VISIBLE
        setupBatchActionListeners()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        binding.batchActionBarContainer.root.visibility = View.GONE
        binding.btnPlayAll.visibility = View.VISIBLE
        adapter.setMultiSelectMode(false)
        selectedItems.clear()
        // 隐藏"移除"按钮
        binding.batchActionBarContainer.btnBatchRemove.visibility = View.GONE
    }

    private fun toggleSelection(id: String) {
        if (selectedItems.contains(id)) {
            selectedItems.remove(id)
        } else {
            selectedItems.add(id)
        }
        adapter.setSelectedItems(selectedItems)
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        binding.batchActionBarContainer.tvSelectedCount.text = selectedItems.size.toString()
    }

    private fun showMoreOptions() {
        val options = arrayOf("清空所有记录")

        MaterialAlertDialogBuilder(this)
            .setTitle("更多选项")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showClearAllDialog()
                }
            }
            .show()
    }

    private fun showDeleteConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除记录")
            .setMessage("确定要删除选中的 ${selectedItems.size} 条播放记录吗？")
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedRecords()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedRecords() {
        lifecycleScope.launch {
            try {
                selectedItems.forEach { id ->
                    recentPlayRepository.deleteById(id)
                }
                exitMultiSelectMode()
                Toast.makeText(this@RecentPlayActivity, "已删除 ${selectedItems.size} 条记录", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteSingleRecord(id: String) {
        lifecycleScope.launch {
            try {
                recentPlayRepository.deleteById(id)
                Toast.makeText(this@RecentPlayActivity, "已删除记录", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showClearAllDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空记录")
            .setMessage("确定要清空所有播放记录吗？")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    recentPlayRepository.clearAll()
                    Toast.makeText(this@RecentPlayActivity, "已清空播放记录", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun playSong(recentPlay: RecentPlay) {
        lifecycleScope.launch {
            try {
                // 使用 playFromRecentPlay 方法，自动验证平台并获取歌曲详情
                val success = playbackManager.playFromRecentPlay(this@RecentPlayActivity, recentPlay)
                if (!success) {
                    Toast.makeText(this@RecentPlayActivity, "获取歌曲信息失败，可能音源不可用", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAllSongs() {
        val recentPlays = adapter.getAllRecentPlays()
        if (recentPlays.isEmpty()) {
            Toast.makeText(this, "暂无播放记录", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                // 添加所有歌曲到播放列表（不清空原有列表）
                recentPlays.forEach { recentPlay ->
                    playlistManager.addSong(recentPlay.toPlaylistSong())
                }

                // 播放第一首 - 使用 PlaylistSong 方式播放，避免 playFromRecentPlay 清空列表
                val firstPlay = recentPlays.first()
                val platform = try {
                    MusicRepository.Platform.valueOf(firstPlay.platform.uppercase())
                } catch (e: Exception) {
                    MusicRepository.Platform.KUWO
                }

                // 获取用户设置的试听音质
                val playbackQuality = SettingsActivity.getPlaybackQuality(this@RecentPlayActivity)

                // 非下载管理页面，强制重新获取最新URL，但封面和歌词使用缓存
                val cachedRepository = CachedMusicRepository(this@RecentPlayActivity)
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongUrlWithCache(
                        platform = platform,
                        songId = firstPlay.id,
                        quality = playbackQuality,
                        songName = firstPlay.name,
                        artists = firstPlay.artists,
                        useCache = true,
                        coverUrlFromSearch = firstPlay.coverUrl
                    )
                }

                if (detail != null) {
                    // 使用获取到的封面，如果没有则尝试使用数据库中的封面
                    val finalCoverUrl = detail.cover ?: firstPlay.coverUrl

                    val playlistSong = firstPlay.toPlaylistSong().copy(
                        coverUrl = finalCoverUrl ?: ""
                    )

                    playbackManager.playFromPlaylist(
                        this@RecentPlayActivity,
                        playlistSong,
                        detail.url,
                        detail
                    )
                    Toast.makeText(this@RecentPlayActivity, "开始播放全部", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@RecentPlayActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongsToFavorites(recentPlays: List<RecentPlay>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                recentPlays.forEach { recentPlay ->
                    val song = Song(
                        index = 0,
                        id = recentPlay.id,
                        name = recentPlay.name,
                        artists = recentPlay.artists,
                        coverUrl = recentPlay.coverUrl
                    )
                    val isAlreadyFavorite = favoriteRepository.isFavorite(recentPlay.id)
                    if (!isAlreadyFavorite) {
                        favoriteRepository.addToFavorites(song, recentPlay.platform)
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }
                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首到喜欢，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到我喜欢"
                }
                Toast.makeText(this@RecentPlayActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongsToNowPlaying(recentPlays: List<RecentPlay>) {
        // 立即退出多选模式，提升用户体验
        exitMultiSelectMode()

        lifecycleScope.launch {
            try {
                // 转换为播放列表歌曲
                val playlistSongs = recentPlays.map { it.toPlaylistSong() }

                // 使用批量添加方法，不触发自动播放，不预获取URL
                // 新歌曲追加到列表末尾，不影响当前播放状态
                val result = playbackManager.addPlaylistSongsWithoutPlay(playlistSongs)
                val addedCount = result.first
                val duplicateCount = result.second

                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到正在播放列表"
                }
                Toast.makeText(this@RecentPlayActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showBatchPlaylistSelectionDialog(recentPlays: List<RecentPlay>) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@RecentPlayActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(recentPlays)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@RecentPlayActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addSongsToPlaylist(playlists[which].id, recentPlays)
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(recentPlays)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(recentPlays: List<RecentPlay>) {
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
                        addSongsToPlaylist(playlist.id, recentPlays)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addSongsToPlaylist(playlistId: String, recentPlays: List<RecentPlay>) {
        lifecycleScope.launch {
            try {
                val songList = recentPlays.map { recentPlay ->
                    Song(
                        index = 0,
                        id = recentPlay.id,
                        name = recentPlay.name,
                        artists = recentPlay.artists,
                        coverUrl = recentPlay.coverUrl
                    )
                }

                playlistRepository.addSongsToPlaylist(playlistId, songList, recentPlays.firstOrNull()?.platform ?: "kuwo")

                Toast.makeText(
                    this@RecentPlayActivity,
                    "已添加 ${recentPlays.size} 首歌曲到歌单",
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(
                    this@RecentPlayActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showBatchDownloadQualityDialog(recentPlays: List<RecentPlay>) {
        val qualities = arrayOf("HR (24bit/96kHz)", "CDQ (16bit/44.1kHz)", "HQ (320kbps)", "LQ (128kbps)")
        val qualityValues = arrayOf("flac24bit", "flac", "320k", "128k")

        MaterialAlertDialogBuilder(this)
            .setTitle("批量下载")
            .setItems(qualities) { _, which ->
                batchDownloadSongs(recentPlays, qualityValues[which])
            }
            .show()
    }

    private fun batchDownloadSongs(recentPlays: List<RecentPlay>, quality: String) {
        val context = applicationContext
        val cachedRepository = CachedMusicRepository(context)
        val dm = DownloadManager.getInstance(context)

        // 立即显示添加下载任务提示
        Toast.makeText(
            this@RecentPlayActivity,
            "已添加 ${recentPlays.size} 首歌曲到下载队列",
            Toast.LENGTH_SHORT
        ).show()

        // 使用 GlobalScope 确保 Activity 销毁后任务继续执行
        GlobalScope.launch(Dispatchers.IO) {
            recentPlays.forEach { recentPlay ->
                launch {
                    try {
                        val platform = try {
                            MusicRepository.Platform.valueOf(recentPlay.platform.uppercase())
                        } catch (e: Exception) {
                            MusicRepository.Platform.KUWO
                        }
                        val detail = cachedRepository.getSongUrlWithCache(
                            platform = platform,
                            songId = recentPlay.id,
                            quality = quality,
                            songName = recentPlay.name,
                            artists = recentPlay.artists,
                            useCache = true
                        )
                        if (detail != null) {
                            val task = dm.createDownloadTask(
                                Song(
                                    index = 0,
                                    id = recentPlay.id,
                                    name = recentPlay.name,
                                    artists = recentPlay.artists,
                                    coverUrl = detail.cover ?: recentPlay.coverUrl
                                ),
                                detail,
                                quality,
                                recentPlay.platform
                            )
                            dm.startDownload(task)
                        }
                    } catch (e: Exception) {
                        // 忽略异常，继续处理下一首
                    }
                }
            }
        }

        exitMultiSelectMode()
    }

    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
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

            // 为状态栏占位视图设置高度
            binding.statusBarPlaceholder.layoutParams.height = insets.top
            binding.statusBarPlaceholder.requestLayout()

            // 为底部设置 padding
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }
}
