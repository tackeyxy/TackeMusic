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
import com.tacke.music.data.model.Playlist
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityPlaylistDetailBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.PlaylistSongAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playlistManager: PlaylistManager
    private lateinit var playbackManager: PlaybackManager
    private lateinit var downloadManager: DownloadManager
    private lateinit var songAdapter: PlaylistSongAdapter

    private var playlistId: String = ""
    private var playlistName: String = ""
    private var currentPlaylist: Playlist? = null
    private var isMultiSelectMode = false
    private val selectedSongs = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16: 适配 Edge-to-Edge 模式
        setupEdgeToEdge()

        playlistId = intent.getStringExtra("playlist_id") ?: ""
        playlistName = intent.getStringExtra("playlist_name") ?: ""

        if (playlistId.isEmpty()) {
            Toast.makeText(this, "歌单不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        playlistRepository = PlaylistRepository(this)
        favoriteRepository = FavoriteRepository(this)
        playlistManager = PlaylistManager.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)
        downloadManager = DownloadManager.getInstance(this)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        observeSongs()
    }

    private fun setupUI() {
        binding.tvTitle.text = playlistName
        loadPlaylistInfo()
    }

    private fun loadPlaylistInfo() {
        lifecycleScope.launch {
            try {
                currentPlaylist = playlistRepository.getPlaylistById(playlistId)
                currentPlaylist?.let { playlist ->
                    playlistName = playlist.name
                    binding.tvTitle.text = playlistName
                }
            } catch (e: Exception) {
                // 加载失败使用默认显示
            }
        }
    }

    private fun setupRecyclerView() {
        songAdapter = PlaylistSongAdapter(
            onItemClick = { song ->
                if (isMultiSelectMode) {
                    toggleSelection(song.id)
                } else {
                    playSong(song)
                }
            },
            onLongClick = { song ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    toggleSelection(song.id)
                    true
                } else {
                    false
                }
            },
            lifecycleScope = lifecycleScope,
            onCoverLoaded = { songId, coverPath ->
                // 封面下载完成后更新数据库
                lifecycleScope.launch {
                    playlistRepository.updateSongCover(playlistId, songId, coverPath)
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = songAdapter
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
            showPlaylistOptions()
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
            if (selectedSongs.size == songAdapter.itemCount) {
                selectedSongs.clear()
            } else {
                selectedSongs.clear()
                songAdapter.getAllSongs().forEach { selectedSongs.add(it.id) }
            }
            songAdapter.setSelectedItems(selectedSongs)
            updateSelectedCount()
        }

        // 添加到喜欢按钮
        binding.batchActionBarContainer.btnAddToFavorite.setOnClickListener {
            val selectedSongList = songAdapter.getAllSongs().filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isNotEmpty()) {
                addSongsToFavorites(selectedSongList)
            }
            exitMultiSelectMode()
        }

        // 添加到歌单按钮
        binding.batchActionBarContainer.btnAddToPlaylist.setOnClickListener {
            val selectedSongList = songAdapter.getAllSongs().filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchPlaylistSelectionDialog(selectedSongList)
        }

        // 添加到播放按钮
        binding.batchActionBarContainer.btnAddToNowPlaying.setOnClickListener {
            val selectedSongList = songAdapter.getAllSongs().filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isNotEmpty()) {
                addSongsToNowPlaying(selectedSongList)
            }
            exitMultiSelectMode()
        }

        // 下载按钮 - 真正的下载功能
        binding.batchActionBarContainer.btnBatchDownload.setOnClickListener {
            val selectedSongList = songAdapter.getAllSongs().filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchDownloadQualityDialog(selectedSongList)
        }

        // 移除按钮 - 从歌单中移除
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
            .setTitle("清空歌单")
            .setMessage("确定要清空歌单中的所有歌曲吗？")
            .setPositiveButton("清空") { _, _ ->
                clearAllSongs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAllSongs() {
        lifecycleScope.launch {
            try {
                val allSongs = songAdapter.getAllSongs()
                if (allSongs.isEmpty()) {
                    Toast.makeText(this@PlaylistDetailActivity, "歌单中没有歌曲", Toast.LENGTH_SHORT).show()
                    exitMultiSelectMode()
                    return@launch
                }
                allSongs.forEach { song ->
                    playlistRepository.removeSongFromPlaylist(playlistId, song.id)
                }
                Toast.makeText(this@PlaylistDetailActivity, "已清空歌单中的所有歌曲", Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeSongs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistRepository.getSongsInPlaylist(playlistId).collect { songs ->
                    songAdapter.submitList(songs)
                    updateEmptyState(songs.isEmpty())
                    updateSongCount(songs.size)

                    if (isMultiSelectMode) {
                        val validSelections = selectedSongs.intersect(songs.map { it.id }.toSet())
                        if (validSelections.size != selectedSongs.size) {
                            selectedSongs.clear()
                            selectedSongs.addAll(validSelections)
                            songAdapter.setSelectedItems(selectedSongs)
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

    private fun updateSongCount(count: Int) {
        binding.tvSongListTitle.text = "歌曲列表（共${count}首）"
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        binding.batchActionBarContainer.root.visibility = View.VISIBLE
        binding.btnPlayAll.visibility = View.GONE
        songAdapter.setMultiSelectMode(true)
        selectedSongs.clear()
        updateSelectedCount()
        // 隐藏"歌单"按钮（已经在歌单中）
        binding.batchActionBarContainer.btnAddToPlaylist.visibility = View.GONE
        // 显示"移除"按钮
        binding.batchActionBarContainer.btnBatchRemove.visibility = View.VISIBLE
        setupBatchActionListeners()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        binding.batchActionBarContainer.root.visibility = View.GONE
        binding.btnPlayAll.visibility = View.VISIBLE
        songAdapter.setMultiSelectMode(false)
        selectedSongs.clear()
        // 隐藏"移除"按钮
        binding.batchActionBarContainer.btnBatchRemove.visibility = View.GONE
    }

    private fun toggleSelection(songId: String) {
        if (selectedSongs.contains(songId)) {
            selectedSongs.remove(songId)
        } else {
            selectedSongs.add(songId)
        }
        songAdapter.setSelectedItems(selectedSongs)
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        binding.batchActionBarContainer.tvSelectedCount.text = selectedSongs.size.toString()
    }

    private fun toggleFavorite(song: PlaylistSong, isCurrentlyFavorite: Boolean) {
        lifecycleScope.launch {
            try {
                if (isCurrentlyFavorite) {
                    favoriteRepository.removeFromFavorites(song.id)
                    Toast.makeText(this@PlaylistDetailActivity, "已从我喜欢移除", Toast.LENGTH_SHORT).show()
                } else {
                    val songModel = com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl
                    )
                    favoriteRepository.addToFavorites(songModel, song.platform)
                    Toast.makeText(this@PlaylistDetailActivity, "已添加到我喜欢", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPlaylistOptions() {
        val options = arrayOf("重命名", "清空歌单")

        MaterialAlertDialogBuilder(this)
            .setTitle(playlistName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog()
                    1 -> showClearConfirm()
                }
            }
            .show()
    }

    private fun showRenameDialog() {
        val editText = android.widget.EditText(this).apply {
            setText(playlistName)
            setSelection(playlistName.length)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("重命名歌单")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    renamePlaylist(newName)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renamePlaylist(newName: String) {
        lifecycleScope.launch {
            try {
                val playlist = playlistRepository.getPlaylistById(playlistId)
                playlist?.let {
                    playlistRepository.updatePlaylist(it.copy(name = newName))
                    playlistName = newName
                    binding.tvTitle.text = newName
                    Toast.makeText(this@PlaylistDetailActivity, "重命名成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "重命名失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showClearConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空歌单")
            .setMessage("确定要清空歌单中的所有歌曲吗？")
            .setPositiveButton("清空") { _, _ ->
                clearPlaylist()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearPlaylist() {
        lifecycleScope.launch {
            try {
                val songs = playlistRepository.getSongsInPlaylist(playlistId)
                songs.collect { songList ->
                    songList.forEach { song ->
                        playlistRepository.removeSongFromPlaylist(playlistId, song.id)
                    }
                }
                Toast.makeText(this@PlaylistDetailActivity, "歌单已清空", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "清空失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除歌曲")
            .setMessage("确定要从歌单中删除选中的 ${selectedSongs.size} 首歌曲吗？")
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedSongs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedSongs() {
        lifecycleScope.launch {
            try {
                selectedSongs.forEach { songId ->
                    playlistRepository.removeSongFromPlaylist(playlistId, songId)
                }
                exitMultiSelectMode()
                Toast.makeText(this@PlaylistDetailActivity, "已删除 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeSongFromPlaylist(songId: String) {
        lifecycleScope.launch {
            try {
                playlistRepository.removeSongFromPlaylist(playlistId, songId)
                Toast.makeText(this@PlaylistDetailActivity, "已从歌单中删除", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playSong(song: PlaylistSong) {
        lifecycleScope.launch {
            try {
                val platform = try {
                    MusicRepository.Platform.valueOf(song.platform.uppercase())
                } catch (e: Exception) {
                    MusicRepository.Platform.KUWO
                }

                // 检查本地文件是否存在
                val downloadManager = com.tacke.music.download.DownloadManager.getInstance(this@PlaylistDetailActivity)
                val localPath = withContext(Dispatchers.IO) {
                    downloadManager.getLocalSongPath(song.id)
                }

                // 如果本地文件存在，直接播放本地文件
                if (localPath != null) {
                    // 使用带缓存的Repository获取歌曲详情（用于歌词和封面）
                    val cachedRepository = CachedMusicRepository(this@PlaylistDetailActivity)
                    val detail = withContext(Dispatchers.IO) {
                        cachedRepository.getSongDetail(
                            platform = platform,
                            songId = song.id,
                            quality = "320k",
                            songName = song.name,
                            artists = song.artists,
                            coverUrlFromSearch = song.coverUrl
                        )
                    }
                    playbackManager.playFromPlaylist(this@PlaylistDetailActivity, song, localPath, detail)
                    return@launch
                }

                // 非下载管理页面且本地文件不存在，强制重新获取最新URL
                val cachedRepository = CachedMusicRepository(this@PlaylistDetailActivity)
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongUrlWithCache(
                        platform = platform,
                        songId = song.id,
                        quality = "320k",
                        songName = song.name,
                        artists = song.artists,
                        useCache = true,
                        coverUrlFromSearch = song.coverUrl
                    )
                }

                if (detail != null) {
                    // 使用统一播放管理器播放歌曲
                    playbackManager.playFromPlaylist(this@PlaylistDetailActivity, song, detail.url, detail)
                } else {
                    Toast.makeText(this@PlaylistDetailActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAllSongs() {
        val songs = songAdapter.getAllSongs()
        if (songs.isEmpty()) {
            Toast.makeText(this, "歌单为空", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                // 添加所有歌曲到播放列表（不清空原有列表）
                songs.forEach { song ->
                    playlistManager.addSong(song)
                }

                // 播放第一首
                val firstSong = songs.first()
                val platform = try {
                    MusicRepository.Platform.valueOf(firstSong.platform.uppercase())
                } catch (e: Exception) {
                    MusicRepository.Platform.KUWO
                }

                val downloadManager = com.tacke.music.download.DownloadManager.getInstance(this@PlaylistDetailActivity)
                val localPath = withContext(Dispatchers.IO) {
                    downloadManager.getLocalSongPath(firstSong.id)
                }

                // 如果本地文件存在，直接播放本地文件
                if (localPath != null) {
                    val cachedRepository = CachedMusicRepository(this@PlaylistDetailActivity)
                    val detail = withContext(Dispatchers.IO) {
                        cachedRepository.getSongDetail(
                            platform = platform,
                            songId = firstSong.id,
                            quality = "320k",
                            songName = firstSong.name,
                            artists = firstSong.artists,
                            coverUrlFromSearch = firstSong.coverUrl
                        )
                    }
                    playbackManager.playFromPlaylist(this@PlaylistDetailActivity, firstSong, localPath, detail)
                    Toast.makeText(this@PlaylistDetailActivity, "开始播放全部", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 非下载管理页面且本地文件不存在，强制重新获取最新URL
                val cachedRepository = CachedMusicRepository(this@PlaylistDetailActivity)
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongUrlWithCache(
                        platform = platform,
                        songId = firstSong.id,
                        quality = "320k",
                        songName = firstSong.name,
                        artists = firstSong.artists,
                        useCache = true,
                        coverUrlFromSearch = firstSong.coverUrl
                    )
                }

                if (detail != null) {
                    playbackManager.playFromPlaylist(this@PlaylistDetailActivity, firstSong, detail.url, detail)
                    Toast.makeText(this@PlaylistDetailActivity, "开始播放全部", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongsToFavorites(songs: List<PlaylistSong>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                songs.forEach { song ->
                    val songModel = com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl
                    )
                    val isAlreadyFavorite = favoriteRepository.isFavorite(song.id)
                    if (!isAlreadyFavorite) {
                        favoriteRepository.addToFavorites(songModel, song.platform)
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }
                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首到喜欢，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到我喜欢"
                }
                Toast.makeText(this@PlaylistDetailActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongsToNowPlaying(songs: List<PlaylistSong>) {
        // 立即退出多选模式，提升用户体验
        exitMultiSelectMode()

        lifecycleScope.launch {
            try {
                // 使用批量添加方法，不触发自动播放，不预获取URL
                // 新歌曲追加到列表末尾，不影响当前播放状态
                val result = playbackManager.addPlaylistSongsWithoutPlay(songs)
                val addedCount = result.first
                val duplicateCount = result.second

                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到正在播放列表"
                }
                Toast.makeText(this@PlaylistDetailActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showBatchPlaylistSelectionDialog(songs: List<PlaylistSong>) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@PlaylistDetailActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(songs)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@PlaylistDetailActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addSongsToPlaylist(playlists[which].id, songs)
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(songs)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(songs: List<PlaylistSong>) {
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
                        addSongsToPlaylist(playlist.id, songs)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addSongsToPlaylist(targetPlaylistId: String, songs: List<PlaylistSong>) {
        lifecycleScope.launch {
            try {
                val songList = songs.map { song ->
                    com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl
                    )
                }

                playlistRepository.addSongsToPlaylist(targetPlaylistId, songList, songs.firstOrNull()?.platform ?: "kuwo")

                Toast.makeText(
                    this@PlaylistDetailActivity,
                    "已添加 ${songs.size} 首歌曲到歌单",
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PlaylistDetailActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showBatchDownloadQualityDialog(songs: List<PlaylistSong>) {
        val qualities = arrayOf("HR (24bit/96kHz)", "CDQ (16bit/44.1kHz)", "HQ (320kbps)", "LQ (128kbps)")
        val qualityValues = arrayOf("flac24bit", "flac", "320k", "128k")

        MaterialAlertDialogBuilder(this)
            .setTitle("批量下载")
            .setItems(qualities) { _, which ->
                batchDownloadSongs(songs, qualityValues[which])
            }
            .show()
    }

    private fun batchDownloadSongs(songs: List<PlaylistSong>, quality: String) {
        val context = applicationContext
        val cachedRepository = CachedMusicRepository(context)
        val dm = DownloadManager.getInstance(context)

        // 立即显示添加下载任务提示
        Toast.makeText(
            this@PlaylistDetailActivity,
            "已添加 ${songs.size} 首歌曲到下载队列",
            Toast.LENGTH_SHORT
        ).show()

        // 使用 GlobalScope 确保 Activity 销毁后任务继续执行
        GlobalScope.launch(Dispatchers.IO) {
            songs.forEach { song ->
                launch {
                    try {
                        val platform = try {
                            MusicRepository.Platform.valueOf(song.platform.uppercase())
                        } catch (e: Exception) {
                            MusicRepository.Platform.KUWO
                        }
                        val detail = cachedRepository.getSongUrlWithCache(
                            platform = platform,
                            songId = song.id,
                            quality = quality,
                            songName = song.name,
                            artists = song.artists,
                            useCache = true
                        )
                        if (detail != null) {
                            val task = dm.createDownloadTask(
                                Song(
                                    index = 0,
                                    id = song.id,
                                    name = song.name,
                                    artists = song.artists,
                                    coverUrl = detail.cover ?: song.coverUrl
                                ),
                                detail,
                                quality,
                                song.platform
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
     * 为顶部 Toolbar 添加状态栏高度 padding，防止内容被状态栏遮挡
     */
    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 为顶部 Toolbar 添加状态栏高度 padding
            binding.toolbar.updatePadding(
                top = insets.top
            )
            // 为底部设置 padding
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }
}
