package com.tacke.music.recognition.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.recognition.MusicSearchService
import com.tacke.music.recognition.RecognitionEngineManager
import com.tacke.music.recognition.api.AcoustIdTrack
import com.tacke.music.recognition.api.DoresoTrack
import com.tacke.music.recognition.api.ShazamTrack
import com.tacke.music.ui.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecognitionResultDialog(
    context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onDismiss: (() -> Unit)? = null
) : Dialog(context, R.style.DialogTheme) {

    private lateinit var tvShazamTitle: TextView
    private lateinit var tvShazamArtist: TextView
    private lateinit var ivShazamCover: ImageView
    private lateinit var rvSearchResults: RecyclerView
    private lateinit var tvNoResults: TextView
    private lateinit var layoutRecognizedSong: LinearLayout

    private lateinit var adapter: RecognitionResultAdapter
    private val musicSearchService = MusicSearchService(context)
    private val favoriteRepository = FavoriteRepository(context)
    private val playlistRepository = PlaylistRepository(context)
    private val musicRepository = MusicRepository()
    private val downloadManager = DownloadManager.getInstance(context)
    private lateinit var playbackManager: PlaybackManager
    private lateinit var playlistManager: PlaylistManager

    private var kuwoSong: Song? = null
    private var neteaseSong: Song? = null

    // 当前显示的封面URL（优先网易云，其次酷我，最后识别结果）
    private var currentCoverUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_recognition_result, null)
        setContentView(view)

        playbackManager = PlaybackManager.getInstance(context)
        playlistManager = PlaylistManager.getInstance(context)

        initViews(view)
        setupRecyclerView()
    }

    private fun initViews(view: android.view.View) {
        tvShazamTitle = view.findViewById(R.id.tvShazamTitle)
        tvShazamArtist = view.findViewById(R.id.tvShazamArtist)
        ivShazamCover = view.findViewById(R.id.ivShazamCover)
        rvSearchResults = view.findViewById(R.id.rvSearchResults)
        tvNoResults = view.findViewById(R.id.tvNoResults)
        layoutRecognizedSong = view.findViewById(R.id.layoutRecognizedSong)

        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            dismiss()
        }
    }

    private fun setupRecyclerView() {
        adapter = RecognitionResultAdapter(
            onPlayClick = { song -> playSong(song) },
            onFavoriteClick = { song, isFavorite -> toggleFavorite(song, isFavorite) },
            onDownloadClick = { song -> downloadSong(song) },
            onAddToPlaylistClick = { song -> addToPlaylist(song) }
        )
        rvSearchResults.layoutManager = LinearLayoutManager(context)
        rvSearchResults.adapter = adapter
    }

    /**
     * 显示 Shazam 识别结果
     * 封面显示优先级：网易云 > 酷我 > Shazam
     */
    fun showResult(track: ShazamTrack) {
        show()

        // 显示识别的歌曲信息
        tvShazamTitle.text = track.title ?: "未知歌曲"
        tvShazamArtist.text = track.subtitle ?: "未知歌手"

        // 先显示 Shazam 封面作为占位
        val shazamCoverUrl = track.images?.coverart
            ?: track.images?.coverArtHq
            ?: track.share?.image
        currentCoverUrl = shazamCoverUrl
        loadCoverImage(currentCoverUrl)

        // 搜索音源
        lifecycleScope.launch {
            try {
                Toast.makeText(context, "正在搜索音源...", Toast.LENGTH_SHORT).show()
                val result = musicSearchService.searchRecognizedSong(track)
                kuwoSong = result.kuwoSong
                neteaseSong = result.neteaseSong

                // 获取优化后的封面URL（优先网易云，其次酷我）
                val optimizedCoverUrl = getOptimizedCoverUrl()
                if (optimizedCoverUrl != currentCoverUrl) {
                    currentCoverUrl = optimizedCoverUrl
                    withContext(Dispatchers.Main) {
                        loadCoverImage(currentCoverUrl)
                    }
                }

                // 获取酷我图片完整URL并更新
                kuwoSong?.let { song ->
                    val coverUrlValue = song.coverUrl
                    if (!coverUrlValue.isNullOrEmpty() && !coverUrlValue.startsWith("http")) {
                        val fullUrl = musicRepository.getKuwoCoverByAlbumPic(coverUrlValue)
                        if (!fullUrl.isNullOrEmpty()) {
                            song.coverUrl = fullUrl
                        }
                    }
                }

                // 检查收藏状态
                checkAndUpdateFavoriteStates()

                withContext(Dispatchers.Main) {
                    if (kuwoSong == null && neteaseSong == null) {
                        tvNoResults.visibility = android.view.View.VISIBLE
                        rvSearchResults.visibility = android.view.View.GONE
                        Toast.makeText(context, "未找到相关音源", Toast.LENGTH_SHORT).show()
                    } else {
                        tvNoResults.visibility = android.view.View.GONE
                        rvSearchResults.visibility = android.view.View.VISIBLE
                        adapter.setSongs(kuwoSong, neteaseSong)
                        val sourceCount = listOfNotNull(kuwoSong, neteaseSong).size
                        Toast.makeText(context, "找到 $sourceCount 个音源", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    tvNoResults.visibility = android.view.View.VISIBLE
                    rvSearchResults.visibility = android.view.View.GONE
                }
            }
        }
    }

    /**
     * 显示 AcoustID 识别结果
     * 封面显示优先级：网易云 > 酷我
     */
    fun showAcoustIdResult(track: AcoustIdTrack) {
        show()

        // 显示识别的歌曲信息
        tvShazamTitle.text = track.title ?: "未知歌曲"
        tvShazamArtist.text = track.artist ?: "未知歌手"

        // AcoustID 不直接提供封面，显示默认图标作为占位
        currentCoverUrl = null
        loadCoverImage(null)

        // 搜索音源
        lifecycleScope.launch {
            try {
                Toast.makeText(context, "正在搜索音源...", Toast.LENGTH_SHORT).show()
                val result = musicSearchService.searchRecognizedSong(track)
                kuwoSong = result.kuwoSong
                neteaseSong = result.neteaseSong

                // 获取优化后的封面URL（优先网易云，其次酷我）
                val optimizedCoverUrl = getOptimizedCoverUrl()
                currentCoverUrl = optimizedCoverUrl
                withContext(Dispatchers.Main) {
                    loadCoverImage(currentCoverUrl)
                }

                // 获取酷我图片完整URL并更新
                kuwoSong?.let { song ->
                    val coverUrlValue = song.coverUrl
                    if (!coverUrlValue.isNullOrEmpty() && !coverUrlValue.startsWith("http")) {
                        val fullUrl = musicRepository.getKuwoCoverByAlbumPic(coverUrlValue)
                        if (!fullUrl.isNullOrEmpty()) {
                            song.coverUrl = fullUrl
                        }
                    }
                }

                // 检查收藏状态
                checkAndUpdateFavoriteStates()

                withContext(Dispatchers.Main) {
                    if (kuwoSong == null && neteaseSong == null) {
                        tvNoResults.visibility = android.view.View.VISIBLE
                        rvSearchResults.visibility = android.view.View.GONE
                        Toast.makeText(context, "未找到相关音源", Toast.LENGTH_SHORT).show()
                    } else {
                        tvNoResults.visibility = android.view.View.GONE
                        rvSearchResults.visibility = android.view.View.VISIBLE
                        adapter.setSongs(kuwoSong, neteaseSong)
                        val sourceCount = listOfNotNull(kuwoSong, neteaseSong).size
                        Toast.makeText(context, "找到 $sourceCount 个音源", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    tvNoResults.visibility = android.view.View.VISIBLE
                    rvSearchResults.visibility = android.view.View.GONE
                }
            }
        }
    }

    /**
     * 显示 Doreso 识别结果
     * 封面显示优先级：网易云 > 酷我
     */
    fun showDoresoResult(track: DoresoTrack) {
        show()

        // 显示识别的歌曲信息
        tvShazamTitle.text = track.title.ifEmpty { "未知歌曲" }
        tvShazamArtist.text = track.artist.ifEmpty { "未知歌手" }

        // Doreso 不直接提供封面，显示默认图标作为占位
        currentCoverUrl = null
        loadCoverImage(null)

        // 搜索音源
        lifecycleScope.launch {
            try {
                // 使用歌曲标题和歌手构建搜索关键词
                val searchQuery = "${track.title} ${track.artist}".trim()
                Toast.makeText(context, "正在搜索音源...", Toast.LENGTH_SHORT).show()

                // 使用MusicSearchService的搜索方法
                val result = musicSearchService.searchByQuery(searchQuery)
                kuwoSong = result.kuwoSong
                neteaseSong = result.neteaseSong

                // 获取优化后的封面URL（优先网易云，其次酷我）
                val optimizedCoverUrl = getOptimizedCoverUrl()
                currentCoverUrl = optimizedCoverUrl
                withContext(Dispatchers.Main) {
                    loadCoverImage(currentCoverUrl)
                }

                // 获取酷我图片完整URL并更新
                kuwoSong?.let { song ->
                    val coverUrlValue = song.coverUrl
                    if (!coverUrlValue.isNullOrEmpty() && !coverUrlValue.startsWith("http")) {
                        val fullUrl = musicRepository.getKuwoCoverByAlbumPic(coverUrlValue)
                        if (!fullUrl.isNullOrEmpty()) {
                            song.coverUrl = fullUrl
                        }
                    }
                }

                // 检查收藏状态
                checkAndUpdateFavoriteStates()

                withContext(Dispatchers.Main) {
                    if (kuwoSong == null && neteaseSong == null) {
                        tvNoResults.visibility = android.view.View.VISIBLE
                        rvSearchResults.visibility = android.view.View.GONE
                        Toast.makeText(context, "未找到相关音源", Toast.LENGTH_SHORT).show()
                    } else {
                        tvNoResults.visibility = android.view.View.GONE
                        rvSearchResults.visibility = android.view.View.VISIBLE
                        adapter.setSongs(kuwoSong, neteaseSong)
                        val sourceCount = listOfNotNull(kuwoSong, neteaseSong).size
                        Toast.makeText(context, "找到 $sourceCount 个音源", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    tvNoResults.visibility = android.view.View.VISIBLE
                    rvSearchResults.visibility = android.view.View.GONE
                }
            }
        }
    }

    /**
     * 显示统一格式的识别结果
     * 封面显示优先级：网易云 > 酷我 > 识别结果
     */
    fun showRecognitionResult(track: RecognitionEngineManager.RecognizedTrack) {
        show()

        // 显示识别的歌曲信息
        tvShazamTitle.text = track.title.ifEmpty { "未知歌曲" }
        tvShazamArtist.text = track.artist.ifEmpty { "未知歌手" }

        // 先显示识别结果的封面（作为占位）
        currentCoverUrl = track.coverUrl
        loadCoverImage(currentCoverUrl)

        // 搜索音源
        lifecycleScope.launch {
            try {
                // 使用歌曲标题和歌手构建搜索关键词
                val searchQuery = "${track.title} ${track.artist}".trim()
                Toast.makeText(context, "正在搜索音源...", Toast.LENGTH_SHORT).show()

                // 使用MusicSearchService的搜索方法
                val result = musicSearchService.searchByQuery(searchQuery)
                kuwoSong = result.kuwoSong
                neteaseSong = result.neteaseSong

                // 获取优化后的封面URL（优先网易云，其次酷我）
                val optimizedCoverUrl = getOptimizedCoverUrl()
                if (optimizedCoverUrl != currentCoverUrl) {
                    currentCoverUrl = optimizedCoverUrl
                    withContext(Dispatchers.Main) {
                        loadCoverImage(currentCoverUrl)
                    }
                }

                // 获取酷我图片完整URL并更新
                kuwoSong?.let { song ->
                    val coverUrlValue = song.coverUrl
                    if (!coverUrlValue.isNullOrEmpty() && !coverUrlValue.startsWith("http")) {
                        val fullUrl = musicRepository.getKuwoCoverByAlbumPic(coverUrlValue)
                        if (!fullUrl.isNullOrEmpty()) {
                            song.coverUrl = fullUrl
                        }
                    }
                }

                // 检查收藏状态
                checkAndUpdateFavoriteStates()

                withContext(Dispatchers.Main) {
                    if (kuwoSong == null && neteaseSong == null) {
                        tvNoResults.visibility = android.view.View.VISIBLE
                        rvSearchResults.visibility = android.view.View.GONE
                        Toast.makeText(context, "未找到相关音源", Toast.LENGTH_SHORT).show()
                    } else {
                        tvNoResults.visibility = android.view.View.GONE
                        rvSearchResults.visibility = android.view.View.VISIBLE
                        adapter.setSongs(kuwoSong, neteaseSong)
                        val sourceCount = listOfNotNull(kuwoSong, neteaseSong).size
                        Toast.makeText(context, "找到 $sourceCount 个音源", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    tvNoResults.visibility = android.view.View.VISIBLE
                    rvSearchResults.visibility = android.view.View.GONE
                }
            }
        }
    }

    /**
     * 加载封面图片
     */
    private fun loadCoverImage(url: String?) {
        if (!url.isNullOrEmpty()) {
            Glide.with(context)
                .load(url)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .into(ivShazamCover)
        } else {
            Glide.with(context)
                .load(R.drawable.ic_music_note)
                .into(ivShazamCover)
        }
    }

    /**
     * 获取优化后的封面URL
     * 优先级：网易云 > 酷我 > 识别结果
     */
    private fun getOptimizedCoverUrl(): String? {
        // 优先使用网易云封面
        if (!neteaseSong?.coverUrl.isNullOrEmpty()) {
            return neteaseSong?.coverUrl
        }
        // 其次使用酷我封面
        if (!kuwoSong?.coverUrl.isNullOrEmpty()) {
            return kuwoSong?.coverUrl
        }
        // 最后使用识别结果封面
        return currentCoverUrl
    }

    /**
     * 检查并更新收藏状态
     */
    private suspend fun checkAndUpdateFavoriteStates() {
        kuwoSong?.let { song ->
            val isFavorite = favoriteRepository.isFavorite(song.id)
            adapter.updateFavoriteState(song, isFavorite)
        }
        neteaseSong?.let { song ->
            val isFavorite = favoriteRepository.isFavorite(song.id)
            adapter.updateFavoriteState(song, isFavorite)
        }
    }

    private fun playSong(song: Song) {
        lifecycleScope.launch {
            try {
                Toast.makeText(context, "正在加载: ${song.name}", Toast.LENGTH_SHORT).show()
                val platform = when (song.platform.uppercase()) {
                    "KUWO" -> MusicRepository.Platform.KUWO
                    else -> MusicRepository.Platform.NETEASE
                }
                playbackManager.playFromSearchFast(context, song, platform)
                Toast.makeText(context, "开始播放: ${song.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 切换收藏状态
     * 如果已收藏则取消收藏，如果未收藏则添加收藏
     */
    private fun toggleFavorite(song: Song, isCurrentlyFavorite: Boolean) {
        lifecycleScope.launch {
            try {
                if (isCurrentlyFavorite) {
                    // 取消收藏
                    Toast.makeText(context, "正在取消收藏...", Toast.LENGTH_SHORT).show()
                    favoriteRepository.removeFromFavorites(song.id)
                    adapter.updateFavoriteState(song, false)
                    Toast.makeText(context, "已取消收藏: ${song.name}", Toast.LENGTH_SHORT).show()
                } else {
                    // 添加收藏
                    Toast.makeText(context, "正在添加收藏...", Toast.LENGTH_SHORT).show()
                    val platform = song.platform.lowercase()
                    favoriteRepository.addToFavorites(song, platform)
                    adapter.updateFavoriteState(song, true)
                    Toast.makeText(context, "已添加到我喜欢: ${song.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadSong(song: Song) {
        // 检查是否已存在下载任务
        val existingTask = findExistingDownloadTask(song)
        if (existingTask != null) {
            Toast.makeText(context, "该歌曲已在下载列表中", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 显示音质选择对话框
        showQualitySelectionDialog(song)
    }

    /**
     * 查找是否已存在该歌曲的下载任务
     */
    private fun findExistingDownloadTask(song: Song): com.tacke.music.data.model.DownloadTask? {
        // 检查正在下载的任务
        val downloadingTask = downloadManager.downloadingTasks.value.find { task ->
            task.platform.equals(song.platform, ignoreCase = true) && 
            (task.fileName.contains(song.name, ignoreCase = true) ||
             task.songId == song.id)
        }
        if (downloadingTask != null) return downloadingTask
        
        // 检查已完成的任务
        return downloadManager.completedTasks.value.find { task ->
            task.platform.equals(song.platform, ignoreCase = true) && 
            (task.fileName.contains(song.name, ignoreCase = true) ||
             task.songId == song.id)
        }
    }

    /**
     * 显示音质选择对话框
     * 使用系统定义的音质档位
     */
    private fun showQualitySelectionDialog(song: Song) {
        val qualityOptions = SettingsActivity.PLAYBACK_QUALITY_OPTIONS
        val qualityLabels = qualityOptions.map { it.second }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle("选择下载音质")
            .setItems(qualityLabels) { _, which ->
                val selectedQuality = qualityOptions[which]
                startDownloadWithQuality(song, selectedQuality.first)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 使用指定音质开始下载
     */
    private fun startDownloadWithQuality(song: Song, quality: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(context, "正在获取歌曲信息...", Toast.LENGTH_SHORT).show()

                val platform = when (song.platform.uppercase()) {
                    "KUWO" -> MusicRepository.Platform.KUWO
                    else -> MusicRepository.Platform.NETEASE
                }
                val cachedRepository = CachedMusicRepository(context)
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongUrlWithCache(
                        platform = platform,
                        songId = song.id,
                        quality = quality,
                        songName = song.name,
                        artists = song.artists,
                        useCache = true,
                        coverUrlFromSearch = song.coverUrl
                    )
                }

                if (detail != null) {
                    val task = downloadManager.createDownloadTask(song, detail, quality, platform.name)
                    downloadManager.startDownload(task)
                    // 获取音质显示名称
                    val qualityName = SettingsActivity.PLAYBACK_QUALITY_OPTIONS
                        .find { it.first == quality }?.second ?: quality
                    Toast.makeText(context, "开始下载 [$qualityName]: ${song.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "获取歌曲信息失败，该音质可能不可用", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addToPlaylist(song: Song) {
        lifecycleScope.launch {
            try {
                Toast.makeText(context, "正在加载歌单...", Toast.LENGTH_SHORT).show()
                val playlists = playlistRepository.getAllPlaylistsSync()

                if (playlists.isEmpty()) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle("添加到歌单")
                        .setMessage("暂无歌单，是否创建新歌单？")
                        .setPositiveButton("创建") { _, _ ->
                            showCreatePlaylistDialog(song)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@launch
                }

                // 检查歌曲是否已在歌单中
                val playlistsWithStatus = mutableListOf<Pair<com.tacke.music.data.model.Playlist, Boolean>>()
                for (playlist in playlists) {
                    val isInPlaylist = playlistRepository.isSongInPlaylist(playlist.id, song.id)
                    playlistsWithStatus.add(Pair(playlist, isInPlaylist))
                }

                val playlistNames = Array(playlistsWithStatus.size) { index ->
                    val playlist = playlistsWithStatus[index].first
                    val isInPlaylist = playlistsWithStatus[index].second
                    if (isInPlaylist) "${playlist.name} (已添加)" else playlist.name
                }

                MaterialAlertDialogBuilder(context)
                    .setTitle("添加到歌单")
                    .setItems(playlistNames) { _, which ->
                        val (playlist, isInPlaylist) = playlistsWithStatus[which]
                        if (isInPlaylist) {
                            Toast.makeText(context, "该歌曲已在歌单 ${playlist.name} 中", Toast.LENGTH_SHORT).show()
                        } else {
                            addSongToPlaylist(playlist.id, song, playlist.name)
                        }
                    }
                    .setPositiveButton("新建歌单") { _, _ ->
                        showCreatePlaylistDialog(song)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(context, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreatePlaylistDialog(song: Song) {
        val editText = android.widget.EditText(context).apply {
            hint = "歌单名称"
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("新建歌单")
            .setView(editText)
            .setPositiveButton("创建") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        try {
                            Toast.makeText(context, "正在创建歌单...", Toast.LENGTH_SHORT).show()
                            val playlist = playlistRepository.createPlaylist(name)
                            Toast.makeText(context, "歌单创建成功", Toast.LENGTH_SHORT).show()
                            addSongToPlaylist(playlist.id, song, playlist.name)
                        } catch (e: Exception) {
                            Toast.makeText(context, "创建歌单失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(context, "歌单名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addSongToPlaylist(playlistId: String, song: Song, playlistName: String? = null) {
        lifecycleScope.launch {
            try {
                Toast.makeText(context, "正在添加到歌单...", Toast.LENGTH_SHORT).show()
                val platform = song.platform.lowercase()
                playlistRepository.addSongsToPlaylist(playlistId, listOf(song), platform)
                val displayName = playlistName ?: "歌单"
                Toast.makeText(context, "已添加到 $displayName: ${song.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun dismiss() {
        super.dismiss()
        onDismiss?.invoke()
    }
}
