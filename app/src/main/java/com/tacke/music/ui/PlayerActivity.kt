package com.tacke.music.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.SongDetail
import com.tacke.music.data.preferences.PlaybackPreferences
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.RecentPlayRepository
import com.tacke.music.databinding.ActivityPlayerBinding
import com.tacke.music.data.model.Song
import com.tacke.music.download.DownloadManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.service.MusicPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var albumRotationAnimator: ObjectAnimator? = null
    private var parsedLyrics: List<Pair<Long, String>> = emptyList()
    private lateinit var gestureDetector: GestureDetector
    private lateinit var seekReceiver: BroadcastReceiver
    private var loadingToast: Toast? = null

    private lateinit var playlistManager: PlaylistManager
    private lateinit var playbackPreferences: PlaybackPreferences
    private lateinit var recentPlayRepository: RecentPlayRepository
    private var isFromEmptyState = false

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

            // 如果 Activity 是新启动的（不是从通知进入），处理新歌曲
            if (!isFromEmptyState && intent.getStringExtra("song_id") != null) {
                val currentMediaItem = exoPlayer?.currentMediaItem
                val isSameSong = currentMediaItem?.mediaId == songId

                if (!isSameSong) {
                    // 不同的歌曲，重新加载
                    loadSongDetail(currentQuality)
                } else {
                    // 同一首歌，只更新UI
                    updateUIForCurrentSong()
                }
            } else if (isFromEmptyState) {
                lifecycleScope.launch {
                    setupEmptyState()
                }
            } else {
                // 从通知进入，只更新UI
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
            }
            context.startActivity(intent)
        }

        // 空状态启动（无歌曲）
        fun startEmpty(context: Context) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_LAUNCH_MODE, LAUNCH_MODE_EMPTY)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistManager = PlaylistManager.getInstance(this)
        playbackPreferences = PlaybackPreferences.getInstance(this)
        recentPlayRepository = RecentPlayRepository(this)

        parseIntent()
        setupClickListeners()
        setupAlbumRotation()
        setupGestureDetector()
        setupSeekReceiver()

        // 启动并绑定服务
        val serviceIntent = Intent(this, MusicPlaybackService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 加载播放列表
        lifecycleScope.launch {
            playlistManager.loadPlaylist()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val newLaunchMode = intent.getStringExtra(EXTRA_LAUNCH_MODE) ?: LAUNCH_MODE_NORMAL
        val isNewEmptyState = newLaunchMode == LAUNCH_MODE_EMPTY

        // 如果是从导航栏进入的空状态启动，且当前正在播放歌曲，则不做任何操作
        if (isNewEmptyState && exoPlayer?.currentMediaItem != null) {
            // 已经在播放歌曲，保持当前状态
            return
        }

        // 否则重新解析 intent 并处理
        parseIntent()

        lifecycleScope.launch {
            if (isFromEmptyState) {
                setupEmptyState()
            } else {
                // 检查是否是同一首歌
                val currentMediaItem = exoPlayer?.currentMediaItem
                val currentSong = playlistManager.getCurrentSong()

                if (currentSong?.id == songId && currentMediaItem != null) {
                    // 同一首歌，不需要重新加载，只更新UI
                    updateUIForCurrentSong()
                } else {
                    // 不同的歌，重新加载
                    loadSongDetail(currentQuality)
                }
            }
        }
    }

    private fun setupPlayerListeners() {
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val duration = exoPlayer?.duration ?: 0
                    if (duration > 0) {
                        binding.seekBar.max = duration.toInt()
                        binding.tvTotalTime.text = formatTime(duration)
                    }
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
        })

        // 启动进度更新循环
        lifecycleScope.launch {
            while (true) {
                delay(100)
                exoPlayer?.let { player ->
                    if (!isTracking) {
                        val currentPosition = player.currentPosition
                        binding.seekBar.progress = currentPosition.toInt()
                        binding.tvCurrentTime.text = formatTime(currentPosition)
                        updateLyrics(currentPosition)
                    }
                    updatePlayPauseButton(player.isPlaying)
                    // 发送播放进度广播给歌词页面
                    sendPlaybackUpdate(player.currentPosition, player.duration, player.isPlaying)
                }
            }
        }
    }

    private fun updateUIForCurrentSong() {
        // 更新UI为当前正在播放的歌曲状态
        songDetail?.let { detail ->
            updateUI(detail)
        }
        binding.tvSongName.text = songName
        binding.tvArtist.text = songArtists
        loadCoverAndBackground(songCover)
        binding.seekBar.max = (exoPlayer?.duration ?: 0).toInt()
        binding.tvTotalTime.text = formatTime(exoPlayer?.duration ?: 0)
        updatePlayPauseButton(exoPlayer?.isPlaying == true)
        if (exoPlayer?.isPlaying == true) {
            startAlbumRotation()
        }
    }

    private suspend fun setupEmptyState() {
        // 显示空状态UI
        binding.tvSongName.text = "暂无歌曲"
        binding.tvArtist.text = "播放列表为空"
        binding.ivAlbum.setImageResource(R.drawable.ic_album_default)
        binding.ivBackground.visibility = View.GONE
        binding.tvLyricsCurrent.text = ""
        binding.tvLyricsNext.text = ""
        binding.tvCurrentTime.text = "0:00"
        binding.tvTotalTime.text = "0:00"
        binding.seekBar.progress = 0
        binding.seekBar.max = 0
        updatePlayPauseButton(false)

        // 尝试恢复上次播放状态（播放列表已加载完成）
        val savedSongId = playbackPreferences.currentSongId
        if (savedSongId != null && playlistManager.getCurrentSong() != null) {
            // 有保存的状态，尝试恢复
            restorePlaybackState()
        } else {
            // 真正没有歌曲，显示提示
            Toast.makeText(this@PlayerActivity, "播放列表内并未存在待播放歌曲", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun restorePlaybackState() {
        val currentSong = playlistManager.getCurrentSong()
        if (currentSong != null) {
            // 恢复歌曲信息
            songId = currentSong.id
            songName = currentSong.name
            songArtists = currentSong.artists
            songCover = currentSong.coverUrl
            platform = try {
                MusicRepository.Platform.valueOf(currentSong.platform)
            } catch (e: Exception) {
                MusicRepository.Platform.KUWO
            }
            currentQuality = playbackPreferences.currentQuality

            // 更新UI
            binding.tvSongName.text = songName
            binding.tvArtist.text = songArtists
            loadCoverAndBackground(songCover)

            // 获取歌曲详情并播放
            try {
                val detail = withContext(Dispatchers.IO) {
                    repository.getSongDetail(platform, songId, currentQuality)
                }
                if (detail != null) {
                    songDetail = detail
                    songLyrics = detail.lyrics
                    updateUI(detail)
                    playSong(detail.url)

                    // 恢复播放位置
                    val savedPosition = playbackPreferences.currentPosition
                    if (savedPosition > 0) {
                        exoPlayer?.seekTo(savedPosition)
                    }

                    isFromEmptyState = false
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "恢复播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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

            binding.tvSongName.text = songName
            binding.tvArtist.text = songArtists

            // 加载封面和背景
            loadCoverAndBackground(songCover)

            // 解析歌词
            songLyrics?.let { lyricsText ->
                parsedLyrics = parseLyrics(lyricsText)
            }

            // 初始化音质显示
            updateQualityDisplay(currentQuality)
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
            playing
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

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ACTION_SEEK_TO)
            addAction(LyricsActivity.ACTION_REQUEST_PLAYBACK_STATUS)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(seekReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(seekReceiver)
        // 保存播放进度
        savePlaybackState()
    }

    private fun savePlaybackState() {
        exoPlayer?.let { player ->
            playbackPreferences.currentPosition = player.currentPosition
            playbackPreferences.isPlaying = player.isPlaying
        }
        if (!isFromEmptyState) {
            playbackPreferences.currentSongId = songId
            playbackPreferences.currentQuality = currentQuality
        }
    }

    private fun loadCoverAndBackground(coverUrl: String?) {
        if (!coverUrl.isNullOrEmpty()) {
            // 加载专辑封面
            Glide.with(this)
                .load(coverUrl)
                .placeholder(R.drawable.ic_album_default)
                .into(binding.ivAlbum)

            // 加载背景图片
            Glide.with(this)
                .load(coverUrl)
                .placeholder(R.drawable.bg_default_light_cyan)
                .error(R.drawable.bg_default_light_cyan)
                .into(binding.ivBackground)

            // 显示背景图片
            binding.ivBackground.visibility = View.VISIBLE
        } else {
            // 没有封面时隐藏背景
            binding.ivAlbum.setImageResource(R.drawable.ic_album_default)
            binding.ivBackground.visibility = View.GONE
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
        albumRotationAnimator?.start()
    }

    private fun stopAlbumRotation() {
        albumRotationAnimator?.pause()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnShare.setOnClickListener {
            if (isFromEmptyState) {
                Toast.makeText(this, "暂无歌曲可分享", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "分享功能开发中", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPlayPause.setOnClickListener {
            if (isFromEmptyState) {
                Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    stopAlbumRotation()
                } else {
                    player.play()
                    startAlbumRotation()
                }
            }
        }

        binding.btnPrevious.setOnClickListener {
            if (isFromEmptyState) {
                Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            playPreviousSong()
        }

        binding.btnNext.setOnClickListener {
            if (isFromEmptyState) {
                Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            playNextSong()
        }

        binding.btnShuffle.setOnClickListener {
            val newMode = playlistManager.togglePlayMode()
            val modeName = playlistManager.getPlayModeName()
            Toast.makeText(this, modeName, Toast.LENGTH_SHORT).show()
        }

        binding.btnPlaylist.setOnClickListener {
            showPlaylistDialog()
        }

        binding.btnMore.setOnClickListener {
            if (isFromEmptyState) {
                Toast.makeText(this, "暂无歌曲", Toast.LENGTH_SHORT).show()
            } else {
                showMoreOptions()
            }
        }

        // 点击音质标识可以切换音质
        binding.tvQuality.setOnClickListener {
            if (isFromEmptyState) {
                Toast.makeText(this, "暂无歌曲", Toast.LENGTH_SHORT).show()
            } else {
                showQualityDialog()
            }
        }
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
            // 更新当前歌曲信息
            songId = playlistSong.id
            songName = playlistSong.name
            songArtists = playlistSong.artists
            songCover = playlistSong.coverUrl
            platform = try {
                MusicRepository.Platform.valueOf(playlistSong.platform)
            } catch (e: Exception) {
                MusicRepository.Platform.KUWO
            }

            // 更新UI
            binding.tvSongName.text = songName
            binding.tvArtist.text = songArtists
            loadCoverAndBackground(songCover)

            // 获取歌曲详情
            val detail = withContext(Dispatchers.IO) {
                repository.getSongDetail(platform, songId, currentQuality)
            }

            if (detail != null) {
                songDetail = detail
                songLyrics = detail.lyrics
                updateUI(detail)
                playSong(detail.url)
            } else {
                Toast.makeText(this@PlayerActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this@PlayerActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPlaylistDialog() {
        lifecycleScope.launch {
            // 先加载最新的播放列表
            playlistManager.loadPlaylist()

            val playlist = playlistManager.currentPlaylist.value
            if (playlist.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "播放列表为空", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val songNames = playlist.map { "${it.name} - ${it.artists}" }.toTypedArray()
            val currentIndex = playlistManager.currentIndex.value

            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("播放列表")
                .setSingleChoiceItems(songNames, currentIndex) { dialog, which ->
                    lifecycleScope.launch {
                        playlistManager.setCurrentIndex(which)
                        val song = playlistManager.getCurrentSong()
                        if (song != null) {
                            loadAndPlaySong(song)
                        }
                    }
                    dialog.dismiss()
                }
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空") { _, _ ->
                    lifecycleScope.launch {
                        playlistManager.clearPlaylist()
                        Toast.makeText(this@PlayerActivity, "播放列表已清空", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    private fun showMoreOptions() {
        val options = arrayOf("下载", "选择音质", "添加到歌单", "查看歌手")
        AlertDialog.Builder(this)
            .setTitle("更多选项")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showQualityDialogForDownload()
                    1 -> showQualityDialog()
                    2 -> Toast.makeText(this, "添加到歌单", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this, "查看歌手", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun loadSongDetail(quality: String) {
        // 显示加载中的气泡提示
        showLoadingToast()

        lifecycleScope.launch {
            try {
                // 如果已有URL，直接使用
                if (songUrl.isNotEmpty()) {
                    playSong(songUrl)
                    hideLoadingToast()

                    // 添加到播放列表
                    val playlistSong = PlaylistSong(
                        id = songId,
                        name = songName,
                        artists = songArtists,
                        coverUrl = songCover,
                        platform = platform.name
                    )
                    playlistManager.addSong(playlistSong)
                } else {
                    // 否则从网络获取
                    songDetail = withContext(Dispatchers.IO) {
                        repository.getSongDetail(platform, songId, quality)
                    }
                    hideLoadingToast()
                    songDetail?.let { detail ->
                        songLyrics = detail.lyrics
                        updateUI(detail)
                        playSong(detail.url)

                        // 添加到播放列表
                        val playlistSong = PlaylistSong(
                            id = songId,
                            name = songName,
                            artists = songArtists,
                            coverUrl = detail.cover ?: songCover,
                            platform = platform.name
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
        // 歌曲信息、背景、歌词已在初始化时设置，不再更新
        // 仅更新专辑封面和背景（如果之前没有封面）
        if (songCover == null) {
            detail.cover?.let { coverUrl ->
                // 更新专辑封面
                Glide.with(this)
                    .load(coverUrl)
                    .placeholder(R.drawable.ic_album_default)
                    .into(binding.ivAlbum)

                // 更新背景图片
                Glide.with(this)
                    .load(coverUrl)
                    .placeholder(R.drawable.bg_default_light_cyan)
                    .error(R.drawable.bg_default_light_cyan)
                    .into(binding.ivBackground)

                // 显示背景图片
                binding.ivBackground.visibility = View.VISIBLE
            }
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
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            startAlbumRotation()

            // 记录播放历史
            recordRecentPlay()

            // 更新通知
            musicService?.updateMediaMetadata(songName, songArtists, null)
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
                        platform = platform.name
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
        val qualityText = when (quality.lowercase()) {
            "128k" -> "LQ"
            "320k" -> "HQ"
            "flac" -> "CDQ"
            "flac24bit" -> "HR"
            else -> "HQ"
        }
        binding.tvQuality.text = qualityText
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
                val detail = withContext(Dispatchers.IO) {
                    repository.getSongDetail(platform, songId, quality)
                }
                if (detail != null) {
                    val song = Song(
                        index = 0,
                        id = songId,
                        name = songName,
                        artists = songArtists,
                        coverUrl = songCover
                    )
                    val downloadManager = DownloadManager.getInstance(this@PlayerActivity)
                    val task = downloadManager.createDownloadTask(song, detail, quality)
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
    }
}
