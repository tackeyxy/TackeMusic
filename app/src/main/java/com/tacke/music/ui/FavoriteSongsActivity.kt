package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.db.FavoriteSongEntity
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.ListCoverRepairManager
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityPlaylistDetailBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.utils.CoverImageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FavoriteSongsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var playlistManager: PlaylistManager
    private lateinit var playbackManager: PlaybackManager
    private lateinit var downloadManager: DownloadManager
    private lateinit var listCoverRepairManager: ListCoverRepairManager
    private lateinit var songAdapter: FavoriteSongAdapter
    private var favoriteSongs: List<FavoriteSongEntity> = emptyList()
    private var isMultiSelectMode = false
    private val selectedSongs = mutableSetOf<String>()

    private fun isLocalSong(songId: String, platform: String): Boolean {
        return platform.equals("LOCAL", ignoreCase = true) || songId.startsWith("local_")
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FavoriteSongsActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16: 适配 Edge-to-Edge 模式
        setupEdgeToEdge()

        favoriteRepository = FavoriteRepository(this)
        playlistRepository = PlaylistRepository(this)
        playlistManager = PlaylistManager.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)
        downloadManager = DownloadManager.getInstance(this)
        listCoverRepairManager = ListCoverRepairManager.getInstance(this)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        observeFavoriteSongs()
    }

    override fun onResume() {
        super.onResume()
        listCoverRepairManager.repairFavoritesAsync()
    }

    private fun setupUI() {
        // 设置标题
        binding.tvTitle.text = "我喜欢的"
        binding.tvSongListTitle.text = "我的收藏歌曲"

        // 返回按钮
        binding.btnBack.setOnClickListener {
            if (isMultiSelectMode) {
                exitMultiSelectMode()
            } else {
                finish()
            }
        }

        // 播放全部按钮
        binding.btnPlayAll.setOnClickListener {
            if (!isMultiSelectMode) {
                playAllSongs()
            }
        }

        // 隐藏更多按钮（我喜欢的歌单不需要编辑功能）
        binding.btnMore.visibility = View.GONE
    }

    private fun setupRecyclerView() {
        songAdapter = FavoriteSongAdapter(
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
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@FavoriteSongsActivity)
            adapter = songAdapter
        }
    }

    private fun setupClickListeners() {
        setupBatchActionListeners()
    }

    private fun setupBatchActionListeners() {
        // 关闭按钮
        findViewById<View>(R.id.btnCloseBatch)?.setOnClickListener {
            exitMultiSelectMode()
        }

        // 全选按钮
        findViewById<View>(R.id.btnSelectAll)?.setOnClickListener {
            if (selectedSongs.size == songAdapter.itemCount) {
                selectedSongs.clear()
            } else {
                selectedSongs.clear()
                favoriteSongs.forEach { selectedSongs.add(it.id) }
            }
            songAdapter.setSelectedItems(selectedSongs)
            updateSelectedCount()
        }

        // 添加到歌单按钮
        findViewById<View>(R.id.btnAddToPlaylist)?.setOnClickListener {
            val selectedSongList = favoriteSongs.filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchPlaylistSelectionDialog(selectedSongList)
        }

        // 添加到播放按钮
        findViewById<View>(R.id.btnAddToNowPlaying)?.setOnClickListener {
            val selectedSongList = favoriteSongs.filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isNotEmpty()) {
                addSongsToNowPlaying(selectedSongList)
            }
            exitMultiSelectMode()
        }

        // 下载按钮 - 真正的下载功能（跳过本地音乐）
        findViewById<View>(R.id.btnBatchDownload)?.setOnClickListener {
            val selectedSongList = favoriteSongs.filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 过滤掉本地音乐
            val (localSongs, onlineSongs) = selectedSongList.partition { isLocalSong(it.id, it.platform) }

            if (onlineSongs.isEmpty()) {
                // 所有选中的都是本地音乐
                Toast.makeText(this, "本地音乐无需下载", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (localSongs.isNotEmpty()) {
                // 有本地音乐被跳过
                Toast.makeText(this, "已跳过 ${localSongs.size} 首本地音乐，仅下载在线音乐", Toast.LENGTH_SHORT).show()
            }

            showBatchDownloadQualityDialog(onlineSongs)
        }

        // 移除按钮 - 从我喜欢中移除
        findViewById<View>(R.id.btnBatchRemove)?.setOnClickListener {
            showDeleteConfirm()
        }

        // 清空列表按钮
        findViewById<View>(R.id.btnClearAll)?.setOnClickListener {
            showClearAllConfirmDialog()
        }
    }

    private fun showClearAllConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空我喜欢")
            .setMessage("确定要清空所有喜欢的歌曲吗？")
            .setPositiveButton("清空") { _, _ ->
                clearAllFavorites()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAllFavorites() {
        lifecycleScope.launch {
            try {
                if (favoriteSongs.isEmpty()) {
                    Toast.makeText(this@FavoriteSongsActivity, "没有喜欢的歌曲", Toast.LENGTH_SHORT).show()
                    exitMultiSelectMode()
                    return@launch
                }
                favoriteSongs.forEach { song ->
                    favoriteRepository.removeFromFavorites(song.id)
                }
                Toast.makeText(this@FavoriteSongsActivity, "已清空所有喜欢的歌曲", Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@FavoriteSongsActivity, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeFavoriteSongs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                favoriteRepository.getAllFavoriteSongs().collect { songs ->
                    favoriteSongs = songs
                    songAdapter.submitList(songs)
                    binding.tvSongListTitle.text = "歌曲列表（共${songs.size}首）"

                    // 更新空状态
                    if (songs.isEmpty()) {
                        binding.emptyView.visibility = View.VISIBLE
                        binding.recyclerView.visibility = View.GONE
                    } else {
                        binding.emptyView.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                    }

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

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        findViewById<View>(R.id.batchActionBar)?.visibility = View.VISIBLE
        binding.btnPlayAll.visibility = View.GONE
        songAdapter.setMultiSelectMode(true)
        selectedSongs.clear()
        updateSelectedCount()
        // 隐藏"喜欢"按钮（已经在喜欢列表中）
        findViewById<View>(R.id.btnAddToFavorite)?.visibility = View.GONE
        // 显示"移除"按钮
        findViewById<View>(R.id.btnBatchRemove)?.visibility = View.VISIBLE
        setupBatchActionListeners()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        findViewById<View>(R.id.batchActionBar)?.visibility = View.GONE
        binding.btnPlayAll.visibility = View.VISIBLE
        songAdapter.setMultiSelectMode(false)
        selectedSongs.clear()
        // 隐藏"移除"按钮
        findViewById<View>(R.id.btnBatchRemove)?.visibility = View.GONE
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
        findViewById<TextView>(R.id.tvSelectedCount)?.text = selectedSongs.size.toString()
    }

    private fun showDeleteConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle("移除收藏")
            .setMessage("确定要从我喜欢中移除选中的 ${selectedSongs.size} 首歌曲吗？")
            .setPositiveButton("移除") { _, _ ->
                removeSelectedFromFavorites()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun removeSelectedFromFavorites() {
        lifecycleScope.launch {
            try {
                val songsToRemove = favoriteSongs.filter { selectedSongs.contains(it.id) }
                songsToRemove.forEach { song ->
                    favoriteRepository.removeFromFavorites(song)
                }
                exitMultiSelectMode()
                Toast.makeText(this@FavoriteSongsActivity, "已移除 ${songsToRemove.size} 首歌曲", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@FavoriteSongsActivity, "移除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playSong(song: FavoriteSongEntity) {
        lifecycleScope.launch {
            try {
                if (isLocalSong(song.id, song.platform)) {
                    val played = playbackManager.playLocalSongById(
                        context = this@FavoriteSongsActivity,
                        songId = song.id,
                        songName = song.name,
                        songArtists = song.artists,
                        fallbackCoverUrl = song.coverUrl
                    )
                    if (!played) {
                        Toast.makeText(this@FavoriteSongsActivity, "未找到本地文件，请先重新扫描本地音乐", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val platform = when (song.platform.uppercase()) {
                    "KUWO" -> MusicRepository.Platform.KUWO
                    "NETEASE" -> MusicRepository.Platform.NETEASE
                    else -> MusicRepository.Platform.KUWO
                }

                // 获取用户设置的试听音质
                val playbackQuality = SettingsActivity.getPlaybackQuality(this@FavoriteSongsActivity)

                // 非下载管理页面，强制重新获取最新URL，但封面和歌词使用缓存
                val cachedRepository = CachedMusicRepository(this@FavoriteSongsActivity)
                val detail = cachedRepository.getSongUrlWithCache(
                    platform = platform,
                    songId = song.id,
                    quality = playbackQuality,
                    songName = song.name,
                    artists = song.artists,
                    useCache = true,
                    coverUrlFromSearch = song.coverUrl
                )

                if (detail != null) {
                    // 使用获取到的封面，如果没有则尝试使用数据库中的封面
                    val finalCoverUrl = detail.cover ?: song.coverUrl

                    // 添加到播放列表
                    val playlistSong = com.tacke.music.data.model.PlaylistSong(
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = finalCoverUrl ?: "",
                        platform = song.platform
                    )
                    playlistManager.addSong(playlistSong)

                    // 如果获取到了新的封面URL，更新数据库中的记录
                    if (detail.cover != null && detail.cover != song.coverUrl) {
                        favoriteRepository.refreshFavoriteCovers()
                    }

                    // 播放歌曲
                    playbackManager.playFromPlaylist(
                        context = this@FavoriteSongsActivity,
                        song = playlistSong,
                        playUrl = detail.url,
                        songDetail = detail
                    )
                } else {
                    Toast.makeText(this@FavoriteSongsActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@FavoriteSongsActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAllSongs() {
        if (favoriteSongs.isEmpty()) {
            Toast.makeText(this, "暂无收藏歌曲", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                // 添加所有喜欢歌曲到播放列表（不清空原有列表）
                val playlistSongs = favoriteSongs.map { song ->
                    com.tacke.music.data.model.PlaylistSong(
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl,
                        platform = song.platform
                    )
                }
                playbackManager.addPlaylistSongsWithoutPlay(playlistSongs)

                // 播放第一首
                val firstSong = favoriteSongs.first()

                if (isLocalSong(firstSong.id, firstSong.platform)) {
                    val played = playbackManager.playLocalSongById(
                        context = this@FavoriteSongsActivity,
                        songId = firstSong.id,
                        songName = firstSong.name,
                        songArtists = firstSong.artists,
                        fallbackCoverUrl = firstSong.coverUrl
                    )
                    if (played) {
                        Toast.makeText(this@FavoriteSongsActivity, "开始播放全部 ${favoriteSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@FavoriteSongsActivity, "未找到本地文件，请先重新扫描本地音乐", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val platform = when (firstSong.platform.uppercase()) {
                    "KUWO" -> MusicRepository.Platform.KUWO
                    "NETEASE" -> MusicRepository.Platform.NETEASE
                    else -> MusicRepository.Platform.KUWO
                }

                // 获取用户设置的试听音质
                val playbackQuality = SettingsActivity.getPlaybackQuality(this@FavoriteSongsActivity)

                // 非下载管理页面，强制重新获取最新URL，但封面和歌词使用缓存
                val cachedRepository = CachedMusicRepository(this@FavoriteSongsActivity)
                val detail = cachedRepository.getSongUrlWithCache(
                    platform = platform,
                    songId = firstSong.id,
                    quality = playbackQuality,
                    songName = firstSong.name,
                    artists = firstSong.artists,
                    useCache = true,
                    coverUrlFromSearch = firstSong.coverUrl
                )

                if (detail != null) {
                    // 使用获取到的封面，如果没有则尝试使用数据库中的封面
                    val finalCoverUrl = detail.cover ?: firstSong.coverUrl

                    val playlistSong = com.tacke.music.data.model.PlaylistSong(
                        id = firstSong.id,
                        name = firstSong.name,
                        artists = firstSong.artists,
                        coverUrl = finalCoverUrl ?: "",
                        platform = firstSong.platform
                    )

                    // 如果获取到了新的封面URL，更新数据库中的记录
                    if (detail.cover != null && detail.cover != firstSong.coverUrl) {
                        favoriteRepository.refreshFavoriteCovers()
                    }

                    playbackManager.playFromPlaylist(
                        context = this@FavoriteSongsActivity,
                        song = playlistSong,
                        playUrl = detail.url,
                        songDetail = detail
                    )

                    Toast.makeText(this@FavoriteSongsActivity, "开始播放全部 ${favoriteSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FavoriteSongsActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@FavoriteSongsActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongsToNowPlaying(songs: List<FavoriteSongEntity>) {
        // 立即退出多选模式，提升用户体验
        exitMultiSelectMode()

        lifecycleScope.launch {
            try {
                // 转换为播放列表歌曲
                val playlistSongs = songs.map { song ->
                    com.tacke.music.data.model.PlaylistSong(
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl,
                        platform = song.platform
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
                Toast.makeText(this@FavoriteSongsActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@FavoriteSongsActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showBatchPlaylistSelectionDialog(songs: List<FavoriteSongEntity>) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@FavoriteSongsActivity)
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

            MaterialAlertDialogBuilder(this@FavoriteSongsActivity)
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

    private fun showCreatePlaylistDialog(songs: List<FavoriteSongEntity>) {
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

    private fun addSongsToPlaylist(playlistId: String, songs: List<FavoriteSongEntity>) {
        lifecycleScope.launch {
            try {
                val songList = songs.map { song ->
                    Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl
                    )
                }

                playlistRepository.addSongsToPlaylist(playlistId, songList, songs.firstOrNull()?.platform ?: "kuwo")

                Toast.makeText(
                    this@FavoriteSongsActivity,
                    "已添加 ${songs.size} 首歌曲到歌单",
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(
                    this@FavoriteSongsActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showBatchDownloadQualityDialog(songs: List<FavoriteSongEntity>) {
        val qualities = arrayOf("HR (24bit/96kHz)", "CDQ (16bit/44.1kHz)", "HQ (320kbps)", "LQ (128kbps)")
        val qualityValues = arrayOf("flac24bit", "flac", "320k", "128k")

        MaterialAlertDialogBuilder(this)
            .setTitle("批量下载")
            .setItems(qualities) { _, which ->
                batchDownloadSongs(songs, qualityValues[which])
            }
            .show()
    }

    private fun batchDownloadSongs(songs: List<FavoriteSongEntity>, quality: String) {
        val context = applicationContext
        val cachedRepository = CachedMusicRepository(context)
        val dm = DownloadManager.getInstance(context)

        // 关键修复：使用 GlobalScope 确保 Activity 销毁后任务继续执行
        GlobalScope.launch(Dispatchers.IO) {
            var skippedCount = 0
            val songsToDownload = mutableListOf<FavoriteSongEntity>()

            // 第一步：检查所有歌曲的音质
            songs.forEach { song ->
                try {
                    val (hasHigherOrEqualQuality, existingFilePath) = com.tacke.music.utils.DownloadQualityChecker.checkExistingDownloadQuality(
                        context,
                        song.id,
                        quality
                    )

                    if (hasHigherOrEqualQuality) {
                        // 已存在更高或相同音质的文件，跳过
                        skippedCount++
                    } else {
                        // 可以下载，如果需要则删除旧文件
                        if (existingFilePath != null) {
                            com.tacke.music.utils.DownloadQualityChecker.deleteExistingFile(existingFilePath)
                            // 同时从下载历史中删除记录
                            com.tacke.music.utils.DownloadQualityChecker.deleteDownloadRecord(context, song.id)
                        }
                        songsToDownload.add(song)
                    }
                } catch (e: Exception) {
                    // 检查失败，默认允许下载
                    songsToDownload.add(song)
                }
            }

            // 在主线程显示提示
            withContext(Dispatchers.Main) {
                when {
                    skippedCount > 0 && songsToDownload.isEmpty() -> {
                        // 所有歌曲都跳过
                        Toast.makeText(
                            this@FavoriteSongsActivity,
                            "${skippedCount} 首歌曲已存在更高音质或相同音质的文件，请在本地歌曲列表扫描添加！",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    skippedCount > 0 -> {
                        // 部分跳过，部分下载
                        Toast.makeText(
                            this@FavoriteSongsActivity,
                            "已添加 ${songsToDownload.size} 首歌曲到下载队列，${skippedCount} 首已存在更高音质跳过",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    else -> {
                        // 全部下载
                        Toast.makeText(
                            this@FavoriteSongsActivity,
                            "已添加 ${songsToDownload.size} 首歌曲到下载队列",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            // 第二步：下载需要下载的歌曲
            songsToDownload.forEach { song ->
                launch {
                    try {
                        val platform = when (song.platform.uppercase()) {
                            "KUWO" -> MusicRepository.Platform.KUWO
                            "NETEASE" -> MusicRepository.Platform.NETEASE
                            else -> MusicRepository.Platform.KUWO
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
     * 我喜欢歌曲列表适配器
     */
    inner class FavoriteSongAdapter(
        private val onItemClick: (FavoriteSongEntity) -> Unit,
        private val onLongClick: (FavoriteSongEntity) -> Boolean
    ) : RecyclerView.Adapter<FavoriteSongAdapter.SongViewHolder>() {

        private var songs: List<FavoriteSongEntity> = emptyList()
        private var isMultiSelectMode = false
        private val selectedItems = mutableSetOf<String>()

        fun submitList(newSongs: List<FavoriteSongEntity>) {
            songs = newSongs
            notifyDataSetChanged()
        }

        fun setMultiSelectMode(enabled: Boolean) {
            isMultiSelectMode = enabled
            if (!enabled) {
                selectedItems.clear()
            }
            notifyDataSetChanged()
        }

        fun setSelectedItems(selected: Set<String>) {
            selectedItems.clear()
            selectedItems.addAll(selected)
            notifyDataSetChanged()
        }

        inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
            private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
            private val ivSource: ImageView = itemView.findViewById(R.id.ivSource)
            private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
            private val cvCover: CardView = itemView.findViewById(R.id.cvCover)
            private val flCheckbox: View = itemView.findViewById(R.id.flCheckbox)
            private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
            private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
            private val coverOverlay: View = itemView.findViewById(R.id.coverOverlay)

            private fun isLocalSong(song: FavoriteSongEntity): Boolean {
                return song.platform.equals("LOCAL", ignoreCase = true) || song.id.startsWith("local_")
            }

            fun bind(song: FavoriteSongEntity, position: Int) {
                tvSongName.text = song.name
                tvArtist.text = song.artists
                tvIndex.text = (position + 1).toString()

                // 设置音源图标
                val iconRes = when {
                    isLocalSong(song) -> R.drawable.ic_local_music
                    song.platform.uppercase() == "KUWO" -> R.drawable.ic_kuwo_logo
                    song.platform.uppercase() == "NETEASE" -> R.drawable.ic_netease_logo
                    else -> R.drawable.ic_music_note
                }
                ivSource.setImageResource(iconRes)
                ivSource.visibility = View.VISIBLE

                // 加载封面
                loadCover(song)

                val isSelected = selectedItems.contains(song.id)

                if (isMultiSelectMode) {
                    flCheckbox.visibility = View.VISIBLE
                    tvIndex.visibility = View.GONE
                    ivSource.visibility = View.GONE
                    ivCheckbox.isSelected = isSelected
                    updateSelectedVisuals(isSelected)

                    itemView.setOnClickListener {
                        onItemClick(song)
                    }
                } else {
                    flCheckbox.visibility = View.GONE
                    tvIndex.visibility = View.VISIBLE
                    ivSource.visibility = View.VISIBLE
                    resetVisuals()

                    itemView.setOnClickListener { onItemClick(song) }
                }

                itemView.setOnLongClickListener { onLongClick(song) }
            }

            private fun updateSelectedVisuals(isSelected: Boolean) {
                if (isSelected) {
                    itemView.setBackgroundResource(R.drawable.bg_item_selected)
                    coverOverlay.visibility = View.VISIBLE
                    coverOverlay.alpha = 0.3f
                    cvCover.scaleX = 0.95f
                    cvCover.scaleY = 0.95f
                } else {
                    itemView.setBackgroundResource(android.R.color.transparent)
                    coverOverlay.visibility = View.GONE
                    cvCover.scaleX = 1f
                    cvCover.scaleY = 1f
                }
            }

            private fun resetVisuals() {
                itemView.setBackgroundResource(android.R.color.transparent)
                coverOverlay.visibility = View.GONE
                cvCover.scaleX = 1f
                cvCover.scaleY = 1f
            }

            private fun loadCover(song: FavoriteSongEntity) {
                val coverUrl = song.coverUrl

                when {
                    coverUrl.isNullOrEmpty() -> {
                        // LOCAL 歌曲无封面时不走在线下载
                        if (isLocalSong(song)) {
                            ivCover.setImageResource(R.drawable.ic_music_note)
                            return
                        }
                        // 没有封面URL，尝试从网络获取
                        ivCover.setImageResource(R.drawable.ic_music_note)
                        downloadAndCacheCover(song)
                    }
                    coverUrl.startsWith("http") -> {
                        // 网络图片，使用 Glide 加载
                        Glide.with(itemView.context)
                            .load(coverUrl)
                            .placeholder(R.drawable.ic_music_note)
                            .error(R.drawable.ic_music_note)
                            .into(ivCover)
                    }
                    coverUrl.startsWith("content://") || coverUrl.startsWith("file://") -> {
                        // 本地 URI（MediaStore/SAF/file）
                        Glide.with(itemView.context)
                            .load(coverUrl)
                            .placeholder(R.drawable.ic_music_note)
                            .error(R.drawable.ic_music_note)
                            .into(ivCover)
                    }
                    coverUrl.startsWith("/") -> {
                        // 本地图片路径（以/开头的绝对路径）
                        try {
                            val file = File(coverUrl)
                            if (file.exists()) {
                                Glide.with(itemView.context)
                                    .load(file)
                                    .placeholder(R.drawable.ic_music_note)
                                    .error(R.drawable.ic_music_note)
                                    .into(ivCover)
                            } else {
                                // 本地文件不存在，尝试重新下载
                                ivCover.setImageResource(R.drawable.ic_music_note)
                                downloadAndCacheCover(song)
                            }
                        } catch (e: Exception) {
                            ivCover.setImageResource(R.drawable.ic_music_note)
                            downloadAndCacheCover(song)
                        }
                    }
                    else -> {
                        if (isLocalSong(song)) {
                            ivCover.setImageResource(R.drawable.ic_music_note)
                            return
                        }
                        // 相对路径（如酷我音乐的封面URL），需要解析为完整URL
                        ivCover.setImageResource(R.drawable.ic_music_note)
                        resolveAndLoadCover(song, coverUrl)
                    }
                }
            }

            private fun resolveAndLoadCover(song: FavoriteSongEntity, relativeUrl: String) {
                if (isLocalSong(song)) {
                    return
                }
                lifecycleScope.launch {
                    try {
                        val context = itemView.context
                        val resolvedUrl = withContext(Dispatchers.IO) {
                            com.tacke.music.utils.CoverUrlResolver.resolveCoverUrl(
                                context,
                                relativeUrl,
                                song.id,
                                song.platform
                            )
                        }

                        if (resolvedUrl != null) {
                            // 使用解析后的URL加载封面
                            Glide.with(context)
                                .load(resolvedUrl)
                                .placeholder(R.drawable.ic_music_note)
                                .error(R.drawable.ic_music_note)
                                .into(ivCover)
                        } else {
                            // 解析失败，尝试下载缓存
                            downloadAndCacheCover(song)
                        }
                    } catch (e: Exception) {
                        // 解析失败，尝试下载缓存
                        downloadAndCacheCover(song)
                    }
                }
            }

            private fun downloadAndCacheCover(song: FavoriteSongEntity) {
                if (isLocalSong(song)) {
                    return
                }
                lifecycleScope.launch {
                    try {
                        val context = itemView.context
                        // 使用小写的平台名称（与CoverImageManager缓存键一致）
                        val cachePlatform = song.platform.lowercase()
                        val localPath = CoverImageManager.downloadAndCacheCover(
                            context,
                            song.id,
                            cachePlatform
                        )

                        if (localPath != null) {
                            // 下载成功，更新UI
                            withContext(Dispatchers.Main) {
                                Glide.with(context)
                                    .load(File(localPath))
                                    .placeholder(R.drawable.ic_music_note)
                                    .error(R.drawable.ic_music_note)
                                    .into(ivCover)
                            }

                            // 更新数据库中的封面URL
                            favoriteRepository.refreshFavoriteCovers()
                        }
                    } catch (e: Exception) {
                        // 下载失败，保持默认图标
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_song_modern, parent, false)
            return SongViewHolder(view)
        }

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            holder.bind(songs[position], position)
        }

        override fun getItemCount(): Int = songs.size
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
            binding.statusBarPlaceholder?.layoutParams?.height = insets.top
            binding.statusBarPlaceholder?.requestLayout()

            // 为底部设置 padding
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }
}
