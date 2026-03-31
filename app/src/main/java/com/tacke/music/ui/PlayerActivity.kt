package com.tacke.music.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.graphics.Bitmap
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tacke.music.R
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.SongDetail
import com.tacke.music.data.model.SongInfo
import com.tacke.music.data.preferences.PlaybackPreferences
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.LocalMusicInfoRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.data.repository.RecentPlayRepository
import com.tacke.music.databinding.ActivityPlayerBinding
import com.tacke.music.data.model.Song
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.service.FloatingLyricsService
import com.tacke.music.service.MusicPlaybackService
import com.tacke.music.ui.adapter.PlaylistDialogAdapter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import com.tacke.music.util.ImmersiveStatusBarHelper
import com.tacke.music.utils.CoverUrlResolver
import com.tacke.music.utils.CoverImageManager
import com.tacke.music.utils.LyricStyleSettings
import com.tacke.music.utils.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private val repository = MusicRepository()
    private var songDetail: SongDetail? = null
    private var currentQuality = "320k"
    private lateinit var platform: MusicRepository.Platform
    private var songId: String = ""
    private var songName: String = ""
    private var songArtists: String = ""
    private var songUrl: String = ""
    private var songCover: String? = null
    private var songLyrics: String? = null
    private var isTracking = false
    private var isSwitchingSong = false  // 标志：是否正在切换歌曲
    private var albumRotationAnimator: ObjectAnimator? = null
    private var parsedLyrics: List<Pair<Long, String>> = emptyList()
    private lateinit var gestureDetector: GestureDetector
    private lateinit var seekReceiver: BroadcastReceiver
    private lateinit var playbackControlReceiver: BroadcastReceiver
    private var loadingToast: Toast? = null
    private var pendingRestoreFromNotification = false
    private val lyricStylePrefs by lazy { getSharedPreferences(LyricStyleSettings.PREFS_NAME, Context.MODE_PRIVATE) }
    private val lyricStyleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (
            key == LyricStyleSettings.KEY_PLAYER_LYRIC_COLOR ||
            key == LyricStyleSettings.KEY_PLAYER_LYRIC_SIZE
        ) {
            runOnUiThread {
                applyPlayerLyricStyle()
                updateLyrics(exoPlayer?.currentPosition ?: 0L)
            }
        }
    }

    private lateinit var playlistManager: PlaylistManager
    private lateinit var playbackPreferences: PlaybackPreferences
    private lateinit var playbackManager: PlaybackManager
    private lateinit var recentPlayRepository: RecentPlayRepository
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playlistRepository: PlaylistRepository
    private var isFromEmptyState = false
    private var favoriteSidebarRefreshToken = 0
    private var waitingOverlayPermissionResult = false

    private fun isCurrentSongLocal(): Boolean {
        if (songId.startsWith("local_")) return true
        return playlistManager.getCurrentSong()?.platform.equals("LOCAL", ignoreCase = true)
    }

    private fun persistencePlatformName(): String {
        return if (isCurrentSongLocal()) "LOCAL" else platform.name
    }

    private fun isLocalPlaylistSong(song: PlaylistSong): Boolean {
        return song.platform.equals("LOCAL", ignoreCase = true) || song.id.startsWith("local_")
    }

    private fun preferNonBlank(newValue: String?, fallback: String?): String? {
        return if (newValue.isNullOrBlank()) fallback else newValue
    }

    private suspend fun resolveLocalSongDetail(
        localSongId: String,
        localSongName: String,
        localSongArtists: String,
        fallbackCoverUrl: String?
    ): SongDetail? {
        val persistedDetail = playbackPreferences.getSongDetail(localSongId)
        val localMusicInfoRepository = LocalMusicInfoRepository(this@PlayerActivity)
        val hash = localSongId.removePrefix("local_").toIntOrNull()
        val localMusic = withContext(Dispatchers.IO) {
            localMusicInfoRepository.getAllCachedMusic().firstOrNull { music ->
                (hash != null && music.path.hashCode() == hash) ||
                    (music.title == localSongName && music.artist == localSongArtists)
            }
        }

        // 本地扫描数据缺失时，优先回退到已缓存的播放详情，避免重进播放页丢封面/歌词
        if (localMusic == null) {
            return persistedDetail?.copy(
                info = SongInfo(name = localSongName, artist = localSongArtists),
                cover = persistedDetail.cover ?: fallbackCoverUrl,
                lyrics = persistedDetail.lyrics
            )
        }

        val cachedInfo = withContext(Dispatchers.IO) {
            localMusicInfoRepository.getCachedInfoByPath(localMusic.path)
        }
        val playUrl = localMusic.contentUri ?: localMusic.path
        val rawCover = cachedInfo?.coverUrl ?: persistedDetail?.cover ?: localMusic.coverUri ?: fallbackCoverUrl
        val cover = if (!rawCover.isNullOrBlank() && CoverUrlResolver.isRelativePath(rawCover)) {
            val sourcePlatform = cachedInfo?.source?.takeIf { it.isNotBlank() } ?: "KUWO"
            CoverUrlResolver.resolveCoverUrl(
                context = this@PlayerActivity,
                coverUrl = rawCover,
                songId = localSongId,
                platform = sourcePlatform,
                songName = localSongName,
                artist = localSongArtists
            ) ?: rawCover
        } else {
            rawCover
        }
        val lyrics = cachedInfo?.lyrics ?: persistedDetail?.lyrics
        return SongDetail(
            url = playUrl,
            info = SongInfo(name = localSongName, artist = localSongArtists),
            cover = cover,
            lyrics = lyrics
        )
    }

    // Service connection
    private var musicService: MusicPlaybackService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicPlaybackService.MusicBinder
            musicService = binder.getService()
            exoPlayer = binder.getPlayer()
            serviceBound = true
            setupPlayerListeners()

            if (pendingRestoreFromNotification ||
                intent.getStringExtra(EXTRA_LAUNCH_MODE) == LAUNCH_MODE_RESTORE
            ) {
                lifecycleScope.launch {
                    val restored = restoreFromCurrentPlayback()
                    if (!restored) {
                        syncCurrentPlaybackState()
                    }
                    pendingRestoreFromNotification = false
                }
                return
            }

            val currentMediaItem = exoPlayer?.currentMediaItem

            if (isFromEmptyState) {
                // 空状态进入时，如果服务正在播放，恢复当前播放状态
                if (currentMediaItem != null) {
                    lifecycleScope.launch {
                        restoreFromCurrentPlayback()
                    }
                } else {
                    lifecycleScope.launch {
                        // 尝试从播放列表恢复
                        val restored = restoreFromCurrentPlayback()
                        if (!restored) {
                            setupEmptyState()
                        }
                    }
                }
            } else if (intent.getStringExtra("song_id") != null) {
                val newSongId = intent.getStringExtra("song_id")
                val isSameSong = currentMediaItem?.mediaId == newSongId

                // 重置播放器状态，防止上一首歌曲的进度带到新歌曲
                isSwitchingSong = true
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                // 重置UI进度显示
                binding.seekBar.progress = 0
                binding.tvCurrentTime.text = "0:00"
                // 重置保存的播放进度为0，确保新歌曲从头开始播放
                playbackPreferences.currentPosition = 0L

                // 重新解析 Intent 以获取最新的歌曲信息
                parseIntent()

                // 无论是否是同一首歌曲，都重新加载并播放
                loadSongDetailButNotPlay(currentQuality)
            } else {
                updateUIForCurrentSong()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            exoPlayer = null
            serviceBound = false
        }
    }

    companion object {
        private const val TAG = "PlayerActivity"

        const val ACTION_PLAYBACK_UPDATE = "com.tacke.music.PLAYBACK_UPDATE"
        const val ACTION_SEEK_TO = "com.tacke.music.SEEK_TO"
        const val EXTRA_CURRENT_POSITION = "current_position"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_SEEK_POSITION = "seek_position"

        // 启动模式
        const val EXTRA_LAUNCH_MODE = "launch_mode"
        const val LAUNCH_MODE_NORMAL = "normal"
        const val LAUNCH_MODE_EMPTY = "empty"
        const val LAUNCH_MODE_RESTORE = "restore"

        // 正常启动（带歌曲信息）
        fun start(
            context: Context,
            songId: String,
            songName: String,
            songArtists: String,
            platform: MusicRepository.Platform,
            songUrl: String? = null,
            songCover: String? = null,
            songLyrics: String? = null
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra("song_id", songId)
                putExtra("song_name", songName)
                putExtra("song_artists", songArtists)
                putExtra("platform", platform.name)
                putExtra("song_url", songUrl)
                putExtra("song_cover", songCover)
                putExtra("song_lyrics", songLyrics)
                putExtra(EXTRA_LAUNCH_MODE, LAUNCH_MODE_NORMAL)
                // 添加 FLAG 确保能正确恢复已有实例
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }

        // 空状态启动（无歌曲）- 会尝试恢复当前播放状态
        fun startEmpty(context: Context) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_MODE, LAUNCH_MODE_EMPTY)
                // 添加 FLAG 确保能正确恢复已有实例
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }

        // 恢复播放状态启动（用于从通知或其他入口进入）
        fun startWithRestore(context: Context) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_MODE, LAUNCH_MODE_RESTORE)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableToolbarMarquee()

        // 设置沉浸式状态栏 - 透明状态栏，背景延伸到状态栏
        ImmersiveStatusBarHelper.setup(
            activity = this,
            lightStatusBar = false,
            lightNavigationBar = true
        )
        // 为顶部工具栏设置状态栏高度 padding
        ImmersiveStatusBarHelper.setStatusBarPadding(binding.toolbar)

        playlistManager = PlaylistManager.getInstance(this)
        playbackPreferences = PlaybackPreferences.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)
        recentPlayRepository = RecentPlayRepository(this)
        favoriteRepository = FavoriteRepository(this)
        playlistRepository = PlaylistRepository(this)

        parseIntent()
        setupClickListeners()
        setupAlbumRotation()
        applyCoverStyle() // 应用封面样式设置
        setupGestureDetector()
        setupSeekReceiver()
        setupPlaybackControlReceiver()
        setupPlayer()

        // 启动并绑定服务
        val serviceIntent = Intent(this, MusicPlaybackService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 加载播放列表并初始化播放模式图标
        lifecycleScope.launch {
            playlistManager.loadPlaylist()
            updatePlayModeIcon()

            // 如果是空状态启动，且服务已经在播放，稍后尝试恢复状态
            if (isFromEmptyState) {
                // 延迟一点等待服务绑定完成
                delay(100)
                if (exoPlayer?.currentMediaItem != null) {
                    restoreFromCurrentPlayback()
                }
            }
        }
    }

    private fun enableToolbarMarquee() {
        binding.tvSongName.isSelected = true
        binding.tvArtist.isSelected = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val newLaunchMode = intent.getStringExtra(EXTRA_LAUNCH_MODE) ?: LAUNCH_MODE_NORMAL
        val isNewEmptyState = newLaunchMode == LAUNCH_MODE_EMPTY
        val isRestoreMode = newLaunchMode == LAUNCH_MODE_RESTORE
        val newSongId = intent.getStringExtra("song_id")

        if (isRestoreMode) {
            pendingRestoreFromNotification = true
            if (serviceBound) {
                lifecycleScope.launch {
                    val restored = restoreFromCurrentPlayback()
                    if (!restored) {
                        syncCurrentPlaybackState()
                    }
                    pendingRestoreFromNotification = false
                }
            }
            return
        }

        // 如果是恢复模式或空状态，且播放器正在播放，直接更新UI即可
        if ((isNewEmptyState || isRestoreMode) && exoPlayer?.currentMediaItem != null) {
            // 从播放列表获取当前歌曲信息
            lifecycleScope.launch {
                restoreFromCurrentPlayback()
            }
            return
        }

        // 检查是否是新歌曲（从下载页面等入口播放的新歌曲）
        val isNewSong = newSongId != null && newSongId != songId

        parseIntent()

        lifecycleScope.launch {
            if (isFromEmptyState) {
                // 空状态启动时，尝试恢复播放状态
                val restored = restoreFromCurrentPlayback()
                if (!restored) {
                    setupEmptyState()
                }
            } else if (isNewSong) {
                // 新歌曲：立即重置播放器状态，防止上一首歌曲的进度带到新歌曲
                isSwitchingSong = true
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                // 重置UI进度显示
                binding.seekBar.progress = 0
                binding.tvCurrentTime.text = "0:00"
                // 重置保存的播放进度为0，确保新歌曲从头开始播放
                playbackPreferences.currentPosition = 0L

                // 重新解析 Intent 以获取最新的歌曲信息
                parseIntent()

                // 加载并播放新歌曲
                loadSongDetailButNotPlay(currentQuality)
            } else {
                // 同一首歌曲或没有指定歌曲：只更新UI，不重新加载
                updateUIForCurrentSong()
            }
        }
    }

    /**
     * 从当前播放状态恢复UI
     * @return 是否成功恢复
     */
    private suspend fun restoreFromCurrentPlayback(): Boolean {
        if (playlistManager.currentPlaylist.value.isEmpty()) {
            playlistManager.loadPlaylist()
        }

        val currentMediaItem = exoPlayer?.currentMediaItem
        val mediaSongId = currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }

        if (mediaSongId != null) {
            val mediaIndex = playlistManager.currentPlaylist.value.indexOfFirst { it.id == mediaSongId }
            if (mediaIndex >= 0) {
                playlistManager.setCurrentIndex(mediaIndex)
            }
        } else {
            val savedSongId = playbackPreferences.currentSongId
            if (!savedSongId.isNullOrBlank()) {
                val savedIndex = playlistManager.currentPlaylist.value.indexOfFirst { it.id == savedSongId }
                if (savedIndex >= 0) {
                    playlistManager.setCurrentIndex(savedIndex)
                }
            }
        }

        val currentSong = playlistManager.getCurrentSong()

        if (currentSong != null) {
            if (isLocalPlaylistSong(currentSong)) {
                val localDetail = resolveLocalSongDetail(
                    localSongId = currentSong.id,
                    localSongName = currentSong.name,
                    localSongArtists = currentSong.artists,
                    fallbackCoverUrl = currentSong.coverUrl
                )
                if (localDetail != null) {
                    songId = currentSong.id
                    songName = currentSong.name
                    songArtists = currentSong.artists
                    platform = MusicRepository.Platform.KUWO
                    currentQuality = playbackPreferences.currentQuality
                    isFromEmptyState = false
                    songDetail = localDetail
                    songLyrics = localDetail.lyrics
                    songCover = localDetail.cover ?: currentSong.coverUrl
                    binding.tvSongName.text = songName
                    binding.tvArtist.text = songArtists
                    updateUI(localDetail)
                    loadCoverAndBackground(songCover)
                    if (currentMediaItem == null || currentMediaItem.mediaId != currentSong.id) {
                        loadSongButNotPlay(localDetail.url, 0L, false)
                    }
                    if (songCover.isNullOrBlank() || songLyrics.isNullOrBlank()) {
                        loadMissingInfoInBackground()
                    }
                    updateUIForCurrentSong()
                    return true
                }
            }

            // 恢复歌曲信息
            songId = currentSong.id
            songName = currentSong.name
            songArtists = currentSong.artists
            platform = try {
                MusicRepository.Platform.valueOf(currentSong.platform.uppercase())
            } catch (e: Exception) {
                MusicRepository.Platform.KUWO
            }

            // 从 playbackPreferences 读取当前音质（用户设置的试听音质）
            currentQuality = playbackPreferences.currentQuality

            // 标记为非空状态
            isFromEmptyState = false

            // 更新UI（先显示基本信息）
            binding.tvSongName.text = songName
            binding.tvArtist.text = songArtists

            // 获取歌曲详情用于封面和歌词等
            var detail: SongDetail? = null
            try {
                detail = playbackPreferences.getSongDetail(songId)
                if (detail == null) {
                    // 使用带缓存的Repository获取歌曲详情
                    val cachedRepository = CachedMusicRepository(this@PlayerActivity)
                    detail = withContext(Dispatchers.IO) {
                        cachedRepository.getSongDetail(
                            platform = platform,
                            songId = songId,
                            quality = currentQuality,
                            songName = currentSong.name,
                            artists = currentSong.artists
                        )
                    }
                    // 如果获取到新详情，保存到缓存
                    if (detail != null) {
                        playbackPreferences.saveSongDetail(songId, detail)
                    }
                }
                if (detail != null) {
                    songDetail = detail
                    songLyrics = detail.lyrics
                    // 优先使用 detail 中的封面，如果没有则使用 currentSong 中的封面
                    songCover = detail.cover ?: currentSong.coverUrl
                    updateUI(detail)
                    
                    // 关键修复：如果播放器没有加载媒体项，但有歌曲详情，加载歌曲但不播放
                    if (currentMediaItem == null) {
                        loadSongButNotPlay(detail.url, 0, false)
                    }
                } else {
                    // 获取详情失败，使用 currentSong 中的封面
                    songCover = currentSong.coverUrl
                }
            } catch (e: Exception) {
                // 忽略错误，使用 currentSong 中的封面
                songCover = currentSong.coverUrl
                Log.e(TAG, "恢复播放状态时获取歌曲详情失败", e)
            }

            // 解析封面URL（处理酷我等平台的相对路径）
            val resolvedCoverUrl = withContext(Dispatchers.IO) {
                CoverUrlResolver.resolveCoverUrl(
                    this@PlayerActivity,
                    songCover,
                    songId,
                    platform.name
                )
            }
            songCover = resolvedCoverUrl ?: songCover

            // 加载封面
            loadCoverAndBackground(songCover)

            // 更新播放控制UI
            updateUIForCurrentSong()
            return true
        }
        return false
    }

    private fun setupPlayerListeners() {
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val duration = exoPlayer?.duration ?: 0
                    val currentPosition = exoPlayer?.currentPosition ?: 0
                    // 修复：确保duration是有效值（大于0且不是TIME_UNSET）
                    if (duration > 0 && duration != androidx.media3.common.C.TIME_UNSET) {
                        binding.seekBar.max = duration.toInt()
                        binding.tvTotalTime.text = formatTime(duration)
                    }
                    // 同步当前进度显示（仅在非切换歌曲状态下）
                    if (!isSwitchingSong) {
                        binding.seekBar.progress = currentPosition.toInt().coerceIn(0, binding.seekBar.max)
                        binding.tvCurrentTime.text = formatTime(currentPosition)
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    // 播放完成，根据播放模式处理
                    handlePlaybackEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton(isPlaying)
                if (isPlaying) {
                    startAlbumRotation()
                } else {
                    stopAlbumRotation()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 处理播放错误，特别是URL过期导致的403错误
                handlePlayerError(error)
            }
        })

        // 启动进度更新循环
        lifecycleScope.launch {
            while (true) {
                delay(100)
                exoPlayer?.let { player ->
                    // 如果正在切换歌曲，跳过UI更新
                    if (!isTracking && !isSwitchingSong) {
                        val currentPosition = player.currentPosition
                        val duration = player.duration
                        // 修复：确保position和duration都是有效值
                        if (duration > 0 && duration != androidx.media3.common.C.TIME_UNSET) {
                            // 如果seekBar.max还没设置或不对，先设置
                            if (binding.seekBar.max != duration.toInt()) {
                                binding.seekBar.max = duration.toInt()
                                binding.tvTotalTime.text = formatTime(duration)
                            }
                            // 确保progress在有效范围内
                            binding.seekBar.progress = currentPosition.toInt().coerceIn(0, binding.seekBar.max)
                            binding.tvCurrentTime.text = formatTime(currentPosition)
                            updateLyrics(currentPosition)
                        }
                    }
                    updatePlayPauseButton(player.isPlaying)
                    // 发送播放进度广播给歌词页面
                    sendPlaybackUpdate(player.currentPosition, player.duration, player.isPlaying)
                    // 发送播放进度给悬浮歌词
                    if (FloatingLyricsService.isRunning) {
                        sendFloatingLyricsUpdate(player.currentPosition, player.isPlaying)
                    }
                }
            }
        }
    }

    private fun handlePlaybackEnded() {
        // 根据播放模式决定下一首行为
        when (playlistManager.playMode.value) {
            PlaylistManager.PLAY_MODE_REPEAT_ONE -> {
                // 单曲循环：重新播放当前歌曲
                exoPlayer?.seekTo(0)
                exoPlayer?.play()
            }
            else -> {
                // 其他模式：播放下一首
                val nextSong = playlistManager.next()
                if (nextSong != null) {
                    lifecycleScope.launch {
                        loadAndPlaySong(nextSong)
                    }
                } else {
                    // 没有下一首（顺序播放到达末尾），重置到开始位置
                    exoPlayer?.seekTo(0)
                    updatePlayPauseButton(false)
                    stopAlbumRotation()
                }
            }
        }
    }

    private fun updateUIForCurrentSong() {
        // 更新UI为当前正在播放的歌曲状态
        songDetail?.let { detail ->
            updateUI(detail)
            // 更新歌词
            songLyrics = detail.lyrics
            detail.lyrics?.let { lyricsText ->
                parsedLyrics = parseLyrics(lyricsText)
            }
        }
        binding.tvSongName.text = songName
        binding.tvArtist.text = songArtists
        loadCoverAndBackground(songCover)
        // 修复：确保duration是有效值
        val duration = exoPlayer?.duration ?: 0
        if (duration > 0 && duration != androidx.media3.common.C.TIME_UNSET) {
            binding.seekBar.max = duration.toInt()
            binding.tvTotalTime.text = formatTime(duration)
        }
        // 同步当前进度
        val currentPosition = exoPlayer?.currentPosition ?: 0
        binding.seekBar.progress = currentPosition.toInt().coerceIn(0, binding.seekBar.max)
        binding.tvCurrentTime.text = formatTime(currentPosition)
        // 更新歌词显示
        updateLyrics(currentPosition)
        updatePlayPauseButton(exoPlayer?.isPlaying == true)
        refreshSidebarActionButtons()
        if (exoPlayer?.isPlaying == true) {
            startAlbumRotation()
        }
    }

    private var isRefreshingUrl = false

    private fun handlePlayerError(error: PlaybackException) {
        Log.e(TAG, "Player error: ${error.errorCodeName}", error)

        if (songId.startsWith("local_") || playlistManager.getCurrentSong()?.platform.equals("LOCAL", ignoreCase = true)) {
            Toast.makeText(this@PlayerActivity, "本地歌曲播放失败，请检查文件是否存在", Toast.LENGTH_LONG).show()
            return
        }

        // 检查是否是URL过期导致的错误（403 Forbidden）
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {

            // 避免重复刷新
            if (isRefreshingUrl) return
            isRefreshingUrl = true

            lifecycleScope.launch {
                try {
                    Toast.makeText(this@PlayerActivity, "正在刷新播放链接...", Toast.LENGTH_SHORT).show()

                    // 清除过期的缓存
                    playbackPreferences.clearSongDetail(songId)

                    // 重新获取歌曲详情
                    val cachedRepository = CachedMusicRepository(this@PlayerActivity)
                    val newDetail = withContext(Dispatchers.IO) {
                        cachedRepository.getSongDetail(
                            platform = platform,
                            songId = songId,
                            quality = currentQuality,
                            songName = songName,
                            artists = songArtists
                        )
                    }

                    if (newDetail != null) {
                        // 保存新的详情
                        playbackPreferences.saveSongDetail(songId, newDetail)
                        songDetail = newDetail

                        // 获取当前播放位置
                        val currentPosition = exoPlayer?.currentPosition ?: 0

                        // 使用新URL重新加载
                        loadSongButNotPlay(newDetail.url, currentPosition, true)

                        Toast.makeText(this@PlayerActivity, "播放链接已刷新", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@PlayerActivity, "无法获取播放链接", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh URL", e)
                    Toast.makeText(this@PlayerActivity, "刷新播放链接失败: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isRefreshingUrl = false
                }
            }
        } else {
            Toast.makeText(this@PlayerActivity, "播放错误: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun setupEmptyState() {
        // 显示空状态UI
        binding.tvSongName.text = "暂无歌曲"
        binding.tvArtist.text = "点击播放列表选择歌曲"
        binding.ivAlbum.setImageResource(R.drawable.ic_album_default)
        binding.ivBackground.visibility = View.GONE
        binding.tvLyricsCurrent.text = ""
        binding.tvLyricsNext.text = ""
        binding.tvCurrentTime.text = "0:00"
        binding.tvTotalTime.text = "0:00"
        binding.seekBar.progress = 0
        binding.seekBar.max = 0
        updatePlayPauseButton(false)
        refreshSidebarActionButtons()

        // 尝试恢复上次播放状态（播放列表已加载完成）
        // 关键修复：必须同时满足以下条件才恢复播放：
        // 1. 有保存的 songId
        // 2. 播放列表中有歌曲
        // 3. 保存的 songId 确实存在于当前播放列表中（确保是用户明确选择播放的歌曲）
        val savedSongId = playbackPreferences.currentSongId
        val currentPlaylist = playlistManager.currentPlaylist.value
        val isSavedSongInPlaylist = savedSongId != null && currentPlaylist.any { it.id == savedSongId }

        if (isSavedSongInPlaylist && playlistManager.getCurrentSong() != null) {
            // 有保存的状态且保存的歌曲确实存在于播放列表中，尝试恢复
            restorePlaybackState()
        } else if (currentPlaylist.isNotEmpty()) {
            // 播放列表有歌曲但没有当前歌曲（例如批量添加后），自动设置第一首为当前歌曲
            // 这样用户进入播放页后可以看到第一首歌的信息并可以播放
            playlistManager.setCurrentIndex(0)
            restorePlaybackState()
        } else {
            // 播放列表确实为空，重置isFromEmptyState标记
            isFromEmptyState = true
        }
        // 注意：批量添加的歌曲不会自动播放，用户需要点击播放按钮或从播放列表中选择歌曲
    }

    private suspend fun restorePlaybackState() {
        val currentSong = playlistManager.getCurrentSong()
        if (currentSong != null) {
            if (isLocalPlaylistSong(currentSong)) {
                val localDetail = resolveLocalSongDetail(
                    localSongId = currentSong.id,
                    localSongName = currentSong.name,
                    localSongArtists = currentSong.artists,
                    fallbackCoverUrl = currentSong.coverUrl
                )
                if (localDetail != null) {
                    songId = currentSong.id
                    songName = currentSong.name
                    songArtists = currentSong.artists
                    platform = MusicRepository.Platform.KUWO
                    currentQuality = playbackPreferences.currentQuality
                    binding.tvSongName.text = songName
                    binding.tvArtist.text = songArtists
                    songDetail = localDetail
                    songLyrics = localDetail.lyrics
                    songCover = localDetail.cover ?: currentSong.coverUrl
                    updateUI(localDetail)
                    updateLyrics(0)
                    val savedPosition = playbackPreferences.currentPosition
                    val wasPlaying = playbackPreferences.isPlaying
                    loadSongButNotPlay(localDetail.url, savedPosition, wasPlaying)
                    loadCoverAndBackground(songCover)
                    if (songCover.isNullOrBlank() || songLyrics.isNullOrBlank()) {
                        loadMissingInfoInBackground()
                    }
                    isFromEmptyState = false
                    return
                }
            }

            songId = currentSong.id
            songName = currentSong.name
            songArtists = currentSong.artists
            platform = try {
                MusicRepository.Platform.valueOf(currentSong.platform.uppercase())
            } catch (e: Exception) {
                MusicRepository.Platform.KUWO
            }
            currentQuality = playbackPreferences.currentQuality

            binding.tvSongName.text = songName
            binding.tvArtist.text = songArtists

            try {
                var detail = playbackPreferences.getSongDetail(songId)

                if (detail == null) {
                    // 使用带缓存的Repository获取歌曲详情
                    val cachedRepository = CachedMusicRepository(this@PlayerActivity)
                    detail = withContext(Dispatchers.IO) {
                        cachedRepository.getSongDetail(
                            platform = platform,
                            songId = songId,
                            quality = currentQuality,
                            songName = songName,
                            artists = songArtists
                        )
                    }
                    // 如果获取到新详情，更新缓存
                    if (detail != null) {
                        playbackPreferences.saveSongDetail(songId, detail)
                    }
                }

                if (detail != null) {
                    songDetail = detail
                    songLyrics = detail.lyrics
                    // 优先使用 detail 中的封面，如果没有则使用 currentSong 中的封面
                    songCover = detail.cover ?: currentSong.coverUrl
                    updateUI(detail)

                    // 关键修复：立即更新歌词显示，确保用户能看到歌词
                    updateLyrics(0)

                    val savedPosition = playbackPreferences.currentPosition
                    val wasPlaying = playbackPreferences.isPlaying

                    loadSongButNotPlay(detail.url, savedPosition, wasPlaying)

                    isFromEmptyState = false
                } else {
                    // 获取详情失败，使用 currentSong 中的封面
                    songCover = currentSong.coverUrl
                }
            } catch (e: Exception) {
                // 恢复播放失败，使用 currentSong 中的封面
                songCover = currentSong.coverUrl
                Toast.makeText(this@PlayerActivity, "恢复播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }

            // 解析封面URL（处理酷我等平台的相对路径）
            val resolvedCoverUrl = withContext(Dispatchers.IO) {
                CoverUrlResolver.resolveCoverUrl(
                    this@PlayerActivity,
                    songCover,
                    songId,
                    platform.name
                )
            }
            songCover = resolvedCoverUrl ?: songCover

            // 加载封面
            loadCoverAndBackground(songCover)
        }
    }

    private fun parseIntent() {
        val launchMode = intent.getStringExtra(EXTRA_LAUNCH_MODE) ?: LAUNCH_MODE_NORMAL
        isFromEmptyState = launchMode == LAUNCH_MODE_EMPTY

        if (!isFromEmptyState) {
            songId = intent.getStringExtra("song_id") ?: ""
            songName = intent.getStringExtra("song_name") ?: "未知"
            songArtists = intent.getStringExtra("song_artists") ?: "未知"
            songUrl = intent.getStringExtra("song_url") ?: ""
            songCover = intent.getStringExtra("song_cover")
            songLyrics = intent.getStringExtra("song_lyrics")
            val platformStr = intent.getStringExtra("platform") ?: "KUWO"
            platform = try {
                MusicRepository.Platform.valueOf(platformStr)
            } catch (e: Exception) {
                MusicRepository.Platform.KUWO
            }

            // 从 playbackPreferences 读取当前音质（用户设置的试听音质）
            currentQuality = playbackPreferences.currentQuality

            binding.tvSongName.text = songName
            binding.tvArtist.text = songArtists

            // 优先使用本地缓存的封面图片
            loadCoverWithCachePriority()

            // 解析歌词
            songLyrics?.let { lyricsText ->
                parsedLyrics = parseLyrics(lyricsText)
            }

            // 初始化音质显示
            updateQualityDisplay(currentQuality)

            // 如果歌词或封面为空，后台加载完整信息
            if (songLyrics.isNullOrBlank() || songCover.isNullOrBlank()) {
                loadMissingInfoInBackground()
            }
        }
    }

    /**
     * 优先使用本地缓存加载封面
     * 如果本地有缓存，直接使用本地图片
     * 如果没有本地缓存，使用传入的URL
     */
    private fun loadCoverWithCachePriority() {
        lifecycleScope.launch {
            // 1. 首先尝试从本地缓存获取封面图片
            val localCoverPath = withContext(Dispatchers.IO) {
                var path = CoverImageManager.getCoverPath(this@PlayerActivity, songId, platform.name)
                // 本地歌曲的封面缓存按 LOCAL 平台维度保存，补一层兜底读取
                if (path == null && songId.startsWith("local_")) {
                    path = CoverImageManager.getCoverPath(this@PlayerActivity, songId, "LOCAL")
                }
                path
            }

            if (localCoverPath != null) {
                // 本地有缓存，使用本地图片
                Log.d(TAG, "使用本地缓存封面: $localCoverPath")
                loadCoverAndBackground(localCoverPath)
                songCover = localCoverPath
            } else if (!songCover.isNullOrEmpty()) {
                // 本地没有缓存，使用传入的URL
                // 检查是否是相对路径（酷我平台）
                if (!songCover!!.startsWith("http") && !songCover!!.startsWith("/")) {
                    // 相对路径，需要解析
                    resolveAndLoadCover(songCover!!)
                } else {
                    loadCoverAndBackground(songCover)
                }
            } else {
                // 没有封面，使用默认
                loadCoverAndBackground(null)
            }
        }
    }

    /**
     * 解析相对路径封面URL并加载
     */
    private fun resolveAndLoadCover(relativeUrl: String) {
        lifecycleScope.launch {
            try {
                val resolvedUrl = withContext(Dispatchers.IO) {
                    CoverUrlResolver.resolveCoverUrl(
                        this@PlayerActivity,
                        relativeUrl,
                        songId,
                        platform.name,
                        songName,
                        songArtists
                    )
                }
                if (resolvedUrl != null) {
                    songCover = resolvedUrl
                    loadCoverAndBackground(resolvedUrl)
                } else {
                    loadCoverAndBackground(null)
                }
            } catch (e: Exception) {
                loadCoverAndBackground(null)
            }
        }
    }

    /**
     * 后台加载缺失的歌曲信息（歌词和封面）
     * 当从快速播放进入时，可能缺少歌词或封面，需要在后台加载
     */
    private fun loadMissingInfoInBackground() {
        lifecycleScope.launch {
            try {
                if (isCurrentSongLocal()) {
                    val localMusicInfoRepository = LocalMusicInfoRepository(this@PlayerActivity)
                    val hash = songId.removePrefix("local_").toIntOrNull()
                    val localMusic = withContext(Dispatchers.IO) {
                        localMusicInfoRepository.getAllCachedMusic().firstOrNull { music ->
                            (hash != null && music.path.hashCode() == hash) ||
                                (music.title == songName && music.artist == songArtists)
                        }
                    } ?: return@launch

                    val info = withContext(Dispatchers.IO) {
                        localMusicInfoRepository.getLocalMusicInfo(localMusic)
                    } ?: return@launch

                    if (!info.coverUrl.isNullOrBlank()) {
                        val finalCover = normalizeCoverUrl(info.coverUrl)
                        if (finalCover != songCover) {
                            songCover = finalCover
                            loadCoverAndBackground(finalCover)
                        }
                    }

                    if (!info.lyrics.isNullOrBlank()) {
                        songLyrics = info.lyrics
                        parsedLyrics = parseLyrics(info.lyrics)
                        updateLyrics(exoPlayer?.currentPosition ?: 0L)
                    }

                    val localDetail = SongDetail(
                        url = songUrl.ifEmpty { songDetail?.url ?: "" },
                        info = SongInfo(name = songName, artist = songArtists),
                        cover = songCover,
                        lyrics = songLyrics
                    )
                    songDetail = localDetail
                    playbackPreferences.saveSongDetail(songId, localDetail)
                    return@launch
                }
                Log.d(TAG, "后台加载缺失的歌曲信息: $songName")

                val cachedRepository = CachedMusicRepository(this@PlayerActivity)

                // 从网络获取完整信息（URL已经获取过了，这里主要是为了封面和歌词）
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongDetail(
                        platform = platform,
                        songId = songId,
                        quality = currentQuality,
                        coverUrlFromSearch = if (songCover.isNullOrEmpty()) null else songCover,
                        songName = songName,
                        artists = songArtists
                    )
                }

                if (detail != null) {
                    // 更新歌词
                    if (songLyrics.isNullOrBlank() && !detail.lyrics.isNullOrBlank()) {
                        songLyrics = detail.lyrics
                        parsedLyrics = parseLyrics(detail.lyrics)
                        Log.d(TAG, "歌词加载完成")
                    }

                    // 更新封面
                    if (!detail.cover.isNullOrBlank()) {
                        val newCover = detail.cover
                        if (newCover != songCover) {
                            songCover = newCover
                            loadCoverAndBackground(newCover)
                            Log.d(TAG, "封面加载完成: $newCover")

                            // 同时下载并缓存封面图片到本地
                            withContext(Dispatchers.IO) {
                                CoverImageManager.downloadAndCacheCover(
                                    context = this@PlayerActivity,
                                    songId = songId,
                                    platform = platform.name,
                                    quality = currentQuality,
                                    songName = songName,
                                    artist = songArtists
                                )
                            }
                        }
                    }

                    // 更新歌曲详情
                    songDetail = detail

                    // 保存到Preferences
                    playbackPreferences.saveSongDetail(songId, detail)
                }
            } catch (e: Exception) {
                Log.e(TAG, "后台加载歌曲信息失败: ${e.message}")
            }
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val diffX = e2.x - (e1?.x ?: 0f)
                val diffY = e2.y - (e1?.y ?: 0f)

                // 检测左滑进入歌词页
                if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY)) {
                    if (kotlin.math.abs(diffX) > SWIPE_THRESHOLD && kotlin.math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX < 0) {
                            // 左滑 - 进入歌词页
                            openLyricsPage()
                            return true
                        }
                    }
                }
                return false
            }
        })

        binding.root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun openLyricsPage() {
        // 检查是否有正在播放的歌曲
        if (isFromEmptyState || exoPlayer?.currentMediaItem == null) {
            Toast.makeText(this, "暂无正在播放的歌曲", Toast.LENGTH_SHORT).show()
            return
        }

        // 优先使用 songDetail 中的数据，如果没有则使用从 Intent 获取的数据
        val coverUrl = songDetail?.cover ?: songCover
        val lyricsText = songDetail?.lyrics ?: songLyrics
        val currentPosition = exoPlayer?.currentPosition ?: 0
        val duration = exoPlayer?.duration ?: 0
        val playing = exoPlayer?.isPlaying ?: false

        LyricsActivity.startForResult(
            this,
            songName,
            songArtists,
            coverUrl,
            lyricsText,
            currentPosition,
            duration,
            playing,
            songId,
            platform
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LyricsActivity.REQUEST_CODE && resultCode == RESULT_OK) {
            // 处理从歌词页返回的跳转请求
            data?.getLongExtra("seek_to", -1)?.let { seekTime ->
                if (seekTime >= 0) {
                    exoPlayer?.seekTo(seekTime)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun setupSeekReceiver() {
        seekReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    when (it.action) {
                        ACTION_SEEK_TO -> {
                            val seekPosition = it.getLongExtra(EXTRA_SEEK_POSITION, -1)
                            if (seekPosition >= 0) {
                                exoPlayer?.seekTo(seekPosition)
                            }
                            Unit
                        }
                        LyricsActivity.ACTION_REQUEST_PLAYBACK_STATUS -> {
                            // 响应歌词页面的状态请求
                            exoPlayer?.let { player ->
                                sendPlaybackUpdate(player.currentPosition, player.duration, player.isPlaying)
                            }
                            Unit
                        }
                        else -> {
                            // 其他广播，不处理
                            Unit
                        }
                    }
                }
            }
        }
    }

    private fun setupPlaybackControlReceiver() {
        playbackControlReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    MusicPlaybackService.ACTION_SONG_CHANGED -> {
                        // Service已切换歌曲，更新UI
                        val newSongId = intent.getStringExtra(MusicPlaybackService.EXTRA_SONG_ID)
                        val newSongName = intent.getStringExtra(MusicPlaybackService.EXTRA_SONG_NAME)
                        val newSongArtists = intent.getStringExtra(MusicPlaybackService.EXTRA_SONG_ARTISTS)
                        val newSongCover = intent.getStringExtra(MusicPlaybackService.EXTRA_SONG_COVER)
                        val newSongLyrics = intent.getStringExtra(MusicPlaybackService.EXTRA_SONG_LYRICS)
                        if (newSongId.isNullOrBlank()) {
                            return
                        }

                        val isSongChanged = newSongId != songId
                        val hasCoverIncrementalUpdate =
                            !newSongCover.isNullOrBlank() && newSongCover != songCover
                        val hasLyricsIncrementalUpdate =
                            !newSongLyrics.isNullOrBlank() && newSongLyrics != songLyrics

                        // 同一首歌曲的增量图词回填也需要刷新UI，不能只在songId变化时处理
                        if (!isSongChanged && !hasCoverIncrementalUpdate && !hasLyricsIncrementalUpdate) {
                            return
                        }

                        // 更新当前歌曲信息
                        songId = newSongId
                        songName = preferNonBlank(newSongName, songName) ?: songName
                        songArtists = preferNonBlank(newSongArtists, songArtists) ?: songArtists
                        songCover = preferNonBlank(newSongCover, songCover)
                        songLyrics = preferNonBlank(newSongLyrics, songLyrics)

                        // 更新UI
                        binding.tvSongName.text = songName
                        binding.tvArtist.text = songArtists
                        loadCoverAndBackground(songCover)

                        // 从Preferences获取歌曲详情
                        val detail = playbackPreferences.getSongDetail(songId)
                        if (detail != null) {
                            songDetail = detail
                            songLyrics = preferNonBlank(detail.lyrics, songLyrics)
                            songCover = preferNonBlank(detail.cover, songCover)
                            updateUI(detail)
                            loadCoverAndBackground(songCover)
                        }

                        // 本地歌曲切歌后如果图词仍缺失，触发后台补齐
                        if ((songCover.isNullOrBlank() || songLyrics.isNullOrBlank()) &&
                            (songId.startsWith("local_") || playlistManager.getCurrentSong()?.platform.equals("LOCAL", ignoreCase = true))
                        ) {
                            loadMissingInfoInBackground()
                        }
                    }
                    MusicPlaybackService.ACTION_PLAY_MODE_CHANGED -> {
                        val incomingMode = intent.getIntExtra(
                            MusicPlaybackService.EXTRA_PLAY_MODE,
                            playlistManager.playMode.value
                        )
                        playlistManager.setPlayMode(incomingMode)
                        updatePlayModeIcon()
                    }
                    FloatingLyricsService.ACTION_STATE_CHANGED -> {
                        val running = intent.getBooleanExtra(
                            FloatingLyricsService.EXTRA_RUNNING,
                            FloatingLyricsService.isRunning
                        )
                        refreshSidebarActionButtons(running)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ACTION_SEEK_TO)
            addAction(LyricsActivity.ACTION_REQUEST_PLAYBACK_STATUS)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(seekReceiver, filter)
        lyricStylePrefs.registerOnSharedPreferenceChangeListener(lyricStyleListener)
        applyPlayerLyricStyle()

        // 注册歌曲变更广播接收器（用于Service切歌后更新UI）
        val controlFilter = IntentFilter().apply {
            addAction(MusicPlaybackService.ACTION_SONG_CHANGED)
            addAction(MusicPlaybackService.ACTION_PLAY_MODE_CHANGED)
            addAction(FloatingLyricsService.ACTION_STATE_CHANGED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(playbackControlReceiver, controlFilter)

        // 从后台恢复时，同步当前播放的歌曲信息
        if (waitingOverlayPermissionResult) {
            handleOverlayPermissionResult()
        }
        syncCurrentPlaybackState()
        refreshSidebarActionButtons()

        // 每次返回页面时应用最新的封面样式设置
        applyCoverStyle()
    }

    /**
     * 同步当前播放状态
     * 用于从后台恢复时检查歌曲是否已在后台被切换
     */
    private fun syncCurrentPlaybackState() {
        lifecycleScope.launch {
            // 获取播放列表中的当前歌曲
            val currentPlaylistSong = playlistManager.getCurrentSong()
            val currentMediaItem = exoPlayer?.currentMediaItem

            // 检查是否需要更新UI：
            // 1. 播放列表中的歌曲与Activity中保存的歌曲不一致
            // 2. 或者播放器当前播放的歌曲与Activity中保存的歌曲不一致
            val needUpdate = currentPlaylistSong != null &&
                    (currentPlaylistSong.id != songId || currentMediaItem?.mediaId != songId)

            // 如果播放列表中的歌曲与Activity中保存的歌曲不一致，说明在后台已被切换
            if (needUpdate) {
                // 更新歌曲信息
                songId = currentPlaylistSong.id
                songName = currentPlaylistSong.name
                songArtists = currentPlaylistSong.artists
                songCover = preferNonBlank(currentPlaylistSong.coverUrl, songCover)
                platform = try {
                    MusicRepository.Platform.valueOf(currentPlaylistSong.platform.uppercase())
                } catch (e: Exception) {
                    MusicRepository.Platform.KUWO
                }

                // 标记为非空状态
                isFromEmptyState = false

                // 更新UI
                binding.tvSongName.text = songName
                binding.tvArtist.text = songArtists

                // LOCAL 歌曲在同步时优先走本地详情，避免被空的 playlist cover/lyrics 覆盖
                if (isLocalPlaylistSong(currentPlaylistSong)) {
                    val localDetail = resolveLocalSongDetail(
                        localSongId = currentPlaylistSong.id,
                        localSongName = currentPlaylistSong.name,
                        localSongArtists = currentPlaylistSong.artists,
                        fallbackCoverUrl = currentPlaylistSong.coverUrl
                    )
                    if (localDetail != null) {
                        songDetail = localDetail
                        songLyrics = preferNonBlank(localDetail.lyrics, songLyrics)
                        songCover = preferNonBlank(localDetail.cover, songCover)
                        updateUI(localDetail)
                        loadCoverAndBackground(songCover)
                        if (songCover.isNullOrBlank() || songLyrics.isNullOrBlank()) {
                            loadMissingInfoInBackground()
                        }
                        updateUIForCurrentSong()
                        return@launch
                    }
                }

                // 尝试从Preferences获取歌曲详情
                val detail = playbackPreferences.getSongDetail(songId)
                if (detail != null) {
                    songDetail = detail
                    songLyrics = preferNonBlank(detail.lyrics, songLyrics)
                    songCover = preferNonBlank(detail.cover, songCover)
                    updateUI(detail)
                } else {
                    // 如果Preferences中没有，尝试重新获取
                    try {
                        // 使用带缓存的Repository获取歌曲详情
                        val cachedRepository = CachedMusicRepository(this@PlayerActivity)
                        val newDetail = withContext(Dispatchers.IO) {
                            cachedRepository.getSongDetail(
                                platform = platform,
                                songId = songId,
                                quality = currentQuality,
                                songName = songName,
                                artists = songArtists
                            )
                        }
                        if (newDetail != null) {
                            songDetail = newDetail
                            songLyrics = preferNonBlank(newDetail.lyrics, songLyrics)
                            songCover = preferNonBlank(newDetail.cover, songCover)
                            updateUI(newDetail)
                        }
                    } catch (e: Exception) {
                        // 忽略错误
                    }
                }

                // 解析封面URL（处理酷我等平台的相对路径）
                val resolvedCoverUrl = withContext(Dispatchers.IO) {
                    CoverUrlResolver.resolveCoverUrl(
                        this@PlayerActivity,
                        songCover,
                        songId,
                        platform.name
                    )
                }
                songCover = resolvedCoverUrl ?: songCover
                loadCoverAndBackground(songCover)

                // 更新播放控制UI
                updateUIForCurrentSong()
            } else if (currentPlaylistSong != null && currentPlaylistSong.id == songId) {
                // 歌曲相同，但仍需要更新UI以确保显示正确
                // 这种情况可能发生在从下载页面播放同一首歌，但Activity被重建后
                updateUIForCurrentSong()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(seekReceiver)
        lyricStylePrefs.unregisterOnSharedPreferenceChangeListener(lyricStyleListener)
        // 注销播放控制广播接收器
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(playbackControlReceiver)
        } catch (e: Exception) {
            // 忽略未注册的错误
        }
        // 保存播放进度
        savePlaybackState()
    }

    private fun savePlaybackState() {
        exoPlayer?.let { player ->
            // 使用统一的播放管理器更新播放进度
            playbackManager.updatePlaybackPosition(player.currentPosition)
            playbackPreferences.isPlaying = player.isPlaying
        }
        if (!isFromEmptyState) {
            playbackPreferences.currentSongId = songId
            playbackPreferences.currentQuality = currentQuality
        }
    }

    private fun loadCoverAndBackground(coverUrl: String?) {
        val normalizedCoverUrl = normalizeCoverUrl(coverUrl)
        if (!normalizedCoverUrl.isNullOrEmpty()) {
            val coverTargetSongId = songId
            val coverTargetSongName = songName
            val coverTargetArtist = songArtists

            // 加载专辑封面 - 强制刷新以确保显示最新图片
            Glide.with(this)
                .load(normalizedCoverUrl)
                .placeholder(R.drawable.ic_album_default)
                .error(R.drawable.ic_album_default)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(binding.ivAlbum)

            // 加载背景图片 - 强制刷新以确保显示最新图片
            Glide.with(this)
                .load(normalizedCoverUrl)
                .placeholder(R.drawable.bg_default_light_cyan)
                .error(R.drawable.bg_default_light_cyan)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(binding.ivBackground)

            // 显示背景图片
            binding.ivBackground.visibility = View.VISIBLE

            // 同步把封面回传给通知栏，避免首播时只显示默认背景
            Glide.with(this)
                .asBitmap()
                .load(normalizedCoverUrl)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        if (songId == coverTargetSongId) {
                            musicService?.updateMediaMetadata(coverTargetSongName, coverTargetArtist, resource)
                        }
                    }

                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                    }
                })

            // LOCAL 场景兜底：远程图先落本地文件，再用文件路径稳定显示
            if (isCurrentSongLocal() && normalizedCoverUrl.startsWith("http", ignoreCase = true)) {
                lifecycleScope.launch {
                    val localPath = withContext(Dispatchers.IO) {
                        CoverImageManager.downloadAndCacheCoverByUrl(
                            context = this@PlayerActivity,
                            songId = songId,
                            platform = persistencePlatformName(),
                            coverUrl = normalizedCoverUrl
                        )
                    }
                    if (!localPath.isNullOrBlank()) {
                        val file = File(localPath)
                        if (file.exists()) {
                            Glide.with(this@PlayerActivity)
                                .load(file)
                                .placeholder(R.drawable.ic_album_default)
                                .error(R.drawable.ic_album_default)
                                .into(binding.ivAlbum)
                            Glide.with(this@PlayerActivity)
                                .load(file)
                                .placeholder(R.drawable.bg_default_light_cyan)
                                .error(R.drawable.bg_default_light_cyan)
                                .into(binding.ivBackground)
                            binding.ivBackground.visibility = View.VISIBLE
                            if (songId == coverTargetSongId) {
                                musicService?.updateMediaMetadata(
                                    coverTargetSongName,
                                    coverTargetArtist,
                                    BitmapFactory.decodeFile(file.absolutePath)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // 没有封面时隐藏背景
            binding.ivAlbum.setImageResource(R.drawable.ic_album_default)
            binding.ivBackground.visibility = View.GONE
        }
    }

    private fun normalizeCoverUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) return trimmed
        return if (trimmed.contains("music.126.net", ignoreCase = true)) {
            trimmed.replaceFirst("http://", "https://", ignoreCase = true)
        } else {
            trimmed
        }
    }

    private fun setupPlayer() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isTracking = false
                exoPlayer?.seekTo(binding.seekBar.progress.toLong())
            }
        })
    }

    private fun setupAlbumRotation() {
        albumRotationAnimator = ObjectAnimator.ofFloat(binding.albumContainer, View.ROTATION, 0f, 360f).apply {
            duration = 20000 // 20秒一圈
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
    }

    private fun startAlbumRotation() {
        // 只有旋转圆形样式才启动旋转动画
        if (PlayerCoverSettingsActivity.getCoverStyle(this) == PlayerCoverSettingsActivity.COVER_STYLE_ROTATING_CIRCLE) {
            albumRotationAnimator?.start()
        }
    }

    private fun stopAlbumRotation() {
        albumRotationAnimator?.pause()
    }

    /**
     * 应用封面样式设置
     */
    private fun applyCoverStyle() {
        val coverStyle = PlayerCoverSettingsActivity.getCoverStyle(this)

        when (coverStyle) {
            PlayerCoverSettingsActivity.COVER_STYLE_ROTATING_CIRCLE -> {
                // 圆形样式 - 设置大圆角
                applyCircularCoverStyle()
            }
            PlayerCoverSettingsActivity.COVER_STYLE_STATIC_SQUARE -> {
                // 正方形样式 - 设置小圆角
                applySquareCoverStyle()
            }
        }
    }

    /**
     * 应用圆形封面样式
     */
    private fun applyCircularCoverStyle() {
        // 设置CardView为圆形 - 使用post确保视图已测量
        binding.albumCardView.post {
            val size = minOf(binding.albumCardView.width, binding.albumCardView.height)
            binding.albumCardView.radius = size / 2f // 使用实际尺寸的一半作为圆角半径
        }
        // 重置旋转角度
        binding.albumContainer.rotation = 0f
    }

    /**
     * 应用正方形封面样式
     */
    private fun applySquareCoverStyle() {
        // 停止旋转动画
        stopAlbumRotation()
        // 设置CardView为小圆角（正方形）
        binding.albumCardView.radius = 16f // 小圆角，形成正方形
        // 重置旋转角度
        binding.albumContainer.rotation = 0f
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnPlayPause.setOnClickListener {
            // 检查播放列表是否实际为空
            val currentSong = playlistManager.getCurrentSong()

            // 关键修复：处理批量添加歌曲后的播放逻辑
            // 1. 如果isFromEmptyState为true但播放列表有歌曲，自动加载第一首
            // 2. 如果播放器没有加载媒体项，尝试恢复并播放
            if (isFromEmptyState || exoPlayer?.currentMediaItem == null) {
                if (currentSong != null) {
                    // 播放列表有歌曲但播放器未加载，尝试加载并播放
                    lifecycleScope.launch {
                        val restored = restoreFromCurrentPlayback()
                        if (restored) {
                            // 标记为非空状态，这样后续操作可以正常进行
                            isFromEmptyState = false
                            exoPlayer?.play()
                            startAlbumRotation()
                            refreshSidebarActionButtons()
                        } else {
                            Toast.makeText(this@PlayerActivity, "加载歌曲失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    // 播放列表确实为空，提示用户
                    Toast.makeText(this, "播放列表为空，请先添加歌曲", Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    stopAlbumRotation()
                } else {
                    // 检查播放状态，如果已结束则重新播放或播放下一首
                    if (player.playbackState == Player.STATE_ENDED) {
                        handlePlaybackEnded()
                    } else {
                        player.play()
                        startAlbumRotation()
                    }
                }
            }
        }

        binding.btnPrevious.setOnClickListener {
            // 检查播放列表是否有歌曲
            val currentSong = playlistManager.getCurrentSong()
            if (currentSong == null) {
                Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 如果处于空状态但播放列表有歌曲，先恢复正常状态
            if (isFromEmptyState) {
                isFromEmptyState = false
            }
            playPreviousSong()
        }

        binding.btnNext.setOnClickListener {
            // 检查播放列表是否有歌曲
            val currentSong = playlistManager.getCurrentSong()
            if (currentSong == null) {
                Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 如果处于空状态但播放列表有歌曲，先恢复正常状态
            if (isFromEmptyState) {
                isFromEmptyState = false
            }
            playNextSong()
        }

        binding.btnShuffle.setOnClickListener {
            val newMode = playlistManager.togglePlayMode()
            updatePlayModeIcon()
            refreshPlaybackNotification()
            val modeName = playlistManager.getPlayModeName()
            Toast.makeText(this, modeName, Toast.LENGTH_SHORT).show()
        }

        binding.btnPlaylist.setOnClickListener {
            showPlaylistDialog()
        }

        binding.btnFavoriteSidebar.setOnClickListener {
            if (songId.isBlank()) {
                Toast.makeText(this, "暂无歌曲", Toast.LENGTH_SHORT).show()
            } else {
                toggleFavorite()
            }
        }

        binding.btnFloatingLyricsSidebar.setOnClickListener {
            if (songId.isBlank()) {
                Toast.makeText(this, "暂无歌曲", Toast.LENGTH_SHORT).show()
            } else {
                toggleFloatingLyrics()
            }
        }

        binding.btnMore.setOnClickListener {
            if (isFromEmptyState) {
                Toast.makeText(this, "暂无歌曲", Toast.LENGTH_SHORT).show()
            } else {
                showMoreOptions()
            }
        }

        refreshSidebarActionButtons()
    }

    private fun playNextSong() {
        val nextSong = playlistManager.next()
        if (nextSong != null) {
            lifecycleScope.launch {
                loadAndPlaySong(nextSong)
            }
        } else {
            Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playPreviousSong() {
        val prevSong = playlistManager.previous()
        if (prevSong != null) {
            lifecycleScope.launch {
                loadAndPlaySong(prevSong)
            }
        } else {
            Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun loadAndPlaySong(playlistSong: PlaylistSong) {
        try {
            // 设置切换歌曲标志，暂停UI更新
            isSwitchingSong = true

            // 更新当前歌曲信息
            songId = playlistSong.id
            songName = playlistSong.name
            songArtists = playlistSong.artists
            platform = try {
                MusicRepository.Platform.valueOf(playlistSong.platform.uppercase())
            } catch (e: Exception) {
                MusicRepository.Platform.KUWO
            }

            // 标记为非空状态，这样播放控件才能正常使用
            isFromEmptyState = false

            // 重置播放进度，确保新歌曲从头开始播放
            playbackPreferences.currentPosition = 0L

            // 立即重置播放器状态和UI
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            binding.seekBar.progress = 0
            binding.tvCurrentTime.text = "0:00"

            // 更新UI（先不加载封面，等获取到详情后再加载）
            binding.tvSongName.text = songName
            binding.tvArtist.text = songArtists
            
            // 通知悬浮歌词歌曲即将切换
            if (FloatingLyricsService.isRunning) {
                val tempIntent = Intent(FloatingLyricsService.ACTION_SONG_CHANGED).apply {
                    putExtra(FloatingLyricsService.EXTRA_SONG_ID, songId)
                    putExtra(FloatingLyricsService.EXTRA_SONG_NAME, songName)
                    putExtra(FloatingLyricsService.EXTRA_SONG_ARTISTS, songArtists)
                    putExtra(FloatingLyricsService.EXTRA_LYRICS, "") // 临时清空歌词
                }
                LocalBroadcastManager.getInstance(this@PlayerActivity).sendBroadcast(tempIntent)
            }

            // LOCAL 歌曲：优先本地文件播放，并从本地缓存读取封面/歌词
            if (playlistSong.platform.equals("LOCAL", ignoreCase = true) || songId.startsWith("local_")) {
                Log.d(TAG, "loadAndPlaySong 命中 LOCAL 分支: songId=$songId, name=$songName")
                val localMusicInfoRepository = LocalMusicInfoRepository(this@PlayerActivity)
                val hash = songId.removePrefix("local_").toIntOrNull()
                val localMusic = withContext(Dispatchers.IO) {
                    localMusicInfoRepository.getAllCachedMusic().firstOrNull { music ->
                        (hash != null && music.path.hashCode() == hash) ||
                            (music.title == songName && music.artist == songArtists)
                    }
                }

                if (localMusic != null) {
                    val cachedInfo = withContext(Dispatchers.IO) {
                        localMusicInfoRepository.getCachedInfoByPath(localMusic.path)
                    }
                    Log.d(
                        TAG,
                        "LOCAL 缓存命中: cover=${!cachedInfo?.coverUrl.isNullOrBlank()}, lyrics=${!cachedInfo?.lyrics.isNullOrBlank()}"
                    )
                    val playUrl = localMusic.contentUri ?: localMusic.path

                    songCover = cachedInfo?.coverUrl ?: localMusic.coverUri ?: playlistSong.coverUrl
                    songLyrics = cachedInfo?.lyrics
                    songDetail = SongDetail(
                        url = playUrl,
                        info = SongInfo(name = songName, artist = songArtists),
                        cover = songCover,
                        lyrics = songLyrics
                    )

                    loadCoverWithCachePriority()
                    songDetail?.let { updateUI(it) }
                    playSong(playUrl)

                    if (songCover.isNullOrBlank() || songLyrics.isNullOrBlank()) {
                        Log.d(TAG, "LOCAL 信息不完整，触发后台补齐: $songName")
                        loadMissingInfoInBackground()
                    }
                    return
                }
                Log.w(TAG, "LOCAL 分支未匹配到本地歌曲记录，回退到通用播放链路: $songName")
            }

            // 检查本地文件是否存在
            val downloadManager = com.tacke.music.download.DownloadManager.getInstance(this@PlayerActivity)
            val localPath = withContext(Dispatchers.IO) {
                downloadManager.getLocalSongPath(songId)
            }

            // 如果本地文件存在，直接播放本地文件
            if (localPath != null) {
                val cachedRepository = CachedMusicRepository(this@PlayerActivity)
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongDetail(
                        platform = platform,
                        songId = songId,
                        quality = currentQuality,
                        songName = songName,
                        artists = songArtists
                    )
                }

                if (detail != null) {
                    songDetail = detail
                    songLyrics = detail.lyrics
                    songCover = detail.cover ?: playlistSong.coverUrl
                } else {
                    songCover = playlistSong.coverUrl
                }

                // 解析封面URL
                val resolvedCoverUrl = withContext(Dispatchers.IO) {
                    CoverUrlResolver.resolveCoverUrl(
                        this@PlayerActivity,
                        songCover,
                        songId,
                        platform.name
                    )
                }
                songCover = resolvedCoverUrl ?: songCover
                loadCoverAndBackground(songCover)
                detail?.let { updateUI(it) }
                playSong(localPath)
                return
            }

            // 非下载管理页面且本地文件不存在，强制重新获取最新URL
            val cachedRepository = CachedMusicRepository(this@PlayerActivity)
            val detail = withContext(Dispatchers.IO) {
                cachedRepository.getSongUrlWithCache(
                    platform = platform,
                    songId = songId,
                    quality = currentQuality,
                    songName = songName,
                    artists = songArtists,
                    useCache = true
                )
            }

            if (detail != null) {
                songDetail = detail
                songLyrics = detail.lyrics
                // 优先使用 detail 中的封面，如果没有则使用 playlistSong 中的封面
                songCover = detail.cover ?: playlistSong.coverUrl

                // 解析封面URL（处理酷我等平台的相对路径）
                val resolvedCoverUrl = withContext(Dispatchers.IO) {
                    CoverUrlResolver.resolveCoverUrl(
                        this@PlayerActivity,
                        songCover,
                        songId,
                        platform.name
                    )
                }
                songCover = resolvedCoverUrl ?: songCover
                loadCoverAndBackground(songCover)
                updateUI(detail)
                playSong(detail.url)
            } else {
                // 获取详情失败，使用 playlistSong 中的封面
                songCover = playlistSong.coverUrl

                // 解析封面URL（处理酷我等平台的相对路径）
                val resolvedCoverUrl = withContext(Dispatchers.IO) {
                    CoverUrlResolver.resolveCoverUrl(
                        this@PlayerActivity,
                        songCover,
                        songId,
                        platform.name
                    )
                }
                songCover = resolvedCoverUrl ?: songCover
                loadCoverAndBackground(songCover)
                Toast.makeText(this@PlayerActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                isSwitchingSong = false  // 重置标志
            }
        } catch (e: Exception) {
            Toast.makeText(this@PlayerActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            isSwitchingSong = false  // 重置标志
        }
    }

    /**
     * 从播放列表播放歌曲（直接在当前Activity内切换，不启动新Activity）
     * 关键修复：避免启动新Activity导致的闪退和多实例问题
     */
    private suspend fun playSongFromPlaylist(playlistSong: PlaylistSong) {
        try {
            // 直接在当前Activity内切换歌曲，不启动新Activity
            loadAndPlaySong(playlistSong)
        } catch (e: Exception) {
            Toast.makeText(this@PlayerActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private var playlistDialog: BottomSheetDialog? = null
    private var playlistAdapter: PlaylistDialogAdapter? = null

    private fun showPlaylistDialog() {
        lifecycleScope.launch {
            // 先加载最新的播放列表
            playlistManager.loadPlaylist()

            val playlist = playlistManager.currentPlaylist.value

            // 创建 BottomSheetDialog
            val dialog = BottomSheetDialog(this@PlayerActivity, R.style.BottomSheetDialogTheme)
            playlistDialog = dialog

            // 加载布局
            val dialogView = layoutInflater.inflate(R.layout.dialog_playlist, null)
            dialog.setContentView(dialogView)

            // 获取视图引用
            val tvCount = dialogView.findViewById<android.widget.TextView>(R.id.tvCount)
            val btnClear = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClear)
            val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
            val layoutPlayMode = dialogView.findViewById<android.widget.LinearLayout>(R.id.layoutPlayMode)
            val ivPlayMode = dialogView.findViewById<android.widget.ImageView>(R.id.ivPlayMode)
            val tvPlayMode = dialogView.findViewById<android.widget.TextView>(R.id.tvPlayMode)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerView)
            val layoutEmpty = dialogView.findViewById<android.widget.LinearLayout>(R.id.layoutEmpty)

            // 更新歌曲数量
            tvCount.text = "${playlist.size}首"

            // 更新播放模式显示
            fun updatePlayModeDisplay() {
                val playMode = playlistManager.playMode.value
                val (iconRes, modeName) = when (playMode) {
                    PlaylistManager.PLAY_MODE_SEQUENTIAL -> R.drawable.ic_sequential to "顺序播放"
                    PlaylistManager.PLAY_MODE_SHUFFLE -> R.drawable.ic_shuffle to "随机播放"
                    PlaylistManager.PLAY_MODE_REPEAT_LIST -> R.drawable.ic_repeat to "列表循环"
                    PlaylistManager.PLAY_MODE_REPEAT_ONE -> R.drawable.ic_repeat_one to "单曲循环"
                    else -> R.drawable.ic_sequential to "顺序播放"
                }
                ivPlayMode.setImageResource(iconRes)
                tvPlayMode.text = modeName
            }
            updatePlayModeDisplay()

            // 播放模式点击切换
            layoutPlayMode.setOnClickListener {
                val newMode = playlistManager.togglePlayMode()
                updatePlayModeDisplay()
                updatePlayModeIcon()
                refreshPlaybackNotification()
                val modeName = playlistManager.getPlayModeName()
                Toast.makeText(this@PlayerActivity, modeName, Toast.LENGTH_SHORT).show()
            }

            // 关闭按钮
            btnClose.setOnClickListener {
                dialog.dismiss()
            }

            // 清空按钮
            btnClear.setOnClickListener {
                if (playlist.isEmpty()) {
                    Toast.makeText(this@PlayerActivity, "播放列表已为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("清空播放列表")
                    .setMessage("确定要清空播放列表吗？")
                    .setPositiveButton("确定") { _, _ ->
                        lifecycleScope.launch {
                            playlistManager.clearPlaylist()
                            playlistAdapter?.submitList(emptyList())
                            tvCount.text = "0首"
                            layoutEmpty.visibility = android.view.View.VISIBLE
                            recyclerView.visibility = android.view.View.GONE
                            Toast.makeText(this@PlayerActivity, "播放列表已清空", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            // 设置 RecyclerView
            if (playlist.isEmpty()) {
                layoutEmpty.visibility = android.view.View.VISIBLE
                recyclerView.visibility = android.view.View.GONE
            } else {
                layoutEmpty.visibility = android.view.View.GONE
                recyclerView.visibility = android.view.View.VISIBLE

                recyclerView.layoutManager = LinearLayoutManager(this@PlayerActivity)
                val currentIndex = playlistManager.currentIndex.value

                playlistAdapter = PlaylistDialogAdapter(
                    currentPlayingIndex = currentIndex,
                    onItemClick = { position ->
                        lifecycleScope.launch {
                            playlistManager.setCurrentIndex(position)
                            val song = playlistManager.getCurrentSong()
                            if (song != null) {
                                // 使用统一播放管理器播放歌曲
                                playSongFromPlaylist(song)
                                dialog.dismiss()
                            }
                        }
                    },
                    onRemoveClick = { position, song ->
                        lifecycleScope.launch {
                            playlistManager.removeSong(song.id)
                            val newPlaylist = playlistManager.currentPlaylist.value
                            playlistAdapter?.submitList(newPlaylist)
                            tvCount.text = "${newPlaylist.size}首"

                            if (newPlaylist.isEmpty()) {
                                layoutEmpty.visibility = android.view.View.VISIBLE
                                recyclerView.visibility = android.view.View.GONE
                            }

                            Toast.makeText(this@PlayerActivity, "已移除: ${song.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                recyclerView.adapter = playlistAdapter
                playlistAdapter?.submitList(playlist)

                // 滚动到当前播放的歌曲
                if (currentIndex in playlist.indices) {
                    recyclerView.scrollToPosition(currentIndex)
                }
            }

            dialog.show()
        }
    }

    private fun showMoreOptions() {
        lifecycleScope.launch {
            val options = arrayOf("下载", "选择音质", "添加到歌单")

            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("更多选项")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showQualityDialogForDownload()
                        1 -> showQualityDialog()
                        2 -> showAddToPlaylistDialog()
                    }
                }
                .show()
        }
    }

    private fun toggleFloatingLyrics() {
        if (FloatingLyricsService.isRunning) {
            // 关闭悬浮歌词
            stopService(Intent(this, FloatingLyricsService::class.java))
            refreshPlaybackNotification()
            refreshSidebarActionButtons(false)
            Toast.makeText(this, "悬浮歌词已关闭", Toast.LENGTH_SHORT).show()
        } else {
            // 检查悬浮窗权限
            if (PermissionHelper.hasOverlayPermission(this)) {
                // 开启悬浮歌词
                startFloatingLyricsService()
                refreshSidebarActionButtons(true)
            } else {
                // 请求悬浮窗权限
                requestOverlayPermission()
            }
        }
    }

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage("为了显示悬浮歌词，需要授予悬浮窗权限。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = PermissionHelper.findFirstResolvableIntent(
                    this,
                    PermissionHelper.buildOverlayPermissionIntents(this)
                )
                if (intent != null) {
                    waitingOverlayPermissionResult = true
                    overlayPermissionLauncher.launch(intent)
                } else {
                    Toast.makeText(this, "当前设备无法打开悬浮窗设置页", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        handleOverlayPermissionResult()
    }

    private fun handleOverlayPermissionResult() {
        waitingOverlayPermissionResult = false
        if (PermissionHelper.hasOverlayPermission(this)) {
            startFloatingLyricsService()
            refreshSidebarActionButtons(true)
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能使用悬浮歌词", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFloatingLyricsService() {
        // 优先使用已解析的歌词，如果没有则使用 songDetail 中的歌词
        val lyricsToSend = songLyrics ?: songDetail?.lyrics ?: ""
        
        val intent = Intent(this, FloatingLyricsService::class.java).apply {
            action = FloatingLyricsService.ACTION_SHOW
        }
        
        // 传递当前歌曲信息 - 确保歌词不为null
        intent.putExtra(FloatingLyricsService.EXTRA_LYRICS, lyricsToSend)
        intent.putExtra(FloatingLyricsService.EXTRA_SONG_ID, songId)
        intent.putExtra(FloatingLyricsService.EXTRA_SONG_NAME, songName)
        intent.putExtra(FloatingLyricsService.EXTRA_SONG_ARTISTS, songArtists)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        refreshPlaybackNotification()
        refreshSidebarActionButtons(true)
        
        // 延迟发送当前播放状态，确保服务已经启动并初始化完成
        exoPlayer?.let { player ->
            lifecycleScope.launch {
                delay(300) // 等待服务初始化完成
                sendFloatingLyricsUpdate(player.currentPosition, player.isPlaying)
                // 再次发送歌曲变更广播，确保歌词正确加载
                sendFloatingLyricsSongChanged()
            }
        }
        
        Toast.makeText(this, "悬浮歌词已开启", Toast.LENGTH_SHORT).show()
    }

    private fun refreshPlaybackNotification() {
        startService(Intent(this, MusicPlaybackService::class.java))
    }

    private fun sendFloatingLyricsUpdate(position: Long, isPlaying: Boolean) {
        val intent = Intent(FloatingLyricsService.ACTION_UPDATE_PLAYBACK).apply {
            putExtra(FloatingLyricsService.EXTRA_CURRENT_POSITION, position)
            putExtra(FloatingLyricsService.EXTRA_IS_PLAYING, isPlaying)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendFloatingLyricsSongChanged() {
        // 优先使用已解析的歌词，如果没有则使用 songDetail 中的歌词
        val lyricsToSend = songLyrics ?: songDetail?.lyrics
        val intent = Intent(FloatingLyricsService.ACTION_SONG_CHANGED).apply {
            putExtra(FloatingLyricsService.EXTRA_SONG_ID, songId)
            putExtra(FloatingLyricsService.EXTRA_SONG_NAME, songName)
            putExtra(FloatingLyricsService.EXTRA_SONG_ARTISTS, songArtists)
            putExtra(FloatingLyricsService.EXTRA_LYRICS, lyricsToSend)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun toggleFavorite() {
        lifecycleScope.launch {
            try {
                val song = Song(
                    index = 0,
                    id = songId,
                    name = songName,
                    artists = songArtists,
                    coverUrl = songCover ?: ""
                )
                val isNowFavorite = favoriteRepository.toggleFavorite(song, persistencePlatformName())
                val message = if (isNowFavorite) "已添加到我喜欢" else "已从我喜欢移除"
                Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_SHORT).show()
                refreshSidebarActionButtons()
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAddToPlaylistDialog() {
        lifecycleScope.launch {
            try {
                val playlists = playlistRepository.getAllPlaylistsSync()

                if (playlists.isEmpty()) {
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("添加到歌单")
                        .setMessage("暂无歌单，是否创建新歌单？")
                        .setPositiveButton("创建") { _, _ ->
                            showCreatePlaylistDialog()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@launch
                }

                val playlistNames = playlists.map { it.name }.toTypedArray()

                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("添加到歌单")
                    .setItems(playlistNames) { _, which ->
                        addCurrentSongToPlaylist(playlists[which].id)
                    }
                    .setPositiveButton("新建歌单") { _, _ ->
                        showCreatePlaylistDialog()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "获取歌单失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_playlist, null)
        val etPlaylistName = dialogView.findViewById<android.widget.EditText>(R.id.etPlaylistName)

        AlertDialog.Builder(this)
            .setTitle("新建歌单")
            .setView(dialogView)
            .setPositiveButton("创建") { _, _ ->
                val name = etPlaylistName.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        try {
                            val playlist = playlistRepository.createPlaylist(name)
                            addCurrentSongToPlaylist(playlist.id)
                        } catch (e: Exception) {
                            Toast.makeText(this@PlayerActivity, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "请输入歌单名称", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addCurrentSongToPlaylist(playlistId: String) {
        lifecycleScope.launch {
            try {
                val song = Song(
                    index = 0,
                    id = songId,
                    name = songName,
                    artists = songArtists,
                    coverUrl = songCover ?: ""
                )
                playlistRepository.addSongToPlaylist(playlistId, song, persistencePlatformName())
                Toast.makeText(this@PlayerActivity, "已添加到歌单", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun isNightModeEnabled(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun resolveSidebarTextColor(): Int {
        return if (isNightModeEnabled()) {
            ContextCompat.getColor(this, R.color.white)
        } else {
            ContextCompat.getColor(this, R.color.text_primary)
        }
    }

    private fun refreshSidebarActionButtons(forceLyricsRunning: Boolean? = null) {
        val hasSong = songId.isNotBlank()
        val heartOutlineColor = resolveSidebarTextColor()
        val lyricsRunning = forceLyricsRunning ?: FloatingLyricsService.isRunning
        val sampledBgColor = sampleSidebarBackgroundColor()
        val lyricsInactiveColor = resolveLyricsInactiveColor(sampledBgColor)
        val lyricsActiveColor = ContextCompat.getColor(this, R.color.error)
        val strokeColor = if (isColorDark(lyricsActiveColor)) {
            ContextCompat.getColor(this, R.color.white)
        } else {
            ContextCompat.getColor(this, R.color.black)
        }

        binding.btnFloatingLyricsSidebar.inactiveColor = lyricsInactiveColor
        binding.btnFloatingLyricsSidebar.isActiveState = lyricsRunning
        binding.btnFloatingLyricsSidebar.activeColor = ContextCompat.getColor(this, R.color.error)
        binding.btnFloatingLyricsSidebar.emphasizeStrokeOnActive =
            lyricsRunning && sampledBgColor?.let { isColorReddish(it) } == true
        binding.btnFloatingLyricsSidebar.strokeColor = strokeColor
        binding.btnFloatingLyricsSidebar.strokeWidthPx = resources.displayMetrics.density * 1.6f
        binding.btnFloatingLyricsSidebar.isEnabled = hasSong || lyricsRunning
        binding.btnFloatingLyricsSidebar.alpha =
            if (lyricsRunning || hasSong) 1f else 0.45f
        binding.btnFloatingLyricsSidebar.contentDescription =
            if (lyricsRunning) "关闭悬浮歌词" else "开启悬浮歌词"

        binding.btnFavoriteSidebar.alpha = if (hasSong) 1f else 0.45f
        binding.btnFavoriteSidebar.isEnabled = hasSong

        if (!hasSong) {
            binding.btnFavoriteSidebar.setImageResource(R.drawable.ic_heart_outline)
            binding.btnFavoriteSidebar.setColorFilter(heartOutlineColor)
            binding.btnFavoriteSidebar.contentDescription = "添加到我喜欢的"
            return
        }

        binding.btnFavoriteSidebar.setImageResource(R.drawable.ic_heart_outline)
        binding.btnFavoriteSidebar.setColorFilter(heartOutlineColor)
        binding.btnFavoriteSidebar.contentDescription = "添加到我喜欢的"

        val currentSongId = songId
        val requestToken = ++favoriteSidebarRefreshToken
        lifecycleScope.launch {
            try {
                val isFavorite = withContext(Dispatchers.IO) {
                    favoriteRepository.isFavorite(currentSongId)
                }
                if (
                    requestToken != favoriteSidebarRefreshToken ||
                    currentSongId != songId ||
                    isFinishing ||
                    isDestroyed
                ) {
                    return@launch
                }

                binding.btnFavoriteSidebar.setImageResource(
                    if (isFavorite) R.drawable.ic_heart else R.drawable.ic_heart_outline
                )
                binding.btnFavoriteSidebar.setColorFilter(
                    if (isFavorite) {
                        ContextCompat.getColor(this@PlayerActivity, R.color.error)
                    } else {
                        heartOutlineColor
                    }
                )
                binding.btnFavoriteSidebar.contentDescription =
                    if (isFavorite) "取消我喜欢" else "添加到我喜欢的"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh favorite sidebar state", e)
            }
        }
    }

    private fun resolveLyricsInactiveColor(sampledBgColor: Int?): Int {
        if (sampledBgColor == null) {
            return resolveSidebarTextColor()
        }
        return if (isColorDark(sampledBgColor)) {
            ContextCompat.getColor(this, R.color.white)
        } else {
            ContextCompat.getColor(this, R.color.text_primary)
        }
    }

    private fun sampleSidebarBackgroundColor(): Int? {
        if (binding.ivBackground.visibility != View.VISIBLE) return null
        val drawable = binding.ivBackground.drawable as? BitmapDrawable ?: return null
        val bitmap = drawable.bitmap ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        // 采样右侧中下区域（贴近“词”按钮所在位置），用于提升对比度判断准确性
        val startX = (bitmap.width * 0.72f).toInt().coerceIn(0, bitmap.width - 1)
        val endX = (bitmap.width * 0.96f).toInt().coerceIn(startX, bitmap.width - 1)
        val startY = (bitmap.height * 0.52f).toInt().coerceIn(0, bitmap.height - 1)
        val endY = (bitmap.height * 0.84f).toInt().coerceIn(startY, bitmap.height - 1)
        val sampleXStep = ((endX - startX) / 4).coerceAtLeast(1)
        val sampleYStep = ((endY - startY) / 4).coerceAtLeast(1)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0

        var y = startY
        while (y <= endY) {
            var x = startX
            while (x <= endX) {
                val color = bitmap.getPixel(x, y)
                sumR += android.graphics.Color.red(color)
                sumG += android.graphics.Color.green(color)
                sumB += android.graphics.Color.blue(color)
                count++
                x += sampleXStep
            }
            y += sampleYStep
        }

        if (count <= 0) return null
        return android.graphics.Color.rgb(
            (sumR / count).toInt().coerceIn(0, 255),
            (sumG / count).toInt().coerceIn(0, 255),
            (sumB / count).toInt().coerceIn(0, 255)
        )
    }

    private fun isColorDark(color: Int): Boolean {
        val luminance =
            (0.299 * android.graphics.Color.red(color) +
                0.587 * android.graphics.Color.green(color) +
                0.114 * android.graphics.Color.blue(color)) / 255.0
        return luminance < 0.55
    }

    private fun isColorReddish(color: Int): Boolean {
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)
        return r >= 130 && r > g * 1.18f && r > b * 1.18f
    }

    private fun loadSongDetail(quality: String) {
        showLoadingToast()

        lifecycleScope.launch {
            try {
                if (songUrl.isNotEmpty()) {
                    playSong(songUrl)
                    hideLoadingToast()

                    val playlistSong = PlaylistSong(
                        id = songId,
                        name = songName,
                        artists = songArtists,
                        coverUrl = songCover,
                        platform = persistencePlatformName()
                    )
                    playlistManager.addSong(playlistSong)
                } else {
                    // 使用带缓存的Repository获取歌曲详情
                    // 传递 songCover 用于酷我平台的相对路径封面解析
                    val cachedRepository = CachedMusicRepository(this@PlayerActivity)
                    songDetail = withContext(Dispatchers.IO) {
                        cachedRepository.getSongDetail(
                            platform = platform,
                            songId = songId,
                            quality = quality,
                            coverUrlFromSearch = songCover,
                            songName = songName,
                            artists = songArtists
                        )
                    }
                    hideLoadingToast()
                    songDetail?.let { detail ->
                        songLyrics = detail.lyrics
                        updateUI(detail)
                        playSong(detail.url)

                        val playlistSong = PlaylistSong(
                            id = songId,
                            name = songName,
                            artists = songArtists,
                            coverUrl = detail.cover ?: songCover,
                            platform = persistencePlatformName()
                        )
                        playlistManager.addSong(playlistSong)
                    } ?: run {
                        Toast.makeText(this@PlayerActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                hideLoadingToast()
                Toast.makeText(this@PlayerActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSongDetailButNotPlay(quality: String) {
        showLoadingToast()

        lifecycleScope.launch {
            try {
                if (songId.startsWith("local_") || platform.name.equals("LOCAL", ignoreCase = true)) {
                    val localDetail = resolveLocalSongDetail(
                        localSongId = songId,
                        localSongName = songName,
                        localSongArtists = songArtists,
                        fallbackCoverUrl = songCover
                    )
                    hideLoadingToast()
                    if (localDetail != null) {
                        songDetail = localDetail
                        songCover = preferNonBlank(localDetail.cover, songCover)
                        songLyrics = preferNonBlank(localDetail.lyrics, songLyrics)
                        updateUI(localDetail)
                        loadCoverAndBackground(songCover)
                        loadSongButNotPlay(localDetail.url)

                        val playlistSong = PlaylistSong(
                            id = songId,
                            name = songName,
                            artists = songArtists,
                            coverUrl = songCover,
                            platform = persistencePlatformName()
                        )
                        playlistManager.addSong(playlistSong)

                        if (songCover.isNullOrBlank() || songLyrics.isNullOrBlank()) {
                            loadMissingInfoInBackground()
                        }
                    } else {
                        Toast.makeText(this@PlayerActivity, "本地歌曲加载失败", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (songUrl.isNotEmpty()) {
                    // 如果已经有URL（从搜索列表播放），直接播放
                    playSong(songUrl)
                    hideLoadingToast()

                    val playlistSong = PlaylistSong(
                        id = songId,
                        name = songName,
                        artists = songArtists,
                        coverUrl = songCover,
                        platform = persistencePlatformName()
                    )
                    playlistManager.addSong(playlistSong)
                } else {
                    // 使用带缓存的Repository获取歌曲详情
                    // 传递 songCover 用于酷我平台的相对路径封面解析
                    val cachedRepository = CachedMusicRepository(this@PlayerActivity)
                    songDetail = withContext(Dispatchers.IO) {
                        cachedRepository.getSongDetail(
                            platform = platform,
                            songId = songId,
                            quality = quality,
                            coverUrlFromSearch = songCover,
                            songName = songName,
                            artists = songArtists
                        )
                    }
                    hideLoadingToast()
                    songDetail?.let { detail ->
                        songLyrics = detail.lyrics
                        updateUI(detail)
                        loadSongButNotPlay(detail.url)

                        val playlistSong = PlaylistSong(
                            id = songId,
                            name = songName,
                            artists = songArtists,
                            coverUrl = detail.cover ?: songCover,
                            platform = persistencePlatformName()
                        )
                        playlistManager.addSong(playlistSong)
                    } ?: run {
                        Toast.makeText(this@PlayerActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                hideLoadingToast()
                Toast.makeText(this@PlayerActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLoadingToast() {
        loadingToast?.cancel()
        loadingToast = Toast.makeText(this, "加载中...", Toast.LENGTH_SHORT)
        loadingToast?.show()
    }

    private fun hideLoadingToast() {
        loadingToast?.cancel()
        loadingToast = null
    }

    private fun updateUI(detail: SongDetail) {
        // 更新专辑封面和背景（始终刷新）
        detail.cover?.let { coverUrl ->
            // 更新专辑封面 - 强制刷新以确保显示最新图片
            Glide.with(this)
                .load(coverUrl)
                .placeholder(R.drawable.ic_album_default)
                .error(R.drawable.ic_album_default)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(binding.ivAlbum)

            // 更新背景图片 - 强制刷新以确保显示最新图片
            Glide.with(this)
                .load(coverUrl)
                .placeholder(R.drawable.bg_default_light_cyan)
                .error(R.drawable.bg_default_light_cyan)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(binding.ivBackground)

            // 显示背景图片
            binding.ivBackground.visibility = View.VISIBLE

            // 更新 songCover
            songCover = coverUrl
        }

        // 解析歌词但不显示到UI
        detail.lyrics?.let { lyricsText ->
            parsedLyrics = parseLyrics(lyricsText)
        }
    }

    private fun parseLyrics(lyricsText: String): List<Pair<Long, String>> {
        val lyricsList = mutableListOf<Pair<Long, String>>()
        val lines = lyricsText.lines()

        for (line in lines) {
            val regex = """\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""".toRegex()
            val matchResult = regex.find(line)
            if (matchResult != null) {
                try {
                    val minutes = matchResult.groupValues[1].toLong()
                    val seconds = matchResult.groupValues[2].toLong()
                    val millis = matchResult.groupValues[3].toLong() * if (matchResult.groupValues[3].length == 2) 10 else 1
                    val text = matchResult.groupValues[4].trim()
                    val timeMillis = TimeUnit.MINUTES.toMillis(minutes) + TimeUnit.SECONDS.toMillis(seconds) + millis
                    if (text.isNotEmpty()) {
                        lyricsList.add(timeMillis to text)
                    }
                } catch (e: Exception) {
                    // 忽略解析失败的行
                }
            }
        }
        return lyricsList.sortedBy { it.first }
    }

    private fun updateLyrics(currentPosition: Long) {
        if (parsedLyrics.isEmpty()) return

        applyPlayerLyricStyle()

        var currentIndex = -1
        for (i in parsedLyrics.indices) {
            if (parsedLyrics[i].first <= currentPosition) {
                currentIndex = i
            } else {
                break
            }
        }

        if (currentIndex >= 0) {
            binding.tvLyricsCurrent.text = parsedLyrics[currentIndex].second
            val nextIndex = currentIndex + 1
            if (nextIndex < parsedLyrics.size) {
                binding.tvLyricsNext.text = parsedLyrics[nextIndex].second
            } else {
                binding.tvLyricsNext.text = ""
            }
        } else {
            binding.tvLyricsCurrent.text = parsedLyrics.firstOrNull()?.second ?: ""
            binding.tvLyricsNext.text = if (parsedLyrics.size > 1) parsedLyrics[1].second else ""
        }
    }

    private fun applyPlayerLyricStyle() {
        val lyricColor = LyricStyleSettings.getPlayerLyricColor(this)
        val lyricSize = LyricStyleSettings.getPlayerLyricSize(this)
        binding.tvLyricsCurrent.setTextColor(lyricColor)
        binding.tvLyricsCurrent.textSize = lyricSize
        binding.tvLyricsNext.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        binding.tvLyricsNext.textSize = (lyricSize * 0.78f).coerceAtLeast(12f)
    }

    private fun playSong(url: String) {
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaId(songId)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(songName)
                        .setArtist(songArtists)
                        .build()
                )
                .build()
            // 使用 setMediaItem(mediaItem, startPositionMs) 确保从0位置开始播放
            player.setMediaItem(mediaItem, 0)
            player.prepare()
            player.play()

            // 立即重置UI进度显示为0，确保用户看到正确的起始位置
            binding.seekBar.progress = 0
            binding.tvCurrentTime.text = "0:00"
            // 重置保存的播放进度为0
            playbackPreferences.currentPosition = 0L

            // 延迟重置切换歌曲标志，确保播放器已经准备好
            lifecycleScope.launch {
                delay(200)
                isSwitchingSong = false
            }

            startAlbumRotation()

            recordRecentPlay()

            musicService?.updateMediaMetadata(songName, songArtists, null)

            songDetail?.let { detail ->
                playbackPreferences.saveSongDetail(songId, detail)
            }
            
            // 通知悬浮歌词歌曲切换
            if (FloatingLyricsService.isRunning) {
                sendFloatingLyricsSongChanged()
            }
        }
    }

    private fun loadSongButNotPlay(url: String, position: Long = 0, autoPlay: Boolean = false) {
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaId(songId)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(songName)
                        .setArtist(songArtists)
                        .build()
                )
                .build()
            // 使用 setMediaItem(mediaItem, startPositionMs) 确保从指定位置开始
            player.setMediaItem(mediaItem, position)
            player.prepare()

            // 修复：立即重置UI进度显示，但确保position不为负数
            val safePosition = position.coerceAtLeast(0)
            binding.seekBar.progress = safePosition.toInt()
            binding.tvCurrentTime.text = formatTime(safePosition)
            // 重置保存的播放进度
            playbackPreferences.currentPosition = safePosition

            // 延迟重置切换歌曲标志，确保播放器已经准备好并更新duration显示
            lifecycleScope.launch {
                delay(300)
                // 播放器准备好后，更新duration显示
                val duration = player.duration
                if (duration > 0 && duration != androidx.media3.common.C.TIME_UNSET) {
                    binding.seekBar.max = duration.toInt()
                    binding.tvTotalTime.text = formatTime(duration)
                }
                isSwitchingSong = false
            }

            if (autoPlay) {
                player.play()
                startAlbumRotation()
            }

            musicService?.updateMediaMetadata(songName, songArtists, null)

            songDetail?.let { detail ->
                playbackPreferences.saveSongDetail(songId, detail)
            }
            
            // 通知悬浮歌词歌曲切换
            if (FloatingLyricsService.isRunning) {
                sendFloatingLyricsSongChanged()
            }
        }
    }

    private fun recordRecentPlay() {
        lifecycleScope.launch {
            try {
                val currentSong = playlistManager.getCurrentSong()
                if (currentSong != null) {
                    recentPlayRepository.addRecentPlayFromPlaylistSong(currentSong)
                } else if (!isFromEmptyState && songId.isNotEmpty()) {
                    // 如果播放列表中没有，但当前有歌曲信息，创建一个临时记录
                    val tempSong = PlaylistSong(
                        id = songId,
                        name = songName,
                        artists = songArtists,
                        coverUrl = songCover,
                        platform = persistencePlatformName()
                    )
                    recentPlayRepository.addRecentPlayFromPlaylistSong(tempSong)
                }
            } catch (e: Exception) {
                // 记录播放历史失败不影响正常播放
            }
        }
    }

    private fun showQualityDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_quality_bubble, null)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Translucent_NoTitleBar)
            .setView(dialogView)
            .create()

        // 设置对话框背景透明
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 设置对话框为自适应大小
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        // 获取视图引用
        val cardHR = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardHR)
        val cardCDQ = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardCDQ)
        val cardHQ = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardHQ)
        val cardLQ = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardLQ)

        val ivCheckHR = dialogView.findViewById<ImageView>(R.id.ivCheckHR)
        val ivCheckCDQ = dialogView.findViewById<ImageView>(R.id.ivCheckCDQ)
        val ivCheckHQ = dialogView.findViewById<ImageView>(R.id.ivCheckHQ)
        val ivCheckLQ = dialogView.findViewById<ImageView>(R.id.ivCheckLQ)

        // 临时存储选中的音质
        var selectedQuality = currentQuality

        // 根据当前音质显示选中状态
        when (currentQuality.lowercase()) {
            "flac24bit" -> {
                cardHR.setCardBackgroundColor(getColor(R.color.primary_light))
                ivCheckHR.visibility = View.VISIBLE
            }
            "flac" -> {
                cardCDQ.setCardBackgroundColor(getColor(R.color.primary_light))
                ivCheckCDQ.visibility = View.VISIBLE
            }
            "320k" -> {
                cardHQ.setCardBackgroundColor(getColor(R.color.primary_light))
                ivCheckHQ.visibility = View.VISIBLE
            }
            "128k" -> {
                cardLQ.setCardBackgroundColor(getColor(R.color.primary_light))
                ivCheckLQ.visibility = View.VISIBLE
            }
        }

        // 设置点击事件 - 点击后直接确认选择
        cardHR.setOnClickListener {
            selectedQuality = "flac24bit"
            if (selectedQuality != currentQuality) {
                currentQuality = selectedQuality
                updateQualityDisplay(currentQuality)
                loadSongDetail(currentQuality)
            }
            dialog.dismiss()
        }

        cardCDQ.setOnClickListener {
            selectedQuality = "flac"
            if (selectedQuality != currentQuality) {
                currentQuality = selectedQuality
                updateQualityDisplay(currentQuality)
                loadSongDetail(currentQuality)
            }
            dialog.dismiss()
        }

        cardHQ.setOnClickListener {
            selectedQuality = "320k"
            if (selectedQuality != currentQuality) {
                currentQuality = selectedQuality
                updateQualityDisplay(currentQuality)
                loadSongDetail(currentQuality)
            }
            dialog.dismiss()
        }

        cardLQ.setOnClickListener {
            selectedQuality = "128k"
            if (selectedQuality != currentQuality) {
                currentQuality = selectedQuality
                updateQualityDisplay(currentQuality)
                loadSongDetail(currentQuality)
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateQualityDisplay(quality: String) {
        // 音质显示已移至"更多选项"菜单内，此方法保留用于记录当前音质
        val qualityText = when (quality.lowercase()) {
            "128k" -> "LQ"
            "320k" -> "HQ"
            "flac" -> "CDQ"
            "flac24bit" -> "HR"
            else -> "HQ"
        }
        // 可以在这里添加日志记录或其他逻辑
    }

    private fun showQualityDialogForDownload() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_quality_bubble, null)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Translucent_NoTitleBar)
            .setView(dialogView)
            .create()

        // 设置对话框背景透明
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 设置对话框为自适应大小
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        // 获取视图引用
        val cardHR = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardHR)
        val cardCDQ = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardCDQ)
        val cardHQ = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardHQ)
        val cardLQ = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardLQ)

        val ivCheckHR = dialogView.findViewById<ImageView>(R.id.ivCheckHR)
        val ivCheckCDQ = dialogView.findViewById<ImageView>(R.id.ivCheckCDQ)
        val ivCheckHQ = dialogView.findViewById<ImageView>(R.id.ivCheckHQ)
        val ivCheckLQ = dialogView.findViewById<ImageView>(R.id.ivCheckLQ)

        // 默认选中 HQ (320k)
        cardHQ.setCardBackgroundColor(getColor(R.color.primary_light))
        ivCheckHQ.visibility = View.VISIBLE

        // 设置点击事件 - 点击后直接下载
        cardHR.setOnClickListener {
            downloadSong("flac24bit")
            dialog.dismiss()
        }

        cardCDQ.setOnClickListener {
            downloadSong("flac")
            dialog.dismiss()
        }

        cardHQ.setOnClickListener {
            downloadSong("320k")
            dialog.dismiss()
        }

        cardLQ.setOnClickListener {
            downloadSong("128k")
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun downloadSong(quality: String) {
        val progressDialog = ProgressDialog(this).apply {
            setMessage(getString(R.string.downloading))
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                // 非下载管理页面，强制重新获取最新URL，但封面和歌词使用缓存
                // 传递 songCover 用于酷我平台的相对路径封面解析
                val cachedRepository = CachedMusicRepository(this@PlayerActivity)
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongUrlWithCache(
                        platform = platform,
                        songId = songId,
                        quality = quality,
                        songName = songName,
                        artists = songArtists,
                        useCache = true,
                        coverUrlFromSearch = songCover
                    )
                }
                if (detail != null) {
                    val song = Song(
                        index = 0,
                        id = songId,
                        name = songName,
                        artists = songArtists,
                        coverUrl = detail.cover ?: songCover
                    )
                    val downloadManager = DownloadManager.getInstance(this@PlayerActivity)
                    val task = downloadManager.createDownloadTask(song, detail, quality, platform.name)
                    downloadManager.startDownload(task)
                    Toast.makeText(this@PlayerActivity, "开始下载: ${task.fileName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@PlayerActivity, R.string.download_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressDialog.dismiss()
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun sendPlaybackUpdate(currentPosition: Long, duration: Long, isPlaying: Boolean) {
        val intent = Intent(ACTION_PLAYBACK_UPDATE).apply {
            putExtra(EXTRA_CURRENT_POSITION, currentPosition)
            putExtra(EXTRA_DURATION, duration)
            putExtra(EXTRA_IS_PLAYING, isPlaying)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        
        // 同时更新悬浮歌词
        if (FloatingLyricsService.isRunning) {
            sendFloatingLyricsUpdate(currentPosition, isPlaying)
        }
    }

    private fun updatePlayModeIcon() {
        val playMode = playlistManager.playMode.value
        val iconRes = when (playMode) {
            PlaylistManager.PLAY_MODE_SEQUENTIAL -> R.drawable.ic_sequential
            PlaylistManager.PLAY_MODE_SHUFFLE -> R.drawable.ic_shuffle
            PlaylistManager.PLAY_MODE_REPEAT_LIST -> R.drawable.ic_repeat
            PlaylistManager.PLAY_MODE_REPEAT_ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_sequential
        }
        binding.btnShuffle.setImageResource(iconRes)
    }

    override fun onDestroy() {
        super.onDestroy()
        savePlaybackState()
        albumRotationAnimator?.cancel()

        // 解绑服务，但不停止它（让音乐继续播放）
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        // 确保注销广播接收器
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(playbackControlReceiver)
        } catch (e: Exception) {
            // 忽略未注册的错误
        }
    }

}
