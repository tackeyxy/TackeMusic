package com.tacke.music.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import com.tacke.music.R
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.SongDetail
import com.tacke.music.data.model.SongInfo
import com.tacke.music.data.preferences.PlaybackPreferences
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.LocalMusicInfoRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.PlayerActivity
import com.tacke.music.utils.CoverUrlResolver
import com.tacke.music.utils.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class MusicPlaybackService : Service() {

    private val notificationLogTag = "MusicNotification"

    private var exoPlayer: ExoPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private val binder = MusicBinder()
    private lateinit var playlistManager: PlaylistManager
    private lateinit var playbackPreferences: PlaybackPreferences
    private val repository = MusicRepository()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentQuality = "320k"
    private var currentNotificationCover: Bitmap? = null
    private var currentNotificationCoverKey: String? = null
    private var notificationProgressJob: Job? = null
    private var telephonyManager: TelephonyManager? = null
    private var lastCallState: Int = TelephonyManager.CALL_STATE_IDLE
    private var manualPlayOverrideDuringCall = false
    private var notificationMarqueeSourceKey: String? = null
    private var notificationMarqueeStartMs: Long = 0L
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallStateChanged(state)
        }
    }

    private data class NotificationUiStyle(
        val primaryTextColor: Int,
        val secondaryTextColor: Int,
        val timeTextColor: Int,
        val iconTintColor: Int,
        val controlBackgroundRes: Int,
        val scrimBackgroundRes: Int,
        val useDarkProgressStyle: Boolean
    )

    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.tacke.music.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.tacke.music.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.tacke.music.ACTION_PREVIOUS"
        const val ACTION_TOGGLE_FLOATING_LYRICS = "com.tacke.music.ACTION_TOGGLE_FLOATING_LYRICS"
        const val ACTION_REQUEST_OVERLAY_PERMISSION = "com.tacke.music.ACTION_REQUEST_OVERLAY_PERMISSION"
        const val ACTION_TOGGLE_PLAY_MODE = "com.tacke.music.ACTION_TOGGLE_PLAY_MODE"
        const val ACTION_PLAY_MODE_CHANGED = "com.tacke.music.ACTION_PLAY_MODE_CHANGED"
        const val ACTION_STOP = "com.tacke.music.ACTION_STOP"
        const val ACTION_SONG_CHANGED = "com.tacke.music.ACTION_SONG_CHANGED"
        const val EXTRA_SONG_ID = "song_id"
        const val EXTRA_SONG_NAME = "song_name"
        const val EXTRA_SONG_ARTISTS = "song_artists"
        const val EXTRA_SONG_URL = "song_url"
        const val EXTRA_SONG_COVER = "song_cover"
        const val EXTRA_SONG_LYRICS = "song_lyrics"
        const val EXTRA_PLAY_MODE = "play_mode"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
        fun getPlayer(): ExoPlayer? = exoPlayer
    }

    override fun onCreate() {
        super.onCreate()
        playlistManager = PlaylistManager.getInstance(this)
        playbackPreferences = PlaybackPreferences.getInstance(this)
        currentQuality = playbackPreferences.currentQuality
        logNotificationAvailability("onCreate:beforeCreateChannel")
        createNotificationChannel()
        logNotificationAvailability("onCreate:afterCreateChannel")
        initializePlayer()
        initializeMediaSession()
        registerBroadcastReceiver()
        registerCallStateListener()
    }

    // 加载并播放指定歌曲
    private suspend fun loadAndPlaySong(playlistSong: PlaylistSong) {
        // 先把目标歌曲信息推到通知，避免详情网络请求期间仍显示旧歌信息
        updateNotificationForPendingSong(playlistSong)

        if (isLocalPlaylistSong(playlistSong)) {
            val localDetail = resolveLocalSongDetail(playlistSong)
            if (localDetail != null) {
                playSong(localDetail, playlistSong)
                if (localDetail.cover.isNullOrBlank() || localDetail.lyrics.isNullOrBlank()) {
                    serviceScope.launch {
                        val enrichedLocalDetail = fetchLocalSongInfoInBackground(
                            playlistSong = playlistSong,
                            fallbackUrl = localDetail.url,
                            fallbackCover = localDetail.cover
                        )
                        if (enrichedLocalDetail != null) {
                            enrichCurrentSongMetadataIfNeeded(playlistSong, enrichedLocalDetail)
                        }
                    }
                }
                return
            }
        }

        // 在线歌曲：优先本地完整缓存直放，避免重复请求
        val cachedRepository = CachedMusicRepository(this@MusicPlaybackService)
        val cachedPlayableDetail = withContext(Dispatchers.IO) {
            cachedRepository.getLocalSongDetail(playlistSong.id)
        }
        if (cachedPlayableDetail != null && cachedPlayableDetail.url.isNotBlank()) {
            val mergedCachedDetail = cachedPlayableDetail.copy(
                cover = cachedPlayableDetail.cover ?: playlistSong.coverUrl
            )
            playSong(mergedCachedDetail, playlistSong)
            return
        }

        val platform = try {
            MusicRepository.Platform.valueOf(playlistSong.platform.uppercase())
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }

        // 缓存未命中时才走网络
        val cachedMeta = withContext(Dispatchers.IO) {
            cachedRepository.getCachedCoverAndLyrics(playlistSong.id)
        }

        val urlOnlyDetail = withContext(Dispatchers.IO) {
            cachedRepository.getSongUrlOnly(
                platform = platform,
                songId = playlistSong.id,
                quality = currentQuality,
                songName = playlistSong.name,
                artists = playlistSong.artists
            )
        }

        if (urlOnlyDetail != null) {
            val immediateDetail = urlOnlyDetail.copy(
                cover = cachedMeta?.cover ?: playlistSong.coverUrl,
                lyrics = cachedMeta?.lyrics
            )
            // 先用可播放URL立即开播，图片/歌词可滞后补齐
            playSong(immediateDetail, playlistSong)

            // 后台补齐封面和歌词，不中断当前播放
            serviceScope.launch {
                val fullDetail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongDetail(
                        platform = platform,
                        songId = playlistSong.id,
                        quality = currentQuality,
                        songName = playlistSong.name,
                        artists = playlistSong.artists
                    )
                }
                if (fullDetail != null) {
                    enrichCurrentSongMetadataIfNeeded(playlistSong, fullDetail)
                }
            }
        } else {
            // URL快速获取失败时回退到完整详情流程，避免切歌失败
            val fallbackDetail = withContext(Dispatchers.IO) {
                cachedRepository.getSongDetail(
                    platform = platform,
                    songId = playlistSong.id,
                    quality = currentQuality,
                    songName = playlistSong.name,
                    artists = playlistSong.artists
                )
            }
            if (fallbackDetail != null) {
                playSong(fallbackDetail, playlistSong)
            }
        }
    }

    private fun isLocalPlaylistSong(song: PlaylistSong): Boolean {
        return song.platform.equals("LOCAL", ignoreCase = true) || song.id.startsWith("local_")
    }

    private suspend fun resolveLocalSongDetail(playlistSong: PlaylistSong): SongDetail? {
        val persistedDetail = playbackPreferences.getSongDetail(playlistSong.id)
        val localMusicInfoRepository = LocalMusicInfoRepository(this@MusicPlaybackService)
        val hash = playlistSong.id.removePrefix("local_").toIntOrNull()
        val localMusic = withContext(Dispatchers.IO) {
            localMusicInfoRepository.getAllCachedMusic().firstOrNull { music ->
                (hash != null && music.path.hashCode() == hash) ||
                    (music.title == playlistSong.name && music.artist == playlistSong.artists)
            }
        }

        if (localMusic == null) {
            return persistedDetail?.copy(
                info = SongInfo(name = playlistSong.name, artist = playlistSong.artists),
                cover = persistedDetail.cover ?: playlistSong.coverUrl,
                lyrics = persistedDetail.lyrics
            )
        }

        val cachedInfo = withContext(Dispatchers.IO) {
            localMusicInfoRepository.getCachedInfoByPath(localMusic.path)
        }
        val playUrl = localMusic.contentUri ?: localMusic.path
        val rawCover = cachedInfo?.coverUrl ?: persistedDetail?.cover ?: localMusic.coverUri ?: playlistSong.coverUrl
        val cover = if (!rawCover.isNullOrBlank() && CoverUrlResolver.isRelativePath(rawCover)) {
            val sourcePlatform = cachedInfo?.source?.takeIf { it.isNotBlank() } ?: "KUWO"
            CoverUrlResolver.resolveCoverUrl(
                context = this@MusicPlaybackService,
                coverUrl = rawCover,
                songId = playlistSong.id,
                platform = sourcePlatform,
                songName = playlistSong.name,
                artist = playlistSong.artists
            ) ?: rawCover
        } else {
            rawCover
        }
        val lyrics = cachedInfo?.lyrics ?: persistedDetail?.lyrics
        return SongDetail(
            url = playUrl,
            info = SongInfo(name = playlistSong.name, artist = playlistSong.artists),
            cover = cover,
            lyrics = lyrics
        )
    }

    private suspend fun fetchLocalSongInfoInBackground(
        playlistSong: PlaylistSong,
        fallbackUrl: String,
        fallbackCover: String?
    ): SongDetail? {
        val localMusicInfoRepository = LocalMusicInfoRepository(this@MusicPlaybackService)
        val hash = playlistSong.id.removePrefix("local_").toIntOrNull()
        val localMusic = withContext(Dispatchers.IO) {
            localMusicInfoRepository.getAllCachedMusic().firstOrNull { music ->
                (hash != null && music.path.hashCode() == hash) ||
                    (music.title == playlistSong.name && music.artist == playlistSong.artists)
            }
        } ?: return null

        val localInfo = withContext(Dispatchers.IO) {
            localMusicInfoRepository.getLocalMusicInfo(localMusic)
        } ?: return null

        val rawCover = localInfo.coverUrl ?: fallbackCover ?: localMusic.coverUri ?: playlistSong.coverUrl
        val cover = if (!rawCover.isNullOrBlank() && CoverUrlResolver.isRelativePath(rawCover)) {
            val sourcePlatform = localInfo.source?.takeIf { it.isNotBlank() } ?: "KUWO"
            CoverUrlResolver.resolveCoverUrl(
                context = this@MusicPlaybackService,
                coverUrl = rawCover,
                songId = playlistSong.id,
                platform = sourcePlatform,
                songName = playlistSong.name,
                artist = playlistSong.artists
            ) ?: rawCover
        } else {
            rawCover
        }
        val lyrics = localInfo.lyrics ?: playbackPreferences.getSongDetail(playlistSong.id)?.lyrics
        if (cover.isNullOrBlank() && lyrics.isNullOrBlank()) return null

        return SongDetail(
            url = fallbackUrl,
            info = SongInfo(name = playlistSong.name, artist = playlistSong.artists),
            cover = cover,
            lyrics = lyrics
        )
    }

    private fun updateNotificationForPendingSong(playlistSong: PlaylistSong) {
        val platform = playlistSong.platform.lowercase(Locale.getDefault())
        val coverRef = playlistSong.coverUrl.orEmpty()
        val quickCover = when {
            coverRef.startsWith("/", ignoreCase = false) -> BitmapFactory.decodeFile(coverRef)
            coverRef.startsWith("file://", ignoreCase = true) -> {
                BitmapFactory.decodeFile(coverRef.removePrefix("file://"))
            }
            else -> com.tacke.music.utils.CoverImageManager.loadCoverBitmap(this, playlistSong.id, platform)
        }
        currentNotificationCover = quickCover
        currentNotificationCoverKey = buildNotificationCoverKey(playlistSong.id, platform, coverRef)
        updateMediaMetadata(playlistSong.name, playlistSong.artists, quickCover)
    }

    // 播放歌曲
    private fun playSong(detail: SongDetail, playlistSong: PlaylistSong) {
        val player = exoPlayer ?: return
        val cachedDetail = playbackPreferences.getSongDetail(playlistSong.id)
        val mergedDetail = detail.copy(
            cover = detail.cover ?: cachedDetail?.cover ?: playlistSong.coverUrl,
            lyrics = detail.lyrics ?: cachedDetail?.lyrics
        )
        val coverReference = mergedDetail.cover ?: playlistSong.coverUrl

        // 更新MediaSession元数据
        updateMediaMetadata(
            playlistSong.name,
            playlistSong.artists,
            null
        )

        currentNotificationCover = null
        currentNotificationCoverKey = null

        // 设置MediaItem并播放
        val mediaItem = MediaItem.Builder()
            .setUri(mergedDetail.url)
            .setMediaId(playlistSong.id)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(playlistSong.name)
                    .setArtist(playlistSong.artists)
                    .build()
            )
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        loadNotificationCoverAsync(
            songId = playlistSong.id,
            platform = playlistSong.platform,
            coverReference = coverReference,
            songName = playlistSong.name,
            artist = playlistSong.artists
        )

        // 保存播放状态
        playbackPreferences.currentSongId = playlistSong.id
        playbackPreferences.saveSongDetail(playlistSong.id, mergedDetail)

        // 发送广播通知UI更新
        val intent = Intent(ACTION_SONG_CHANGED).apply {
            putExtra(EXTRA_SONG_ID, playlistSong.id)
            putExtra(EXTRA_SONG_NAME, playlistSong.name)
            putExtra(EXTRA_SONG_ARTISTS, playlistSong.artists)
            putExtra(EXTRA_SONG_URL, mergedDetail.url)
            putExtra(EXTRA_SONG_COVER, mergedDetail.cover ?: playlistSong.coverUrl)
            putExtra(EXTRA_SONG_LYRICS, mergedDetail.lyrics)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // 同时发送广播给悬浮歌词服务
        val floatingLyricsIntent = Intent(FloatingLyricsService.ACTION_SONG_CHANGED).apply {
            putExtra(FloatingLyricsService.EXTRA_SONG_ID, playlistSong.id)
            putExtra(FloatingLyricsService.EXTRA_SONG_NAME, playlistSong.name)
            putExtra(FloatingLyricsService.EXTRA_SONG_ARTISTS, playlistSong.artists)
            putExtra(FloatingLyricsService.EXTRA_LYRICS, mergedDetail.lyrics)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(floatingLyricsIntent)
    }

    private fun enrichCurrentSongMetadataIfNeeded(playlistSong: PlaylistSong, fullDetail: SongDetail) {
        val currentSong = playlistManager.getCurrentSong() ?: return
        if (currentSong.id != playlistSong.id) return

        val existing = playbackPreferences.getSongDetail(playlistSong.id)
        val mergedDetail = fullDetail.copy(
            cover = fullDetail.cover ?: existing?.cover ?: playlistSong.coverUrl,
            lyrics = fullDetail.lyrics ?: existing?.lyrics
        )
        playbackPreferences.saveSongDetail(playlistSong.id, mergedDetail)

        loadNotificationCoverAsync(
            songId = playlistSong.id,
            platform = playlistSong.platform,
            coverReference = mergedDetail.cover ?: playlistSong.coverUrl,
            songName = playlistSong.name,
            artist = playlistSong.artists
        )

        val songChangedIntent = Intent(ACTION_SONG_CHANGED).apply {
            putExtra(EXTRA_SONG_ID, playlistSong.id)
            putExtra(EXTRA_SONG_NAME, playlistSong.name)
            putExtra(EXTRA_SONG_ARTISTS, playlistSong.artists)
            putExtra(EXTRA_SONG_URL, mergedDetail.url)
            putExtra(EXTRA_SONG_COVER, mergedDetail.cover ?: playlistSong.coverUrl)
            putExtra(EXTRA_SONG_LYRICS, mergedDetail.lyrics)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(songChangedIntent)

        val floatingLyricsIntent = Intent(FloatingLyricsService.ACTION_SONG_CHANGED).apply {
            putExtra(FloatingLyricsService.EXTRA_SONG_ID, playlistSong.id)
            putExtra(FloatingLyricsService.EXTRA_SONG_NAME, playlistSong.name)
            putExtra(FloatingLyricsService.EXTRA_SONG_ARTISTS, playlistSong.artists)
            putExtra(FloatingLyricsService.EXTRA_LYRICS, mergedDetail.lyrics)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(floatingLyricsIntent)
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
                updateMediaSessionState()
                if (isPlaying) {
                    startNotificationProgressUpdates()
                } else {
                    stopNotificationProgressUpdates()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMediaSessionState()
                if (playbackState == Player.STATE_ENDED) {
                    stopNotificationProgressUpdates()
                    handlePlaybackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 处理播放错误，特别是URL过期导致的403错误
                handlePlayerError(error)
            }
        })
    }

    private var currentSongForRetry: PlaylistSong? = null
    private var isRetrying = false

    private fun handlePlayerError(error: PlaybackException) {
        Log.e("MusicPlaybackService", "Player error: ${error.errorCodeName}", error)

        // 检查是否是URL过期导致的错误（403/410 Bad HTTP Status）
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {

            // 避免重复刷新
            if (isRetrying) return
            isRetrying = true

            val currentSong = playlistManager.getCurrentSong()
            if (currentSong != null) {
                if (isLocalPlaylistSong(currentSong)) {
                    serviceScope.launch {
                        val localDetail = resolveLocalSongDetail(currentSong)
                        if (localDetail != null) {
                            playSong(localDetail, currentSong)
                        }
                    }
                    isRetrying = false
                    return
                }
                currentSongForRetry = currentSong
                serviceScope.launch {
                    try {
                        // 清除过期的缓存（包括数据库缓存和SharedPreferences缓存）
                        playbackPreferences.clearSongDetail(currentSong.id)
                        val cachedRepository = CachedMusicRepository(this@MusicPlaybackService)
                        cachedRepository.clearCache(currentSong.id)

                        val platform = try {
                            MusicRepository.Platform.valueOf(currentSong.platform.uppercase())
                        } catch (e: Exception) {
                            MusicRepository.Platform.KUWO
                        }

                        // 强制从网络获取最新URL（不使用缓存）
                        val newDetail = withContext(Dispatchers.IO) {
                            cachedRepository.getSongUrlOnly(
                                platform = platform,
                                songId = currentSong.id,
                                quality = currentQuality,
                                songName = currentSong.name,
                                artists = currentSong.artists
                            )
                        }

                        if (newDetail != null && newDetail.url.isNotBlank()) {
                            // 获取缓存的封面和歌词
                            val cachedMeta = withContext(Dispatchers.IO) {
                                cachedRepository.getCachedCoverAndLyrics(currentSong.id)
                            }
                            // 合并数据：使用新URL，保留缓存的封面和歌词
                            val mergedDetail = newDetail.copy(
                                cover = cachedMeta?.cover ?: currentSong.coverUrl,
                                lyrics = cachedMeta?.lyrics
                            )
                            // 使用新URL重新播放
                            playSong(mergedDetail, currentSong)
                            Log.d("MusicPlaybackService", "URL刷新成功，重新播放: ${currentSong.name}")
                        } else {
                            Log.e("MusicPlaybackService", "无法获取新的播放URL: ${currentSong.name}")
                        }
                    } catch (e: Exception) {
                        Log.e("MusicPlaybackService", "Failed to refresh URL", e)
                    } finally {
                        isRetrying = false
                        currentSongForRetry = null
                    }
                }
            }
        }
    }

    private fun handlePlaybackEnded() {
        // 播放完成，根据播放模式处理
        when (playlistManager.playMode.value) {
            PlaylistManager.PLAY_MODE_REPEAT_ONE -> {
                // 单曲循环：重新播放当前歌曲
                exoPlayer?.seekTo(0)
                exoPlayer?.play()
            }
            else -> {
                // 其他模式：播放下一首
                serviceScope.launch {
                    playNextSong()
                }
            }
        }
    }

    private suspend fun playNextSong() {
        // 确保播放列表已加载
        if (playlistManager.currentPlaylist.value.isEmpty()) {
            playlistManager.loadPlaylist()
        }

        val nextSong = playlistManager.next()
        if (nextSong != null) {
            loadAndPlaySong(nextSong)
        }
    }

    private suspend fun playPreviousSong() {
        // 确保播放列表已加载
        if (playlistManager.currentPlaylist.value.isEmpty()) {
            playlistManager.loadPlaylist()
        }

        val previousSong = playlistManager.previous()
        if (previousSong != null) {
            loadAndPlaySong(previousSong)
        }
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlaybackService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    markManualPlaybackDuringCallIfNeeded()
                    exoPlayer?.play()
                }

                override fun onPause() {
                    exoPlayer?.pause()
                }

                override fun onSkipToNext() {
                    handleUserSkipNext()
                }

                override fun onSkipToPrevious() {
                    handleUserSkipPrevious()
                }

                override fun onStop() {
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    private fun registerBroadcastReceiver() {
        // 注册全局广播接收器（用于通知栏控制）
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_TOGGLE_FLOATING_LYRICS)
            addAction(ACTION_REQUEST_OVERLAY_PERMISSION)
            addAction(ACTION_TOGGLE_PLAY_MODE)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }

        // 注册LocalBroadcastManager接收器（用于应用内组件通信，如悬浮窗）
        val localFilter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_TOGGLE_FLOATING_LYRICS)
            addAction(ACTION_REQUEST_OVERLAY_PERMISSION)
            addAction(ACTION_TOGGLE_PLAY_MODE)
            addAction(PlayerActivity.ACTION_SEEK_TO)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(localControlReceiver, localFilter)
    }

    private fun registerCallStateListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("MusicPlaybackService", "READ_PHONE_STATE not granted, call state listener disabled")
            return
        }
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        @Suppress("DEPRECATION")
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun unregisterCallStateListener() {
        @Suppress("DEPRECATION")
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        telephonyManager = null
    }

    private fun handleCallStateChanged(state: Int) {
        val previousState = lastCallState
        lastCallState = state
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (previousState != state) {
                    pausePlaybackForCallIfNeeded()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                manualPlayOverrideDuringCall = false
            }
        }
    }

    private fun pausePlaybackForCallIfNeeded() {
        val player = exoPlayer ?: return
        if (!player.isPlaying) return
        if (manualPlayOverrideDuringCall) return
        player.pause()
        updateNotification()
    }

    private fun markManualPlaybackDuringCallIfNeeded() {
        if (lastCallState != TelephonyManager.CALL_STATE_IDLE) {
            manualPlayOverrideDuringCall = true
        }
    }

    private fun handleUserPlayPauseToggle() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            markManualPlaybackDuringCallIfNeeded()
            player.play()
        }
        updateNotification()
    }

    private fun handleUserSkipNext() {
        markManualPlaybackDuringCallIfNeeded()
        serviceScope.launch {
            playNextSong()
        }
    }

    private fun handleUserSkipPrevious() {
        markManualPlaybackDuringCallIfNeeded()
        serviceScope.launch {
            playPreviousSong()
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY_PAUSE -> {
                    handleUserPlayPauseToggle()
                }
                ACTION_NEXT -> {
                    handleUserSkipNext()
                }
                ACTION_PREVIOUS -> {
                    handleUserSkipPrevious()
                }
                ACTION_TOGGLE_FLOATING_LYRICS -> {
                    toggleFloatingLyricsFromNotification()
                }
                ACTION_REQUEST_OVERLAY_PERMISSION -> {
                    openOverlayPermissionSettings()
                }
                ACTION_TOGGLE_PLAY_MODE -> {
                    togglePlayModeFromNotification()
                }
                ACTION_STOP -> stopSelf()
                PlayerActivity.ACTION_SEEK_TO -> {
                    val seekPosition = intent.getLongExtra(PlayerActivity.EXTRA_SEEK_POSITION, -1L)
                    if (seekPosition >= 0L) {
                        seekToPosition(seekPosition)
                    }
                }
            }
        }
    }

    private val localControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY_PAUSE -> {
                    handleUserPlayPauseToggle()
                }
                ACTION_NEXT -> {
                    handleUserSkipNext()
                }
                ACTION_PREVIOUS -> {
                    handleUserSkipPrevious()
                }
                ACTION_TOGGLE_FLOATING_LYRICS -> {
                    toggleFloatingLyricsFromNotification()
                }
                ACTION_REQUEST_OVERLAY_PERMISSION -> {
                    openOverlayPermissionSettings()
                }
                ACTION_TOGGLE_PLAY_MODE -> {
                    togglePlayModeFromNotification()
                }
                PlayerActivity.ACTION_SEEK_TO -> {
                    val seekPosition = intent.getLongExtra(PlayerActivity.EXTRA_SEEK_POSITION, -1L)
                    if (seekPosition >= 0L) {
                        seekToPosition(seekPosition)
                    }
                }
            }
        }
    }

    private fun seekToPosition(seekPosition: Long) {
        val player = exoPlayer ?: return
        val duration = player.duration
        val target = if (duration > 0L && duration != C.TIME_UNSET) {
            seekPosition.coerceIn(0L, duration)
        } else {
            seekPosition.coerceAtLeast(0L)
        }
        player.seekTo(target)
        updateNotification()
        broadcastFloatingLyricsPlaybackState()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logNotificationAvailability("onStartCommand:beforeStartForeground")
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(notificationLogTag, "startForeground success, notificationId=$NOTIFICATION_ID")
        } catch (e: Exception) {
            Log.e(notificationLogTag, "startForeground failed: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
        if (exoPlayer?.isPlaying == true) {
            startNotificationProgressUpdates()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音乐播放通知"
                setSound(null, null)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            val actualChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
            Log.d(
                notificationLogTag,
                "createNotificationChannel done, channelId=$CHANNEL_ID, importance=${actualChannel?.importance}, " +
                    "name=${actualChannel?.name}, canBypassDnd=${actualChannel?.canBypassDnd()}"
            )
        }
    }

    private fun createNotification(): Notification {
        val player = exoPlayer ?: return createEmptyNotification()

        val isPlaying = player.isPlaying
        val songName = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "未知歌曲"
        val artist = player.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "未知艺术家"
        val (displaySongName, displayArtist) = resolveNotificationMarqueeTexts(songName, artist)
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        val currentTime = formatTime(position)
        val totalTime = if (duration > 0L) formatTime(duration) else "--:--"
        val coverBitmap = currentNotificationCover
        val maxProgress = 1000
        val progress = if (duration > 0L) {
            ((position.coerceAtMost(duration) * maxProgress) / duration).toInt().coerceIn(0, maxProgress)
        } else {
            0
        }
        val uiStyle = resolveNotificationUiStyle(coverBitmap)

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_RESTORE)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val compactView = createNotificationRemoteViews(
            layoutId = R.layout.notification_music_playback_compact,
            songName = displaySongName,
            artist = displayArtist,
            currentTime = currentTime,
            totalTime = totalTime,
            coverBitmap = coverBitmap,
            progress = progress,
            maxProgress = maxProgress,
            isIndeterminate = duration <= 0L,
            playPauseIcon = playPauseIcon,
            uiStyle = uiStyle
        )
        val expandedView = createNotificationRemoteViews(
            layoutId = R.layout.notification_music_playback_expanded,
            songName = displaySongName,
            artist = displayArtist,
            currentTime = currentTime,
            totalTime = totalTime,
            coverBitmap = coverBitmap,
            progress = progress,
            maxProgress = maxProgress,
            isIndeterminate = duration <= 0L,
            playPauseIcon = playPauseIcon,
            uiStyle = uiStyle
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(songName)
            .setContentText(artist)
            .setSubText("$currentTime / $totalTime")
            .setSmallIcon(R.drawable.ic_play)
            .setLargeIcon(coverBitmap)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setProgress(maxProgress, progress, false)
            .setCustomContentView(compactView)
            .setCustomBigContentView(expandedView)
            .setCustomHeadsUpContentView(compactView)
            .build()
    }

    private fun resolveNotificationMarqueeTexts(songName: String, artist: String): Pair<String, String> {
        val sourceKey = "$songName|$artist"
        val now = SystemClock.elapsedRealtime()
        if (notificationMarqueeSourceKey != sourceKey) {
            notificationMarqueeSourceKey = sourceKey
            notificationMarqueeStartMs = now
        }

        val step = ((now - notificationMarqueeStartMs) / 250L).toInt().coerceAtLeast(0)
        val titleWindowSize = 19
        val artistWindowSize = 22

        return buildNotificationMarqueeText(songName, titleWindowSize, step) to
            buildNotificationMarqueeText(artist, artistWindowSize, step)
    }

    private fun buildNotificationMarqueeText(source: String, windowSize: Int, step: Int): String {
        val text = source.trim()
        if (text.isEmpty() || text.length <= windowSize) return text
        val spacer = "   ·   "
        val loop = text + spacer
        val doubled = loop + loop
        val start = step % loop.length
        return doubled.substring(start, start + windowSize)
    }

    private fun createNotificationRemoteViews(
        layoutId: Int,
        songName: String,
        artist: String,
        currentTime: String,
        totalTime: String,
        coverBitmap: Bitmap?,
        progress: Int,
        maxProgress: Int,
        isIndeterminate: Boolean,
        playPauseIcon: Int,
        uiStyle: NotificationUiStyle
    ): RemoteViews {
        return RemoteViews(packageName, layoutId).apply {
            setTextViewText(R.id.notificationTitle, songName)
            setTextViewText(R.id.notificationArtist, artist)
            setTextColor(R.id.notificationTitle, uiStyle.primaryTextColor)
            setTextColor(R.id.notificationArtist, uiStyle.secondaryTextColor)
            setTextViewText(R.id.notificationTimeCurrent, currentTime)
            setTextViewText(R.id.notificationTimeTotal, totalTime)
            setTextColor(R.id.notificationTimeCurrent, uiStyle.timeTextColor)
            setTextColor(R.id.notificationTimeTotal, uiStyle.timeTextColor)
            setProgressBar(R.id.notificationProgress, maxProgress, progress, false)
            setProgressBar(R.id.notificationProgressDark, maxProgress, progress, false)
            setBoolean(R.id.notificationProgress, "setIndeterminate", false)
            setBoolean(R.id.notificationProgressDark, "setIndeterminate", false)
            setViewVisibility(
                R.id.notificationProgress,
                if (uiStyle.useDarkProgressStyle) View.GONE else View.VISIBLE
            )
            setViewVisibility(
                R.id.notificationProgressDark,
                if (uiStyle.useDarkProgressStyle) View.VISIBLE else View.GONE
            )
            setInt(R.id.notificationScrim, "setBackgroundResource", uiStyle.scrimBackgroundRes)

            if (coverBitmap != null) {
                setImageViewBitmap(R.id.notificationBackground, coverBitmap)
            } else {
                setImageViewResource(R.id.notificationBackground, R.drawable.bg_notification_default_background)
            }

            setImageViewResource(R.id.notificationPlayPause, playPauseIcon)
            setImageViewResource(R.id.notificationPrevious, R.drawable.ic_skip_previous)
            setImageViewResource(R.id.notificationNext, R.drawable.ic_skip_next)
            setImageViewResource(R.id.notificationPlayMode, resolvePlayModeIconRes())
            val lyricsToggleOn = FloatingLyricsService.isRunning
            val activeColor = Color.parseColor("#FFE53935")
            val inactiveColor = resolveNotificationLyricsInactiveColor(uiStyle = uiStyle)
            setTextViewText(R.id.notificationLyricsToggle, "词")
            setTextColor(R.id.notificationLyricsToggle, if (lyricsToggleOn) activeColor else inactiveColor)
            setInt(R.id.notificationLyricsToggle, "setBackgroundResource", uiStyle.controlBackgroundRes)
            setInt(R.id.notificationPrevious, "setColorFilter", uiStyle.iconTintColor)
            setInt(R.id.notificationPlayPause, "setColorFilter", uiStyle.iconTintColor)
            setInt(R.id.notificationNext, "setColorFilter", uiStyle.iconTintColor)
            setInt(R.id.notificationPlayMode, "setColorFilter", uiStyle.iconTintColor)
            setInt(R.id.notificationPrevious, "setBackgroundResource", uiStyle.controlBackgroundRes)
            setInt(R.id.notificationPlayPause, "setBackgroundResource", uiStyle.controlBackgroundRes)
            setInt(R.id.notificationNext, "setBackgroundResource", uiStyle.controlBackgroundRes)
            setInt(R.id.notificationPlayMode, "setBackgroundResource", uiStyle.controlBackgroundRes)

            val mainIntent = Intent(this@MusicPlaybackService, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_RESTORE)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val mainPendingIntent = PendingIntent.getActivity(
                this@MusicPlaybackService,
                100,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setOnClickPendingIntent(R.id.notificationBackground, mainPendingIntent)

            val playPauseIntent = PendingIntent.getBroadcast(
                this@MusicPlaybackService,
                101,
                Intent(ACTION_PLAY_PAUSE).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nextIntent = PendingIntent.getBroadcast(
                this@MusicPlaybackService,
                102,
                Intent(ACTION_NEXT).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val previousIntent = PendingIntent.getBroadcast(
                this@MusicPlaybackService,
                103,
                Intent(ACTION_PREVIOUS).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val toggleLyricsIntent = if (!PermissionHelper.hasOverlayPermission(this@MusicPlaybackService)) {
                createOverlayPermissionPendingIntent() ?: PendingIntent.getBroadcast(
                    this@MusicPlaybackService,
                    104,
                    Intent(ACTION_REQUEST_OVERLAY_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getBroadcast(
                    this@MusicPlaybackService,
                    104,
                    Intent(ACTION_TOGGLE_FLOATING_LYRICS).setPackage(packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val togglePlayModeIntent = PendingIntent.getBroadcast(
                this@MusicPlaybackService,
                105,
                Intent(ACTION_TOGGLE_PLAY_MODE).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            setOnClickPendingIntent(R.id.notificationPlayPause, playPauseIntent)
            setOnClickPendingIntent(R.id.notificationNext, nextIntent)
            setOnClickPendingIntent(R.id.notificationPrevious, previousIntent)
            setOnClickPendingIntent(R.id.notificationLyricsToggle, toggleLyricsIntent)
            setOnClickPendingIntent(R.id.notificationPlayMode, togglePlayModeIntent)
        }
    }

    private fun createEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("音乐播放器")
            .setContentText("准备中...")
            .setSmallIcon(R.drawable.ic_play)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        logNotificationAvailability("updateNotification")
        val notificationManager = getSystemService(NotificationManager::class.java)
        try {
            notificationManager.notify(NOTIFICATION_ID, createNotification())
            Log.d(notificationLogTag, "notify success, notificationId=$NOTIFICATION_ID")
        } catch (e: Exception) {
            Log.e(notificationLogTag, "notify failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun logNotificationAvailability(stage: String) {
        val runtimePermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val channelImportance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_ID)
                ?.importance
        } else {
            null
        }
        val channelBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelImportance == NotificationManager.IMPORTANCE_NONE
        } else {
            false
        }
        Log.d(
            notificationLogTag,
            "stage=$stage, sdk=${Build.VERSION.SDK_INT}, runtimePermissionGranted=$runtimePermissionGranted, " +
                "notificationsEnabled=$notificationsEnabled, channelId=$CHANNEL_ID, channelImportance=$channelImportance, " +
                "channelBlocked=$channelBlocked"
        )
        if (!runtimePermissionGranted || !notificationsEnabled || channelBlocked) {
            Log.w(
                notificationLogTag,
                "notification unavailable at $stage: runtimePermissionGranted=$runtimePermissionGranted, " +
                    "notificationsEnabled=$notificationsEnabled, channelBlocked=$channelBlocked"
            )
        }
    }

    private fun startNotificationProgressUpdates() {
        if (notificationProgressJob?.isActive == true) return
        notificationProgressJob = serviceScope.launch {
            while (isActive) {
                updateNotification()
                broadcastFloatingLyricsPlaybackState()
                delay(250L)
            }
        }
    }

    private fun stopNotificationProgressUpdates() {
        notificationProgressJob?.cancel()
        notificationProgressJob = null
    }

    private fun loadNotificationCoverAsync(
        songId: String,
        platform: String,
        coverReference: String?,
        songName: String,
        artist: String
    ) {
        val normalizedPlatform = platform.lowercase(Locale.getDefault())
        val cacheKey = buildNotificationCoverKey(songId, normalizedPlatform, coverReference)
        if (cacheKey == currentNotificationCoverKey && currentNotificationCover != null) {
            return
        }

        serviceScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                resolveNotificationCoverBitmap(songId, normalizedPlatform, coverReference, songName, artist)
            }
            if (bitmap != null && cacheKey == buildNotificationCoverKey(songId, normalizedPlatform, coverReference)) {
                currentNotificationCover = bitmap
                currentNotificationCoverKey = cacheKey
                updateMediaMetadata(songName, artist, bitmap)
            }
        }
    }

    private suspend fun resolveNotificationCoverBitmap(
        songId: String,
        platform: String,
        coverReference: String?,
        songName: String,
        artist: String
    ): Bitmap? {
        var reference = coverReference?.trim().orEmpty()

        // 检测并修复混合 URL（远程 URL + 本地路径）
        // 例如：https://p2.music.126.net/...==//data/user/0/...
        if (reference.startsWith("http", ignoreCase = true)) {
            val localPathIndex = reference.indexOf("//data/user/0/")
            if (localPathIndex > 0) {
                // 提取本地路径部分
                reference = reference.substring(localPathIndex)
            } else {
                val cachePathIndex = reference.indexOf("/data/user/0/")
                if (cachePathIndex > 0) {
                    reference = reference.substring(cachePathIndex)
                }
            }
        }

        // 如果是本地文件路径，去除 URL 参数（如 ?param=500y500）
        if (reference.startsWith("/")) {
            val queryIndex = reference.indexOf("?")
            if (queryIndex > 0) {
                reference = reference.substring(0, queryIndex)
            }
        }

        if (reference.isNotEmpty()) {
            if (reference.startsWith("http", ignoreCase = true)) {
                val cachedPath = com.tacke.music.utils.CoverImageManager.downloadAndCacheCoverByUrl(
                    context = this,
                    songId = songId,
                    platform = platform,
                    coverUrl = reference
                )
                if (!cachedPath.isNullOrBlank()) {
                    return BitmapFactory.decodeFile(cachedPath)
                } else {
                    return null
                }
            } else {
                val file = File(reference)
                if (file.exists()) {
                    return BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    return com.tacke.music.utils.CoverImageManager.loadCoverBitmap(this, songId, platform)
                }
            }
        } else {
            return com.tacke.music.utils.CoverImageManager.loadCoverBitmap(this, songId, platform)
        }
    }

    private fun buildNotificationCoverKey(songId: String, platform: String, coverReference: String?): String {
        return "$songId|${platform.lowercase(Locale.getDefault())}|${coverReference.orEmpty().trim()}"
    }

    private fun resolveNotificationUiStyle(@Suppress("UNUSED_PARAMETER") coverBitmap: Bitmap?): NotificationUiStyle {
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        return if (isNightMode) {
            NotificationUiStyle(
                primaryTextColor = Color.WHITE,
                secondaryTextColor = Color.parseColor("#E6FFFFFF"),
                timeTextColor = Color.parseColor("#FFFFFFFF"),
                iconTintColor = Color.WHITE,
                controlBackgroundRes = R.drawable.bg_notification_control_circle_dark,
                scrimBackgroundRes = R.drawable.bg_notification_scrim_dark,
                useDarkProgressStyle = false
            )
        } else {
            NotificationUiStyle(
                primaryTextColor = Color.parseColor("#FF111827"),
                secondaryTextColor = Color.parseColor("#D9111827"),
                timeTextColor = Color.parseColor("#FF111827"),
                iconTintColor = Color.parseColor("#FF111827"),
                controlBackgroundRes = R.drawable.bg_notification_control_circle_light,
                scrimBackgroundRes = R.drawable.bg_notification_scrim_light,
                useDarkProgressStyle = true
            )
        }
    }

    private fun formatTime(millis: Long): String {
        val safeMillis = millis.coerceAtLeast(0L)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(safeMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(safeMillis) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun applyAlpha(color: Int, alphaRatio: Float): Int {
        val alpha = (Color.alpha(color) * alphaRatio).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun resolveNotificationLyricsInactiveColor(uiStyle: NotificationUiStyle): Int {
        return uiStyle.iconTintColor
    }

    private fun resolvePlayModeIconRes(): Int {
        return when (playlistManager.playMode.value) {
            PlaylistManager.PLAY_MODE_SEQUENTIAL -> R.drawable.ic_sequential
            PlaylistManager.PLAY_MODE_SHUFFLE -> R.drawable.ic_shuffle
            PlaylistManager.PLAY_MODE_REPEAT_LIST -> R.drawable.ic_repeat
            PlaylistManager.PLAY_MODE_REPEAT_ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_sequential
        }
    }

    private fun toggleFloatingLyricsFromNotification() {
        if (FloatingLyricsService.isRunning) {
            val hideIntent = Intent(this, FloatingLyricsService::class.java).apply {
                action = FloatingLyricsService.ACTION_HIDE
            }
            startService(hideIntent)
            updateNotification()
            return
        }

        if (!PermissionHelper.hasOverlayPermission(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限以启用悬浮歌词", Toast.LENGTH_SHORT).show()
            openOverlayPermissionSettings()
            return
        }

        val currentSong = playlistManager.getCurrentSong()
        val lyrics = currentSong?.let { playbackPreferences.getSongDetail(it.id)?.lyrics }
        val showIntent = Intent(this, FloatingLyricsService::class.java).apply {
            action = FloatingLyricsService.ACTION_SHOW
            putExtra(FloatingLyricsService.EXTRA_SONG_ID, currentSong?.id ?: "")
            putExtra(FloatingLyricsService.EXTRA_SONG_NAME, currentSong?.name ?: "")
            putExtra(FloatingLyricsService.EXTRA_SONG_ARTISTS, currentSong?.artists ?: "")
            putExtra(FloatingLyricsService.EXTRA_LYRICS, lyrics ?: "")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(showIntent)
        } else {
            startService(showIntent)
        }
        // 通知栏开启悬浮歌词时，立即补发一次歌曲与播放进度，避免首帧不同步
        serviceScope.launch {
            delay(250L)
            broadcastFloatingLyricsSongState()
            broadcastFloatingLyricsPlaybackState()
        }
        updateNotification()
    }

    private fun openOverlayPermissionSettings() {
        val settingsIntent = PermissionHelper.findFirstResolvableIntent(
            this,
            PermissionHelper.buildOverlayPermissionIntents(this)
        )?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (settingsIntent != null) {
            startActivity(settingsIntent)
            Log.d(notificationLogTag, "openOverlayPermissionSettings success")
        } else {
            Log.w(notificationLogTag, "openOverlayPermissionSettings failed: no resolvable intent")
            Toast.makeText(this, "当前设备无法打开悬浮窗设置页", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createOverlayPermissionPendingIntent(): PendingIntent? {
        val settingsIntent = PermissionHelper.findFirstResolvableIntent(
            this,
            PermissionHelper.buildOverlayPermissionIntents(this)
        )?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return null

        return PendingIntent.getActivity(
            this,
            106,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun togglePlayModeFromNotification() {
        val newMode = playlistManager.togglePlayMode()
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_PLAY_MODE_CHANGED).apply {
                putExtra(EXTRA_PLAY_MODE, newMode)
            }
        )
        updateNotification()
    }

    private fun updateMediaSessionState() {
        val player = exoPlayer ?: return
        val playbackState = if (player.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(playbackState, player.currentPosition, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )
    }

    fun updateMediaMetadata(title: String?, artist: String?, albumArt: android.graphics.Bitmap?) {
        currentNotificationCover = albumArt
        currentNotificationCoverKey = if (albumArt != null) "inline:${title.orEmpty()}|${artist.orEmpty()}" else null
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
            .build()
        mediaSession.setMetadata(metadata)
        updateNotification()
    }

    private fun broadcastFloatingLyricsPlaybackState() {
        val player = exoPlayer ?: return
        val playbackIntent = Intent(FloatingLyricsService.ACTION_UPDATE_PLAYBACK).apply {
            putExtra(FloatingLyricsService.EXTRA_CURRENT_POSITION, player.currentPosition.coerceAtLeast(0L))
            putExtra(FloatingLyricsService.EXTRA_IS_PLAYING, player.isPlaying)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(playbackIntent)
    }

    private fun broadcastFloatingLyricsSongState() {
        val currentSong = playlistManager.getCurrentSong() ?: return
        val detail = playbackPreferences.getSongDetail(currentSong.id)
        val songIntent = Intent(FloatingLyricsService.ACTION_SONG_CHANGED).apply {
            putExtra(FloatingLyricsService.EXTRA_SONG_ID, currentSong.id)
            putExtra(FloatingLyricsService.EXTRA_SONG_NAME, currentSong.name)
            putExtra(FloatingLyricsService.EXTRA_SONG_ARTISTS, currentSong.artists)
            putExtra(FloatingLyricsService.EXTRA_LYRICS, detail?.lyrics)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(songIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(localControlReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        try {
            unregisterCallStateListener()
        } catch (e: Exception) {
            // Ignore if not registered
        }
        stopNotificationProgressUpdates()
        mediaSession.release()
        exoPlayer?.release()
        exoPlayer = null
    }
}
