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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tacke.music.R
import com.tacke.music.ui.PlayerActivity

class MusicPlaybackService : Service() {

    private var exoPlayer: ExoPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private val binder = MusicBinder()

    companion object {
        const val CHANNEL_ID = "music_playback_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.tacke.music.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.tacke.music.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.tacke.music.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.tacke.music.ACTION_STOP"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
        fun getPlayer(): ExoPlayer? = exoPlayer
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializePlayer()
        initializeMediaSession()
        registerBroadcastReceiver()
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
                updateMediaSessionState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMediaSessionState()
            }
        })
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
                    sendBroadcast(Intent(ACTION_NEXT))
                }

                override fun onSkipToPrevious() {
                    sendBroadcast(Intent(ACTION_PREVIOUS))
                }

                override fun onStop() {
                    stopSelf()
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
        }
        registerReceiver(controlReceiver, filter)
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY_PAUSE -> {
                    exoPlayer?.let {
                        if (it.isPlaying) it.pause() else it.play()
                    }
                }
                ACTION_NEXT -> sendBroadcast(Intent(ACTION_NEXT))
                ACTION_PREVIOUS -> sendBroadcast(Intent(ACTION_PREVIOUS))
                ACTION_STOP -> stopSelf()
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
        mediaSession.release()
        exoPlayer?.release()
        exoPlayer = null
    }
}
