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
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tacke.music.R
import com.tacke.music.ui.PlayerActivity
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 音乐播放服务
 * 负责后台播放、通知栏显示和媒体会话管理
 */
class MusicPlaybackService : Service() {

    private var exoPlayer: ExoPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private val binder = MusicBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var currentAlbumArt: Bitmap? = null
    private lateinit var playlistManager: PlaylistManager
    private val musicRepository = MusicRepository()
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    // 保存当前歌曲的完整信息，用于从通知栏恢复播放页时显示
    private var currentSongInfo: SongInfo? = null

    // SharedPreferences 用于持久化保存歌曲信息
    private lateinit var prefs: android.content.SharedPreferences

    data class SongInfo(
        val id: String,
        val title: String,
        val artist: String,
        val coverUrl: String?,
        val lyrics: String?
    )

    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.tacke.music.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.tacke.music.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.tacke.music.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.tacke.music.ACTION_STOP"
        const val ACTION_EXIT_APP = "com.tacke.music.ACTION_EXIT_APP"
        const val ACTION_UPDATE_PROGRESS = "com.tacke.music.ACTION_UPDATE_PROGRESS"
        const val ACTION_SONG_CHANGED = "com.tacke.music.ACTION_SONG_CHANGED"

        // SharedPreferences keys
        private const val PREFS_NAME = "music_playback_prefs"
        private const val KEY_SONG_ID = "song_id"
        private const val KEY_SONG_TITLE = "song_title"
        private const val KEY_SONG_ARTIST = "song_artist"
        private const val KEY_SONG_COVER = "song_cover"
        private const val KEY_SONG_LYRICS = "song_lyrics"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
        fun getPlayer(): ExoPlayer? = exoPlayer
        fun getCurrentSongInfo(): SongInfo? = currentSongInfo
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化 SharedPreferences
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 从持久化存储恢复歌曲信息
        restoreSongInfoFromPrefs()
        // 初始化 PlaylistManager
        playlistManager = PlaylistManager.getInstance(this)

        createNotificationChannel()
        initializePlayer()
        initializeMediaSession()
        registerBroadcastReceiver()
    }

    /**
     * 从 SharedPreferences 恢复歌曲信息
     */
    private fun restoreSongInfoFromPrefs() {
        val id = prefs.getString(KEY_SONG_ID, null)
        val title = prefs.getString(KEY_SONG_TITLE, null)
        val artist = prefs.getString(KEY_SONG_ARTIST, null)
        val coverUrl = prefs.getString(KEY_SONG_COVER, null)
        val lyrics = prefs.getString(KEY_SONG_LYRICS, null)

        if (id != null && title != null && artist != null) {
            currentSongInfo = SongInfo(id, title, artist, coverUrl, lyrics)
        }
    }

    /**
     * 保存歌曲信息到 SharedPreferences
     */
    private fun saveSongInfoToPrefs(songInfo: SongInfo?) {
        prefs.edit().apply {
            if (songInfo != null) {
                putString(KEY_SONG_ID, songInfo.id)
                putString(KEY_SONG_TITLE, songInfo.title)
                putString(KEY_SONG_ARTIST, songInfo.artist)
                putString(KEY_SONG_COVER, songInfo.coverUrl)
                putString(KEY_SONG_LYRICS, songInfo.lyrics)
            } else {
                clear()
            }
            apply()
        }
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
                updateMediaSessionState()
                if (isPlaying) {
                    startProgressUpdate()
                } else {
                    stopProgressUpdate()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMediaSessionState()
                if (playbackState == Player.STATE_READY && exoPlayer?.isPlaying == true) {
                    startProgressUpdate()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                updateNotification()
            }
        })
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlaybackService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                private var lastMediaButtonTime = 0L
                private val MEDIA_BUTTON_DEBOUNCE = 800L // 800ms 防抖动

                override fun onPlay() {
                    exoPlayer?.play()
                }

                override fun onPause() {
                    exoPlayer?.pause()
                }

                override fun onSkipToNext() {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastMediaButtonTime < MEDIA_BUTTON_DEBOUNCE) {
                        return
                    }
                    lastMediaButtonTime = currentTime
                    // 直接处理下一首逻辑，不依赖Activity
                    playNextSong()
                    // 同时发送广播通知Activity更新UI（如果Activity处于活动状态）
                    sendBroadcast(Intent(ACTION_NEXT))
                }

                override fun onSkipToPrevious() {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastMediaButtonTime < MEDIA_BUTTON_DEBOUNCE) {
                        return
                    }
                    lastMediaButtonTime = currentTime
                    // 直接处理上一首逻辑，不依赖Activity
                    playPreviousSong()
                    // 同时发送广播通知Activity更新UI（如果Activity处于活动状态）
                    sendBroadcast(Intent(ACTION_PREVIOUS))
                }

                override fun onStop() {
                    stopSelf()
                }

                override fun onSeekTo(pos: Long) {
                    exoPlayer?.seekTo(pos)
                    updateNotification()
                }
            })
            isActive = true
        }
    }

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_STOP)
            addAction(ACTION_EXIT_APP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.registerReceiver(
                this,
                controlReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(controlReceiver, filter)
        }
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
                    // 直接处理下一首逻辑
                    playNextSong()
                }
                ACTION_PREVIOUS -> {
                    // 直接处理上一首逻辑
                    playPreviousSong()
                }
                ACTION_STOP -> stopSelf()
                ACTION_EXIT_APP -> {
                    stopSelf()
                    // 退出应用广播使用全局广播，让应用所有组件都能接收
                    sendBroadcast(Intent("com.tacke.music.ACTION_EXIT_APPLICATION"))
                }
            }
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 1000) // 每秒更新一次
        }
    }

    private fun startProgressUpdate() {
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    private fun stopProgressUpdate() {
        handler.removeCallbacks(progressRunnable)
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

    /**
     * 创建系统自带的媒体通知栏
     */
    private fun createNotification(): Notification {
        val player = exoPlayer ?: return createEmptyNotification()

        val isPlaying = player.isPlaying
        val songName = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "未知歌曲"
        val artist = player.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "未知艺术家"
        val currentPosition = player.currentPosition
        val duration = player.duration.coerceAtLeast(0)

        // 创建控制按钮的 PendingIntents
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

        val exitIntent = PendingIntent.getBroadcast(
            this, 5, Intent(ACTION_EXIT_APP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 点击通知栏进入播放页
        val contentIntent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_RESTORE)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 使用系统自带的媒体通知样式
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(songName)
            .setContentText(artist)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .addAction(R.drawable.ic_skip_previous, "上一首", previousIntent)
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "暂停" else "播放",
                playPauseIntent
            )
            .addAction(R.drawable.ic_skip_next, "下一首", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2) // 显示前三个操作按钮
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(exitIntent)
            )

        // 添加播放时间和进度信息
        val progress = if (duration > 0) {
            ((currentPosition * 100) / duration).toInt()
        } else 0

        notificationBuilder
            .setProgress(100, progress, false)
            .setSubText("${formatTime(currentPosition)} / ${formatTime(duration)}")

        // 设置专辑封面
        currentAlbumArt?.let {
            notificationBuilder.setLargeIcon(it)
        }

        return notificationBuilder.build()
    }

    /**
     * 创建空状态通知 - 播放器未准备好时显示
     */
    private fun createEmptyNotification(): Notification {
        // 创建控制按钮的 PendingIntents
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

        val exitIntent = PendingIntent.getBroadcast(
            this, 5, Intent(ACTION_EXIT_APP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 点击通知栏进入播放页
        val contentIntent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_RESTORE)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("准备中...")
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .addAction(R.drawable.ic_skip_previous, "上一首", previousIntent)
            .addAction(R.drawable.ic_play, "播放", playPauseIntent)
            .addAction(R.drawable.ic_skip_next, "下一首", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(exitIntent)
            )
            .setProgress(100, 0, false)
            .setSubText("0:00 / 0:00")
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
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .build()
        )
    }

    fun updateMediaMetadata(title: String?, artist: String?, albumArt: Bitmap?) {
        currentAlbumArt = albumArt
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
            .build()
        mediaSession.setMetadata(metadata)
        updateNotification()
    }

    /**
     * 更新当前歌曲的完整信息（包括封面URL和歌词）
     * 用于从通知栏恢复播放页时显示
     */
    fun updateCurrentSongInfo(id: String, title: String, artist: String, coverUrl: String?, lyrics: String?) {
        currentSongInfo = SongInfo(id, title, artist, coverUrl, lyrics)
        // 同时保存到 SharedPreferences，确保服务重建后能恢复
        saveSongInfoToPrefs(currentSongInfo)
    }

    /**
     * 获取当前保存的歌曲信息
     */
    fun getCurrentSongInfo(): SongInfo? = currentSongInfo

    /**
     * 播放下一首歌曲
     * 在后台时直接由服务处理，不依赖Activity
     */
    private fun playNextSong() {
        serviceScope.launch {
            try {
                val nextSong = playlistManager.next()
                if (nextSong != null) {
                    // 获取歌曲播放URL
                    val platform = try {
                        MusicRepository.Platform.valueOf(nextSong.platform.uppercase())
                    } catch (e: Exception) {
                        MusicRepository.Platform.KUWO
                    }
                    
                    val songDetail = musicRepository.getSongDetail(platform, nextSong.id, "320k")
                    if (songDetail != null && songDetail.url.isNotEmpty()) {
                        // 播放新歌曲
                        playSong(nextSong, songDetail.url)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 播放上一首歌曲
     * 在后台时直接由服务处理，不依赖Activity
     */
    private fun playPreviousSong() {
        serviceScope.launch {
            try {
                val prevSong = playlistManager.previous()
                if (prevSong != null) {
                    // 获取歌曲播放URL
                    val platform = try {
                        MusicRepository.Platform.valueOf(prevSong.platform.uppercase())
                    } catch (e: Exception) {
                        MusicRepository.Platform.KUWO
                    }
                    
                    val songDetail = musicRepository.getSongDetail(platform, prevSong.id, "320k")
                    if (songDetail != null && songDetail.url.isNotEmpty()) {
                        // 播放新歌曲
                        playSong(prevSong, songDetail.url)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 播放指定歌曲
     */
    private fun playSong(song: com.tacke.music.data.model.PlaylistSong, url: String) {
        exoPlayer?.let { player ->
            // 更新MediaItem
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(song.name)
                        .setArtist(song.artists)
                        .build()
                )
                .build()
            
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            
            // 更新通知和MediaSession
            updateNotification()
            updateMediaSessionState()
            
            // 发送广播通知Activity歌曲已切换
            sendBroadcast(Intent(ACTION_SONG_CHANGED).apply {
                putExtra("song_id", song.id)
                putExtra("song_name", song.name)
                putExtra("song_artists", song.artists)
            })
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${String.format("%02d", seconds)}"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdate()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        mediaSession.release()
        exoPlayer?.release()
        exoPlayer = null
    }
}
