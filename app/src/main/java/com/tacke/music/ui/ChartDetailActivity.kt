package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.api.ChartType
import com.tacke.music.data.api.RetrofitClient
import com.tacke.music.data.model.ChartSong
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityChartDetailBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.ChartSongAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChartDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChartDetailBinding
    private lateinit var adapter: ChartSongAdapter
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playbackManager: PlaybackManager
    private var chartType: ChartType = ChartType.SOARING
    private var chartTitle: String = ""
    private var chartSubtitle: String = ""
    private var songs: List<ChartSong> = emptyList()
    private var isMultiSelectMode = false

    companion object {
        private const val EXTRA_CHART_TYPE = "chart_type"
        private const val EXTRA_CHART_TITLE = "chart_title"
        private const val EXTRA_CHART_SUBTITLE = "chart_subtitle"

        fun start(
            context: Context,
            chartType: ChartType,
            title: String,
            subtitle: String
        ) {
            val intent = Intent(context, ChartDetailActivity::class.java).apply {
                putExtra(EXTRA_CHART_TYPE, chartType.name)
                putExtra(EXTRA_CHART_TITLE, title)
                putExtra(EXTRA_CHART_SUBTITLE, subtitle)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChartDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16: 适配 Edge-to-Edge 模式
        setupEdgeToEdge()

        // 获取传递的参数
        val chartTypeName = intent.getStringExtra(EXTRA_CHART_TYPE) ?: ChartType.SOARING.name
        chartType = ChartType.valueOf(chartTypeName)
        chartTitle = intent.getStringExtra(EXTRA_CHART_TITLE) ?: ""
        chartSubtitle = intent.getStringExtra(EXTRA_CHART_SUBTITLE) ?: ""

        playlistRepository = PlaylistRepository(this)
        favoriteRepository = FavoriteRepository(this)
        playbackManager = PlaybackManager.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        setupBatchActionListeners()
        loadChartData()
    }

    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
        }
    }

    private fun setupToolbar() {
        // 返回按钮点击事件
        binding.btnBack.setOnClickListener {
            if (isMultiSelectMode) {
                exitMultiSelectMode()
            } else {
                finish()
            }
        }

        binding.tvChartTitle.text = chartTitle

        // 设置背景颜色
        val backgroundRes = when (chartType) {
            ChartType.SOARING -> R.drawable.bg_chart_soaring
            ChartType.NEW -> R.drawable.bg_chart_new
            ChartType.ORIGINAL -> R.drawable.bg_chart_original
            ChartType.HOT -> R.drawable.bg_chart_hot
        }
        binding.toolbarBackground.setBackgroundResource(backgroundRes)
        binding.toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun setupRecyclerView() {
        adapter = ChartSongAdapter(
            onItemClick = { song, rank ->
                if (isMultiSelectMode) {
                    updateBatchActionBar()
                } else {
                    // 榜单列表点击歌曲：添加到播放列表并播放
                    addToNowPlayingAndPlay(song)
                }
            },
            onLongClick = { song ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    true
                } else {
                    false
                }
            },
            lifecycleScope = lifecycleScope
        )
        binding.rvChartSongs.layoutManager = LinearLayoutManager(this)
        binding.rvChartSongs.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnPlayAll.setOnClickListener {
            if (!isMultiSelectMode) {
                playAllSongs()
            }
        }
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        adapter.setMultiSelectMode(true)
        showBatchActionBar()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        adapter.setMultiSelectMode(false)
        hideBatchActionBar()
    }

    private fun showBatchActionBar() {
        binding.batchActionBarContainer.root.visibility = View.VISIBLE
        binding.btnPlayAll.visibility = View.GONE
        // 隐藏清空按钮（榜单列表不需要清空功能）
        binding.batchActionBarContainer.btnClearAll.visibility = View.GONE
        updateBatchActionBar()
        setupBatchActionListeners()
    }

    private fun hideBatchActionBar() {
        binding.batchActionBarContainer.root.visibility = View.GONE
        binding.btnPlayAll.visibility = View.VISIBLE
    }

    private fun updateBatchActionBar() {
        val selectedCount = adapter.getSelectedSongs().size
        binding.batchActionBarContainer.tvSelectedCount.text = selectedCount.toString()
    }

    private fun setupBatchActionListeners() {
        // 关闭按钮
        binding.batchActionBarContainer.btnCloseBatch?.setOnClickListener {
            exitMultiSelectMode()
        }

        // 全选按钮
        binding.batchActionBarContainer.btnSelectAll.setOnClickListener {
            adapter.selectAll()
            updateBatchActionBar()
        }

        // 下载按钮
        binding.batchActionBarContainer.btnBatchDownload.setOnClickListener {
            val selectedSongs = adapter.getSelectedSongs()
            if (selectedSongs.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchDownloadQualityDialog(selectedSongs)
        }

        // 添加到喜欢按钮
        binding.batchActionBarContainer.btnAddToFavorite.setOnClickListener {
            val selectedSongs = adapter.getSelectedSongs()
            if (selectedSongs.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addSongsToFavorites(selectedSongs)
        }

        // 添加到歌单按钮
        binding.batchActionBarContainer.btnAddToPlaylist.setOnClickListener {
            val selectedSongs = adapter.getSelectedSongs()
            if (selectedSongs.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchPlaylistSelectionDialog(selectedSongs)
        }

        // 加入播放按钮
        binding.batchActionBarContainer.btnAddToNowPlaying.setOnClickListener {
            val selectedSongs = adapter.getSelectedSongs()
            if (selectedSongs.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addSongsToNowPlaying(selectedSongs)
        }
    }

    private fun loadChartData() {
        lifecycleScope.launch {
            showLoading(true)
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.chartApi.getChartList(chartType.value)
                }

                if (response.code == 200 && response.data != null) {
                    songs = response.data
                    adapter.submitList(songs)
                    showEmptyState(songs.isEmpty())
                } else {
                    showEmptyState(true)
                    Toast.makeText(this@ChartDetailActivity, response.msg ?: "加载失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                showEmptyState(true)
                Toast.makeText(this@ChartDetailActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
            }
        }
    }

    /**
     * 添加歌曲到播放列表并播放（榜单列表使用）
     * 非下载管理页面，需要重新请求URL进行播放
     */
    private fun addToNowPlayingAndPlay(song: ChartSong) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val platform = when (song.source.lowercase()) {
                    "kuwo" -> MusicRepository.Platform.KUWO
                    "netease" -> MusicRepository.Platform.NETEASE
                    else -> MusicRepository.Platform.KUWO
                }

                val cachedRepository = CachedMusicRepository(this@ChartDetailActivity)

                // 如果歌曲没有封面，尝试从网易云搜索获取封面
                var coverUrl = song.cover
                if (coverUrl.isNullOrEmpty()) {
                    coverUrl = withContext(Dispatchers.IO) {
                        cachedRepository.getCoverUrlFromNetease(song.name, song.artist)
                    }
                }

                // 获取用户设置的试听音质
                val playbackQuality = SettingsActivity.getPlaybackQuality(this@ChartDetailActivity)

                // 非下载管理页面，强制重新获取最新URL，但封面和歌词使用缓存
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongUrlWithCache(
                        platform = platform,
                        songId = song.id,
                        quality = playbackQuality,
                        songName = song.name,
                        artists = song.artist,
                        useCache = true,
                        coverUrlFromSearch = coverUrl
                    )
                }

                if (detail != null && detail.url.isNotEmpty()) {
                    // 先添加到播放列表
                    val playlistManager = PlaylistManager.getInstance(this@ChartDetailActivity)
                    val songModel = com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artist,
                        coverUrl = detail.cover ?: coverUrl ?: song.cover
                    )
                    val playlistSong = playlistManager.convertToPlaylistSong(songModel, platform)
                    playlistManager.addSong(playlistSong)

                    // 然后播放 - 使用 playFromPlaylist 确保能重新开始播放
                    playbackManager.playFromPlaylist(this@ChartDetailActivity, playlistSong, detail.url, detail)
                } else {
                    // 检查是否是API服务问题
                    Toast.makeText(
                        this@ChartDetailActivity,
                        "无法获取歌曲播放链接，音乐服务可能暂时不可用，请稍后重试",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("403") == true -> "音乐服务暂时不可用(403)，请稍后重试"
                    e.message?.contains("HTTP") == true -> "网络服务异常，请稍后重试"
                    else -> "播放失败: ${e.message}"
                }
                Toast.makeText(this@ChartDetailActivity, errorMessage, Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun playAllSongs() {
        if (songs.isEmpty()) return

        lifecycleScope.launch {
            val playlistManager = PlaylistManager.getInstance(this@ChartDetailActivity)
            var addedCount = 0

            songs.forEach { song ->
                try {
                    val platform = when (song.source.lowercase()) {
                        "kuwo" -> MusicRepository.Platform.KUWO
                        "netease" -> MusicRepository.Platform.NETEASE
                        else -> MusicRepository.Platform.KUWO
                    }
                    val playlistSong = playlistManager.convertToPlaylistSong(
                        com.tacke.music.data.model.Song(
                            index = 0,
                            id = song.id,
                            name = song.name,
                            artists = song.artist,
                            coverUrl = song.cover
                        ),
                        platform
                    )
                    playlistManager.addSong(playlistSong)
                    addedCount++
                } catch (e: Exception) {
                    // 忽略单个歌曲添加失败
                }
            }

            Toast.makeText(this@ChartDetailActivity, "已添加 $addedCount 首歌曲到播放列表", Toast.LENGTH_SHORT).show()

            // 播放第一首
            addToNowPlayingAndPlay(songs.first())
        }
    }

    private fun toggleFavorite(song: ChartSong, isCurrentlyFavorite: Boolean) {
        lifecycleScope.launch {
            try {
                val platform = when (song.source.lowercase()) {
                    "kuwo" -> "kuwo"
                    "netease" -> "netease"
                    else -> "kuwo"
                }

                if (isCurrentlyFavorite) {
                    favoriteRepository.removeFromFavorites(song.id)
                    Toast.makeText(this@ChartDetailActivity, "已从我喜欢移除", Toast.LENGTH_SHORT).show()
                } else {
                    val songModel = com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artist,
                        coverUrl = song.cover
                    )
                    favoriteRepository.addToFavorites(songModel, platform)
                    Toast.makeText(this@ChartDetailActivity, "已添加到我喜欢", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChartDetailActivity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addToNowPlaying(song: ChartSong) {
        lifecycleScope.launch {
            try {
                val platform = when (song.source.lowercase()) {
                    "kuwo" -> MusicRepository.Platform.KUWO
                    "netease" -> MusicRepository.Platform.NETEASE
                    else -> MusicRepository.Platform.KUWO
                }
                val playlistManager = PlaylistManager.getInstance(this@ChartDetailActivity)
                val playlistSong = playlistManager.convertToPlaylistSong(
                    com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artist,
                        coverUrl = song.cover
                    ),
                    platform
                )
                playlistManager.addSong(playlistSong)

                // 关键修复：预获取刚添加歌曲的URL并缓存
                // 这样当用户进入播放页时，歌曲的URL已经准备好了
                val cachedRepository = CachedMusicRepository(this@ChartDetailActivity)
                // 获取用户设置的试听音质
                val playbackQuality = SettingsActivity.getPlaybackQuality(this@ChartDetailActivity)
                withContext(Dispatchers.IO) {
                    try {
                        Log.d("ChartDetailActivity", "预获取单曲URL: ${playlistSong.name}")
                        cachedRepository.getSongUrlWithCache(
                            platform = platform,
                            songId = playlistSong.id,
                            quality = playbackQuality,
                            songName = playlistSong.name,
                            artists = playlistSong.artists,
                            useCache = true,
                            coverUrlFromSearch = playlistSong.coverUrl
                        )
                        Log.d("ChartDetailActivity", "预获取URL完成: ${playlistSong.name}")
                    } catch (e: Exception) {
                        Log.e("ChartDetailActivity", "预获取URL失败: ${playlistSong.name}, ${e.message}")
                    }
                }

                Toast.makeText(this@ChartDetailActivity, "已添加到正在播放列表", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ChartDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPlaylistSelectionDialog(song: ChartSong) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@ChartDetailActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(listOf(song))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@ChartDetailActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addSongsToPlaylist(playlists[which].id, listOf(song))
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(listOf(song))
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showBatchPlaylistSelectionDialog(selectedSongs: List<ChartSong>) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@ChartDetailActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(selectedSongs)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@ChartDetailActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addSongsToPlaylist(playlists[which].id, selectedSongs)
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(selectedSongs)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(selectedSongs: List<ChartSong>) {
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
                        addSongsToPlaylist(playlist.id, selectedSongs)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addSongsToPlaylist(playlistId: String, selectedSongs: List<ChartSong>) {
        lifecycleScope.launch {
            try {
                val songList = selectedSongs.map { song ->
                    val platform = when (song.source.lowercase()) {
                        "kuwo" -> "kuwo"
                        "netease" -> "netease"
                        else -> "kuwo"
                    }
                    com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artist,
                        coverUrl = song.cover
                    ) to platform
                }

                // 按平台分组添加
                val songsByPlatform = songList.groupBy { it.second }
                var totalAdded = 0

                songsByPlatform.forEach { (platform, songs) ->
                    val songModels = songs.map { it.first }
                    playlistRepository.addSongsToPlaylist(playlistId, songModels, platform)
                    totalAdded += songModels.size
                }

                Toast.makeText(
                    this@ChartDetailActivity,
                    "已添加 $totalAdded 首歌曲到歌单",
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ChartDetailActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showDownloadQualityDialog(song: ChartSong) {
        val qualities = arrayOf("HR (24bit/96kHz)", "CDQ (16bit/44.1kHz)", "HQ (320kbps)", "LQ (128kbps)")
        val qualityValues = arrayOf("flac24bit", "flac", "320k", "128k")

        MaterialAlertDialogBuilder(this)
            .setTitle("选择音质")
            .setItems(qualities) { _, which ->
                downloadSong(song, qualityValues[which])
            }
            .show()
    }

    private fun showBatchDownloadQualityDialog(selectedSongs: List<ChartSong>) {
        val qualities = arrayOf("HR (24bit/96kHz)", "CDQ (16bit/44.1kHz)", "HQ (320kbps)", "LQ (128kbps)")
        val qualityValues = arrayOf("flac24bit", "flac", "320k", "128k")

        MaterialAlertDialogBuilder(this)
            .setTitle("批量下载")
            .setItems(qualities) { _, which ->
                batchDownloadSongs(selectedSongs, qualityValues[which])
            }
            .show()
    }

    private fun downloadSong(song: ChartSong, quality: String) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val platform = when (song.source.lowercase()) {
                    "kuwo" -> MusicRepository.Platform.KUWO
                    "netease" -> MusicRepository.Platform.NETEASE
                    else -> MusicRepository.Platform.KUWO
                }
                // 非下载管理页面，强制重新获取最新URL，但封面和歌词使用缓存
                val cachedRepository = CachedMusicRepository(this@ChartDetailActivity)
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongUrlWithCache(
                        platform = platform,
                        songId = song.id,
                        quality = quality,
                        songName = song.name,
                        artists = song.artist,
                        useCache = true
                    )
                }
                if (detail != null) {
                    val platform = when (song.source.lowercase()) {
                        "kuwo" -> MusicRepository.Platform.KUWO
                        "netease" -> MusicRepository.Platform.NETEASE
                        else -> MusicRepository.Platform.KUWO
                    }
                    val songModel = com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artist,
                        coverUrl = detail.cover ?: song.cover
                    )
                    val downloadManager = DownloadManager.getInstance(this@ChartDetailActivity)
                    val task = downloadManager.createDownloadTask(songModel, detail, quality, platform.name)
                    downloadManager.startDownload(task)
                    Toast.makeText(this@ChartDetailActivity, "开始下载: ${task.fileName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ChartDetailActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChartDetailActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun batchDownloadSongs(selectedSongs: List<ChartSong>, quality: String) {
        val context = applicationContext
        val cachedRepository = CachedMusicRepository(context)
        val dm = DownloadManager.getInstance(context)

        // 立即显示添加下载任务提示
        Toast.makeText(
            this@ChartDetailActivity,
            "已添加 ${selectedSongs.size} 首歌曲到下载队列",
            Toast.LENGTH_SHORT
        ).show()

        // 使用 GlobalScope 确保 Activity 销毁后任务继续执行
        GlobalScope.launch(Dispatchers.IO) {
            selectedSongs.forEach { song ->
                launch {
                    try {
                        val platform = when (song.source.lowercase()) {
                            "kuwo" -> MusicRepository.Platform.KUWO
                            "netease" -> MusicRepository.Platform.NETEASE
                            else -> MusicRepository.Platform.KUWO
                        }
                        val detail = cachedRepository.getSongUrlWithCache(
                            platform = platform,
                            songId = song.id,
                            quality = quality,
                            songName = song.name,
                            artists = song.artist,
                            useCache = true
                        )
                        if (detail != null) {
                            val songModel = com.tacke.music.data.model.Song(
                                index = 0,
                                id = song.id,
                                name = song.name,
                                artists = song.artist,
                                coverUrl = detail.cover ?: song.cover
                            )
                            val task = dm.createDownloadTask(songModel, detail, quality, platform.name)
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

    private fun addSongsToFavorites(selectedSongs: List<ChartSong>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0

                selectedSongs.forEach { song ->
                    val platform = when (song.source.lowercase()) {
                        "kuwo" -> "kuwo"
                        "netease" -> "netease"
                        else -> "kuwo"
                    }
                    val songModel = com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artist,
                        coverUrl = song.cover
                    )
                    val isAlreadyFavorite = favoriteRepository.isFavorite(song.id)
                    if (!isAlreadyFavorite) {
                        favoriteRepository.addToFavorites(songModel, platform)
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }

                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首到喜欢，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到我喜欢"
                }
                Toast.makeText(this@ChartDetailActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ChartDetailActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun addSongsToNowPlaying(selectedSongs: List<ChartSong>) {
        // 立即退出多选模式，提升用户体验
        exitMultiSelectMode()

        lifecycleScope.launch {
            try {
                val playlistManager = PlaylistManager.getInstance(this@ChartDetailActivity)

                // 转换为播放列表歌曲
                val playlistSongs = selectedSongs.map { song ->
                    val platform = when (song.source.lowercase()) {
                        "kuwo" -> MusicRepository.Platform.KUWO
                        "netease" -> MusicRepository.Platform.NETEASE
                        else -> MusicRepository.Platform.KUWO
                    }
                    playlistManager.convertToPlaylistSong(
                        com.tacke.music.data.model.Song(
                            index = 0,
                            id = song.id,
                            name = song.name,
                            artists = song.artist,
                            coverUrl = song.cover
                        ),
                        platform
                    )
                }

                // 使用批量添加方法，不触发自动播放，不预获取URL
                // 新歌曲追加到列表末尾，不影响当前播放状态
                val result = playbackManager.addPlaylistSongsWithoutPlay(playlistSongs)
                val addedCount = result.first
                val duplicateCount = result.second

                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到正在播放列表"
                }
                Toast.makeText(this@ChartDetailActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ChartDetailActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showEmptyState(show: Boolean) {
        binding.emptyState.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvChartSongs.visibility = if (show) View.GONE else View.VISIBLE
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

            // 为工具栏设置顶部padding，使其延伸到状态栏下方
            binding.toolbar.setPadding(0, insets.top, 0, 0)

            // 为底部设置 padding
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }
}
