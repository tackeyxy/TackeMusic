package com.tacke.music.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.util.Log
import com.tacke.music.R
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.SongDetail
import com.tacke.music.data.preferences.PlaybackPreferences
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicPlaybackService : Service() {

    private var exoPlayer: ExoPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private val binder = MusicBinder()
    private lateinit var playlistManager: PlaylistManager
    private lateinit var playbackPreferences: PlaybackPreferences
    private val repository = MusicRepository()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentQuality = "320k"

    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.tacke.music.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.tacke.music.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.tacke.music.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.tacke.music.ACTION_STOP"
        const val ACTION_SONG_CHANGED = "com.tacke.music.ACTION_SONG_CHANGED"
        const val EXTRA_SONG_ID = "song_id"
        const val EXTRA_SONG_NAME = "song_name"
        const val EXTRA_SONG_ARTISTS = "song_artists"
        const val EXTRA_SONG_URL = "song_url"
        const val EXTRA_SONG_COVER = "song_cover"
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
        createNotificationChannel()
        initializePlayer()
        initializeMediaSession()
        registerBroadcastReceiver()
    }

    // 加载并播放指定歌曲
    private suspend fun loadAndPlaySong(playlistSong: PlaylistSong) {
        val platform = try {
            MusicRepository.Platform.valueOf(playlistSong.platform.uppercase())
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }

        // 使用带缓存的Repository获取歌曲详情
        val cachedRepository = CachedMusicRepository(this@MusicPlaybackService)
        val detail = withContext(Dispatchers.IO) {
            cachedRepository.getSongDetail(
                platform = platform,
                songId = playlistSong.id,
                quality = currentQuality,
                songName = playlistSong.name,
                artists = playlistSong.artists
            )
        }

        if (detail != null) {
            playSong(detail, playlistSong)
        }
    }

    // 播放歌曲
    private fun playSong(detail: SongDetail, playlistSong: PlaylistSong) {
        val player = exoPlayer ?: return

        // 更新MediaSession元数据
        updateMediaMetadata(
            playlistSong.name,
            playlistSong.artists,
            null // 封面Bitmap可以后续添加
        )

        // 设置MediaItem并播放
        val mediaItem = MediaItem.Builder()
            .setUri(detail.url)
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

        // 保存播放状态
        playbackPreferences.currentSongId = playlistSong.id
        playbackPreferences.saveSongDetail(playlistSong.id, detail)

        // 发送广播通知UI更新
        val intent = Intent(ACTION_SONG_CHANGED).apply {
            putExtra(EXTRA_SONG_ID, playlistSong.id)
            putExtra(EXTRA_SONG_NAME, playlistSong.name)
            putExtra(EXTRA_SONG_ARTISTS, playlistSong.artists)
            putExtra(EXTRA_SONG_URL, detail.url)
            putExtra(EXTRA_SONG_COVER, detail.cover ?: playlistSong.coverUrl)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // 直接发送歌词更新广播给悬浮歌词服务（确保悬浮歌词能及时更新）
        sendFloatingLyricsUpdate(detail, playlistSong)
    }

    /**
     * 发送歌词更新广播给悬浮歌词服务
     */
    private fun sendFloatingLyricsUpdate(detail: SongDetail, playlistSong: PlaylistSong) {
        val intent = Intent(FloatingLyricsService.ACTION_UPDATE_LYRICS).apply {
            putExtra(FloatingLyricsService.EXTRA_LYRICS, detail.lyrics)
            putExtra(FloatingLyricsService.EXTRA_SONG_NAME, playlistSong.name)
            putExtra(FloatingLyricsService.EXTRA_ARTISTS, playlistSong.artists)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
                updateMediaSessionState()
                // 发送播放状态变化广播给悬浮歌词
                sendPlaybackStateChangedBroadcast(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMediaSessionState()
                if (playbackState == Player.STATE_ENDED) {
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

        // 检查是否是URL过期导致的错误（403 Forbidden）
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {

            // 避免重复刷新
            if (isRetrying) return
            isRetrying = true

            val currentSong = playlistManager.getCurrentSong()
            if (currentSong != null) {
                currentSongForRetry = currentSong
                serviceScope.launch {
                    try {
                        // 清除过期的缓存
                        playbackPreferences.clearSongDetail(currentSong.id)

                        // 重新获取歌曲详情
                        val cachedRepository = CachedMusicRepository(this@MusicPlaybackService)
                        val platform = try {
                            MusicRepository.Platform.valueOf(currentSong.platform.uppercase())
                        } catch (e: Exception) {
                            MusicRepository.Platform.KUWO
                        }

                        val newDetail = withContext(Dispatchers.IO) {
                            cachedRepository.getSongDetail(
                                platform = platform,
                                songId = currentSong.id,
                                quality = currentQuality,
                                songName = currentSong.name,
                                artists = currentSong.artists
                            )
                        }

                        if (newDetail != null) {
                            // 使用新URL重新播放
                            playSong(newDetail, currentSong)
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

    /**
     * 发送播放状态变化广播给悬浮歌词服务
     */
    private fun sendPlaybackStateChangedBroadcast(isPlaying: Boolean) {
        val intent = Intent(FloatingLyricsService.ACTION_PLAYBACK_STATE_CHANGED).apply {
            putExtra(FloatingLyricsService.EXTRA_IS_PLAYING, isPlaying)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
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
                    exoPlayer?.play()
                }

                override fun onPause() {
                    exoPlayer?.pause()
                }

                override fun onSkipToNext() {
                    serviceScope.launch {
                        playNextSong()
                    }
                }

                override fun onSkipToPrevious() {
                    serviceScope.launch {
                        playPreviousSong()
                    }
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
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(localControlReceiver, localFilter)
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY_PAUSE -> {
                    exoPlayer?.let {
                        if (it.isPlaying) it.pause() else it.play()
                    }
                }
                ACTION_NEXT -> {
                    serviceScope.launch {
                        playNextSong()
                    }
                }
                ACTION_PREVIOUS -> {
                    serviceScope.launch {
                        playPreviousSong()
                    }
                }
                ACTION_STOP -> stopSelf()
            }
        }
    }

    private val localControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY_PAUSE -> {
                    exoPlayer?.let {
                        if (it.isPlaying) it.pause() else it.play()
                    }
                }
                ACTION_NEXT -> {
                    serviceScope.launch {
                        playNextSong()
                    }
                }
                ACTION_PREVIOUS -> {
                    serviceScope.launch {
                        playPreviousSong()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
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
        }
    }

    private fun createNotification(): Notification {
        val player = exoPlayer ?: return createEmptyNotification()

        val isPlaying = player.isPlaying
        val songName = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "未知歌曲"
        val artist = player.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "未知艺术家"

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseTitle = if (isPlaying) "暂停" else "播放"

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_RESTORE)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val previousIntent = PendingIntent.getBroadcast(
            this, 3, Intent(ACTION_PREVIOUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            this, 4, Intent(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(songName)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(pendingIntent)
            .setOngoing(isPlaying)
            .addAction(R.drawable.ic_back, "上一首", previousIntent)
            .addAction(playPauseIcon, playPauseTitle, playPauseIntent)
            .addAction(R.drawable.ic_queue, "下一首", nextIntent)
            .addAction(R.drawable.ic_delete, "停止", stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun createEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("音乐播放器")
            .setContentText("准备中...")
            .setSmallIcon(R.drawable.ic_play)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
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
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
            .build()
        mediaSession.setMetadata(metadata)
        updateNotification()
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
        mediaSession.release()
        exoPlayer?.release()
        exoPlayer = null
    }
}
