package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.api.PlaylistTrack
import com.tacke.music.data.api.RetrofitClient
import com.tacke.music.data.api.SongDetailInfo
import com.tacke.music.data.api.TrackArtist
import com.tacke.music.data.api.TrackAlbum
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityNeteasePlaylistDetailBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.NeteasePlaylistTrackAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NeteasePlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNeteasePlaylistDetailBinding
    private lateinit var trackAdapter: NeteasePlaylistTrackAdapter
    private lateinit var playlistManager: PlaylistManager
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playbackManager: PlaybackManager

    private var playlistId: Long = 0
    private var playlistName: String = ""
    private var playlistCover: String = ""
    private var isMultiSelectMode = false

    // 分页加载相关
    private var allTrackIds: List<Long> = emptyList()
    private var loadedTrackCount = 0
    private var isLoadingMore = false
    private val batchSize = 100

    companion object {
        fun start(context: Context, playlistId: Long, playlistName: String, playlistCover: String) {
            val intent = Intent(context, NeteasePlaylistDetailActivity::class.java).apply {
                putExtra("playlist_id", playlistId)
                putExtra("playlist_name", playlistName)
                putExtra("playlist_cover", playlistCover)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNeteasePlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16: 适配 Edge-to-Edge 模式
        setupEdgeToEdge()

        playlistId = intent.getLongExtra("playlist_id", 0)
        playlistName = intent.getStringExtra("playlist_name") ?: ""
        playlistCover = intent.getStringExtra("playlist_cover") ?: ""

        if (playlistId == 0L) {
            Toast.makeText(this, "歌单不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        playlistManager = PlaylistManager.getInstance(this)
        playlistRepository = PlaylistRepository(this)
        favoriteRepository = FavoriteRepository(this)
        playbackManager = PlaybackManager.getInstance(this)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        setupBatchActionListeners()
        setupScrollListener()
        loadPlaylistDetail()
    }

    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
        }
    }

    private fun setupUI() {
        binding.tvTitle.text = playlistName
        binding.tvPlaylistName.text = playlistName

        // 加载封面
        Glide.with(this)
            .load(playlistCover)
            .placeholder(com.tacke.music.R.drawable.ic_album_default)
            .error(com.tacke.music.R.drawable.ic_album_default)
            .into(binding.ivPlaylistCover)
    }

    private fun setupRecyclerView() {
        trackAdapter = NeteasePlaylistTrackAdapter(
            onItemClick = { track ->
                if (isMultiSelectMode) {
                    updateBatchActionBar()
                } else {
                    // 推荐歌单列表点击歌曲：添加到播放列表并播放
                    addToNowPlayingAndPlay(track)
                }
            },
            onLongClick = { track ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    true
                } else {
                    false
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = trackAdapter
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            if (isMultiSelectMode) {
                exitMultiSelectMode()
            } else {
                finish()
            }
        }

        binding.btnPlayAll.setOnClickListener {
            if (!isMultiSelectMode) {
                playAllTracks()
            }
        }


    }

    private fun setupBatchActionListeners() {
        // 关闭按钮
        binding.batchActionBarContainer.btnCloseBatch?.setOnClickListener {
            exitMultiSelectMode()
        }

        // 全选按钮
        binding.batchActionBarContainer.btnSelectAll.setOnClickListener {
            trackAdapter.selectAll()
            updateBatchActionBar()
        }

        // 下载按钮
        binding.batchActionBarContainer.btnBatchDownload.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchDownloadQualityDialog(selectedTracks)
        }

        // 添加到喜欢按钮
        binding.batchActionBarContainer.btnAddToFavorite.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addTracksToFavorites(selectedTracks)
        }

        // 添加到歌单按钮
        binding.batchActionBarContainer.btnAddToPlaylist.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchPlaylistSelectionDialog(selectedTracks)
        }

        // 加入播放按钮
        binding.batchActionBarContainer.btnAddToNowPlaying.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addTracksToNowPlaying(selectedTracks)
        }
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        trackAdapter.setMultiSelectMode(true)
        showBatchActionBar()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        trackAdapter.setMultiSelectMode(false)
        hideBatchActionBar()
    }

    private fun showBatchActionBar() {
        binding.batchActionBarContainer.root.visibility = View.VISIBLE
        updateBatchActionBar()
        setupBatchActionListeners()
    }

    private fun hideBatchActionBar() {
        binding.batchActionBarContainer.root.visibility = View.GONE
    }

    private fun updateBatchActionBar() {
        val selectedCount = trackAdapter.getSelectedTracks().size
        binding.batchActionBarContainer.tvSelectedCount.text = selectedCount.toString()
    }

    private fun setupScrollListener() {
        binding.nestedScrollView.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
                // 检查是否滚动到底部
                val child = v.getChildAt(0)
                if (child != null) {
                    val diff = child.bottom - (v.height + scrollY)
                    // 当距离底部小于100px时触发加载更多
                    if (diff < 100 && !isLoadingMore && loadedTrackCount < allTrackIds.size) {
                        loadMoreTracks()
                    }
                }
            }
        )
    }

    private fun loadPlaylistDetail() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.playlistApi.getPlaylistDetail(playlistId)
                }

                if (response.code == 200 && response.playlist != null) {
                    val playlist = response.playlist
                    playlistName = playlist.name
                    playlistCover = playlist.coverImgUrl ?: ""

                    binding.tvPlaylistName.text = playlistName
                    binding.tvSongCount.text = "${playlist.trackCount} 首歌曲"
                    binding.tvDescription.text = playlist.description ?: "暂无简介"

                    // 重新加载封面
                    Glide.with(this@NeteasePlaylistDetailActivity)
                        .load(playlistCover)
                        .placeholder(com.tacke.music.R.drawable.ic_album_default)
                        .error(com.tacke.music.R.drawable.ic_album_default)
                        .into(binding.ivPlaylistCover)

                    // 获取所有trackIds
                    allTrackIds = playlist.trackIds?.map { it.id } ?: emptyList()

                    // 如果tracks已经有数据，先显示
                    val initialTracks = playlist.tracks ?: emptyList()
                    if (initialTracks.isNotEmpty()) {
                        trackAdapter.submitList(initialTracks)
                        loadedTrackCount = initialTracks.size
                        updateEmptyState(false)
                    } else {
                        updateEmptyState(true)
                    }

                    // 如果还有更多歌曲需要加载
                    if (loadedTrackCount < allTrackIds.size) {
                        loadMoreTracks()
                    }
                } else {
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "加载歌单失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun loadMoreTracks() {
        if (isLoadingMore || loadedTrackCount >= allTrackIds.size) return

        isLoadingMore = true
        binding.progressBarLoadMore.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // 计算本次要加载的id范围
                val endIndex = minOf(loadedTrackCount + batchSize, allTrackIds.size)
                val batchIds = allTrackIds.subList(loadedTrackCount, endIndex)

                // 构建ids参数 [id1,id2,id3,...]
                val idsString = "[${batchIds.joinToString(",")}]"

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.playlistApi.getSongDetails(idsString)
                }

                if (response.code == 200 && response.songs != null) {
                    val newTracks = response.songs.map { it.toPlaylistTrack() }
                    trackAdapter.addTracks(newTracks)
                    loadedTrackCount += batchIds.size
                    updateEmptyState(trackAdapter.getAllTracks().isEmpty())
                }
            } catch (e: Exception) {
                // 加载失败，不中断流程
            } finally {
                isLoadingMore = false
                binding.progressBarLoadMore.visibility = View.GONE
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    /**
     * 添加歌曲到播放列表并播放（推荐歌单列表使用）
     */
    private fun addToNowPlayingAndPlay(track: PlaylistTrack) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val platform = MusicRepository.Platform.NETEASE

                // 获取歌曲详情
                val detail = withContext(Dispatchers.IO) {
                    MusicRepository().getSongDetail(platform, track.id.toString(), "320k")
                }

                if (detail != null) {
                    // 先添加到播放列表
                    val playlistSong = com.tacke.music.data.model.PlaylistSong(
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: "",
                        platform = "netease"
                    )
                    playlistManager.addSong(playlistSong)

                    // 然后播放
                    val song = Song(
                        index = 0,
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: ""
                    )
                    playbackManager.playFromSearch(this@NeteasePlaylistDetailActivity, song, platform, detail)
                } else {
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun playAllTracks() {
        val tracks = trackAdapter.getAllTracks()
        if (tracks.isEmpty()) {
            Toast.makeText(this, "歌单为空", Toast.LENGTH_SHORT).show()
            return
        }

        // 先将所有歌曲添加到播放列表
        lifecycleScope.launch {
            tracks.forEach { track ->
                val playlistSong = com.tacke.music.data.model.PlaylistSong(
                    id = track.id.toString(),
                    name = track.name,
                    artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                    coverUrl = track.al?.picUrl ?: "",
                    platform = "netease"
                )
                playlistManager.addSong(playlistSong)
            }

            // 播放第一首
            addToNowPlayingAndPlay(tracks[0])
        }
    }

    private fun toggleFavorite(track: PlaylistTrack, isCurrentlyFavorite: Boolean) {
        lifecycleScope.launch {
            try {
                if (isCurrentlyFavorite) {
                    favoriteRepository.removeFromFavorites(track.id.toString())
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "已从我喜欢移除", Toast.LENGTH_SHORT).show()
                } else {
                    val song = Song(
                        index = 0,
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: ""
                    )
                    favoriteRepository.addToFavorites(song, "netease")
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "已添加到我喜欢", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addToNowPlaying(track: PlaylistTrack) {
        lifecycleScope.launch {
            try {
                val playlistSong = com.tacke.music.data.model.PlaylistSong(
                    id = track.id.toString(),
                    name = track.name,
                    artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                    coverUrl = track.al?.picUrl ?: "",
                    platform = "netease"
                )
                playlistManager.addSong(playlistSong)
                Toast.makeText(this@NeteasePlaylistDetailActivity, "已添加到播放列表", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addTracksToFavorites(tracks: List<PlaylistTrack>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                tracks.forEach { track ->
                    val song = Song(
                        index = 0,
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: ""
                    )
                    val isAlreadyFavorite = favoriteRepository.isFavorite(track.id.toString())
                    if (!isAlreadyFavorite) {
                        favoriteRepository.addToFavorites(song, "netease")
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }
                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首到喜欢，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到我喜欢"
                }
                Toast.makeText(this@NeteasePlaylistDetailActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addTracksToNowPlaying(tracks: List<PlaylistTrack>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                tracks.forEach { track ->
                    val playlistSong = com.tacke.music.data.model.PlaylistSong(
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: "",
                        platform = "netease"
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
                Toast.makeText(this@NeteasePlaylistDetailActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPlaylistSelectionDialog(track: PlaylistTrack) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@NeteasePlaylistDetailActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(listOf(track))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@NeteasePlaylistDetailActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addTracksToPlaylist(playlists[which].id, listOf(track))
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(listOf(track))
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showBatchPlaylistSelectionDialog(tracks: List<PlaylistTrack>) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@NeteasePlaylistDetailActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(tracks)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@NeteasePlaylistDetailActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addTracksToPlaylist(playlists[which].id, tracks)
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(tracks)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(tracks: List<PlaylistTrack>) {
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
                        addTracksToPlaylist(playlist.id, tracks)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addTracksToPlaylist(playlistId: String, tracks: List<PlaylistTrack>) {
        lifecycleScope.launch {
            try {
                val songList = tracks.map { track ->
                    Song(
                        index = 0,
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: ""
                    )
                }

                playlistRepository.addSongsToPlaylist(playlistId, songList, "netease")

                Toast.makeText(
                    this@NeteasePlaylistDetailActivity,
                    "已添加 ${tracks.size} 首歌曲到歌单",
                    Toast.LENGTH_SHORT
                ).show()

                if (isMultiSelectMode) {
                    exitMultiSelectMode()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@NeteasePlaylistDetailActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showDownloadQualityDialog(track: PlaylistTrack) {
        val qualities = arrayOf("HR (24bit/96kHz)", "CDQ (16bit/44.1kHz)", "HQ (320kbps)", "LQ (128kbps)")
        val qualityValues = arrayOf("flac24bit", "flac", "320k", "128k")

        MaterialAlertDialogBuilder(this)
            .setTitle("选择音质")
            .setItems(qualities) { _, which ->
                downloadTrack(track, qualityValues[which])
            }
            .show()
    }

    private fun showBatchDownloadQualityDialog(tracks: List<PlaylistTrack>) {
        val qualities = arrayOf("HR (24bit/96kHz)", "CDQ (16bit/44.1kHz)", "HQ (320kbps)", "LQ (128kbps)")
        val qualityValues = arrayOf("flac24bit", "flac", "320k", "128k")

        MaterialAlertDialogBuilder(this)
            .setTitle("批量下载")
            .setItems(qualities) { _, which ->
                batchDownloadTracks(tracks, qualityValues[which])
            }
            .show()
    }

    private fun downloadTrack(track: PlaylistTrack, quality: String) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val platform = MusicRepository.Platform.NETEASE
                val repository = MusicRepository()
                val detail = withContext(Dispatchers.IO) {
                    repository.getSongDetail(platform, track.id.toString(), quality)
                }
                if (detail != null) {
                    val song = Song(
                        index = 0,
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: ""
                    )
                    val downloadManager = DownloadManager.getInstance(this@NeteasePlaylistDetailActivity)
                    val task = downloadManager.createDownloadTask(song, detail, quality, MusicRepository.Platform.NETEASE.name)
                    downloadManager.startDownload(task)
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "开始下载: ${task.fileName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun batchDownloadTracks(tracks: List<PlaylistTrack>, quality: String) {
        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0

            tracks.forEach { track ->
                try {
                    val platform = MusicRepository.Platform.NETEASE
                    val repository = MusicRepository()
                    val detail = withContext(Dispatchers.IO) {
                        repository.getSongDetail(platform, track.id.toString(), quality)
                    }
                    if (detail != null) {
                        val song = Song(
                            index = 0,
                            id = track.id.toString(),
                            name = track.name,
                            artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                            coverUrl = track.al?.picUrl ?: ""
                        )
                        val downloadManager = DownloadManager.getInstance(this@NeteasePlaylistDetailActivity)
                        val task = downloadManager.createDownloadTask(song, detail, quality, MusicRepository.Platform.NETEASE.name)
                        downloadManager.startDownload(task)
                        successCount++
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    failCount++
                }
            }

            Toast.makeText(
                this@NeteasePlaylistDetailActivity,
                "批量下载开始: 成功 $successCount 首, 失败 $failCount 首",
                Toast.LENGTH_LONG
            ).show()

            exitMultiSelectMode()
        }
    }

    // 扩展函数：将SongDetailInfo转换为PlaylistTrack
    private fun SongDetailInfo.toPlaylistTrack(): PlaylistTrack {
        val trackArtists = this.artists?.map { artist ->
            TrackArtist(id = artist.id, name = artist.name)
        }
        val trackAlbum = this.album?.let { alb ->
            TrackAlbum(id = alb.id, name = alb.name, picUrl = alb.picUrl)
        }
        return PlaylistTrack(
            id = this.id,
            name = this.name,
            ar = trackArtists,
            al = trackAlbum,
            dt = this.duration
        )
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
