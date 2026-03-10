package com.tacke.music.ui

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tacke.music.util.ImmersiveStatusBarHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.databinding.ActivityLyricsBinding
import com.tacke.music.ui.adapter.LyricsAdapter
import java.util.Locale
import java.util.concurrent.TimeUnit

class LyricsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLyricsBinding
    private lateinit var lyricsAdapter: LyricsAdapter
    private lateinit var gestureDetector: GestureDetector
    private lateinit var playbackReceiver: BroadcastReceiver
    private lateinit var layoutManager: LinearLayoutManager

    private var parsedLyrics: List<Pair<Long, String>> = emptyList()
    private var currentLyricIndex = -1

    // 播放状态
    private var currentPosition: Long = 0
    private var duration: Long = 0
    private var isPlaying: Boolean = false
    private var isTracking = false

    // 歌词滚动状态
    private var isUserScrolling = false
    private var userScrollTimeout: android.os.Handler? = null
    private val SCROLL_TIMEOUT = 5000L // 5秒后恢复自动居中
    
    companion object {
        const val REQUEST_CODE = 1001
        const val ACTION_REQUEST_PLAYBACK_STATUS = "com.tacke.music.REQUEST_PLAYBACK_STATUS"

        fun startForResult(
            activity: AppCompatActivity,
            songName: String,
            songArtists: String,
            coverUrl: String?,
            lyrics: String?,
            currentPosition: Long,
            duration: Long,
            isPlaying: Boolean
        ) {
            val intent = Intent(activity, LyricsActivity::class.java).apply {
                putExtra("song_name", songName)
                putExtra("song_artists", songArtists)
                putExtra("cover_url", coverUrl)
                putExtra("lyrics", lyrics)
                putExtra("current_position", currentPosition)
                putExtra("duration", duration)
                putExtra("is_playing", isPlaying)
            }
            activity.startActivityForResult(intent, REQUEST_CODE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLyricsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置沉浸式状态栏 - 使用渐变背景模式（深色背景，白色状态栏图标）
        ImmersiveStatusBarHelper.setupWithGradientBackground(
            activity = this,
            headerViewId = R.id.toolbar,
            contentViewId = null
        )

        setupRecyclerView()
        setupSeekBar()
        setupClickListeners()
        setupGestureDetector()
        setupPlaybackReceiver()
        loadSongInfo()
    }

    private fun setupPlaybackReceiver() {
        playbackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    when (it.action) {
                        PlayerActivity.ACTION_PLAYBACK_UPDATE -> {
                            currentPosition = it.getLongExtra(PlayerActivity.EXTRA_CURRENT_POSITION, currentPosition)
                            duration = it.getLongExtra(PlayerActivity.EXTRA_DURATION, duration)
                            isPlaying = it.getBooleanExtra(PlayerActivity.EXTRA_IS_PLAYING, isPlaying)

                            // 更新UI
                            updateProgressUI()
                            updateLyricsHighlight(currentPosition)
                        }
                    }
                }
            }
        }
    }

    private fun updateProgressUI() {
        if (!isTracking) {
            binding.seekBar.max = duration.toInt()
            binding.seekBar.progress = currentPosition.toInt()
            binding.tvCurrentTime.text = formatTime(currentPosition)
            binding.tvTotalTime.text = formatTime(duration)
        }
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatTime(progress.toLong())
                    // 实时发送进度更新
                    val intent = Intent(PlayerActivity.ACTION_SEEK_TO).apply {
                        putExtra(PlayerActivity.EXTRA_SEEK_POSITION, progress.toLong())
                    }
                    LocalBroadcastManager.getInstance(this@LyricsActivity).sendBroadcast(intent)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isTracking = false
                val seekPosition = binding.seekBar.progress.toLong()
                // 发送跳转广播
                val intent = Intent(PlayerActivity.ACTION_SEEK_TO).apply {
                    putExtra(PlayerActivity.EXTRA_SEEK_POSITION, seekPosition)
                }
                LocalBroadcastManager.getInstance(this@LyricsActivity).sendBroadcast(intent)
                
                // 立即更新当前位置
                currentPosition = seekPosition
                // 立即更新歌词高亮
                updateLyricsHighlight(seekPosition)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // 注册广播接收器
        val filter = IntentFilter().apply {
            addAction(PlayerActivity.ACTION_PLAYBACK_UPDATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(playbackReceiver, filter)

        // 请求当前播放状态
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_REQUEST_PLAYBACK_STATUS))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playbackReceiver)
    }

    private fun setupRecyclerView() {
        lyricsAdapter = LyricsAdapter { position ->
            // 点击歌词，跳转到对应时间
            val time = if (position < parsedLyrics.size) parsedLyrics[position].first else 0
            seekTo(time)
        }

        layoutManager = LinearLayoutManager(this)
        binding.rvLyrics.layoutManager = layoutManager
        binding.rvLyrics.adapter = lyricsAdapter

        // 添加顶部和底部间距，使歌词可以滚动到中间
        binding.rvLyrics.addItemDecoration(LyricsItemDecoration())

        // 监听用户滚动事件
        binding.rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        // 用户开始拖动
                        isUserScrolling = true
                        showLocateButton()
                        // 取消之前的超时任务
                        userScrollTimeout?.removeCallbacksAndMessages(null)
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        // 滚动停止，设置超时后恢复自动居中
                        userScrollTimeout?.removeCallbacksAndMessages(null)
                        userScrollTimeout = android.os.Handler(android.os.Looper.getMainLooper())
                        userScrollTimeout?.postDelayed({
                            isUserScrolling = false
                            hideLocateButton()
                            // 恢复居中显示当前歌词
                            if (currentLyricIndex >= 0) {
                                scrollToCenter(currentLyricIndex)
                            }
                        }, SCROLL_TIMEOUT)
                    }
                }
            }
        })
    }

    private fun showLocateButton() {
        binding.btnLocateCurrent?.visibility = View.VISIBLE
    }

    private fun hideLocateButton() {
        binding.btnLocateCurrent?.visibility = View.GONE
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 定位到当前播放歌词按钮
        binding.btnLocateCurrent?.setOnClickListener {
            isUserScrolling = false
            hideLocateButton()
            if (currentLyricIndex >= 0) {
                scrollToCenter(currentLyricIndex)
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

                // 检测右滑返回
                if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY)) {
                    if (kotlin.math.abs(diffX) > SWIPE_THRESHOLD && kotlin.math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            // 右滑 - 返回
                            finish()
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

    private fun loadSongInfo() {
        // 从 Intent 获取歌曲信息
        val songName = intent.getStringExtra("song_name") ?: "未知"
        val songArtists = intent.getStringExtra("song_artists") ?: "未知"
        val coverUrl = intent.getStringExtra("cover_url")
        val lyricsText = intent.getStringExtra("lyrics")
        currentPosition = intent.getLongExtra("current_position", 0)
        duration = intent.getLongExtra("duration", 0)
        isPlaying = intent.getBooleanExtra("is_playing", false)

        binding.tvSongName.text = songName
        binding.tvArtist.text = songArtists

        // 加载背景图片
        loadBackground(coverUrl)

        // 解析歌词
        lyricsText?.let { text ->
            parsedLyrics = parseLyrics(text)
            if (parsedLyrics.isNotEmpty()) {
                lyricsAdapter.submitList(parsedLyrics.map { it.second })
                // 高亮当前播放的歌词
                updateLyricsHighlight(currentPosition)
            } else {
                lyricsAdapter.submitList(listOf("纯音乐，请欣赏"))
            }
        } ?: run {
            parsedLyrics = emptyList()
            lyricsAdapter.submitList(listOf("暂无歌词"))
        }
        
        // 初始化进度条
        binding.seekBar.max = duration.toInt()
        binding.seekBar.progress = currentPosition.toInt()
        binding.tvCurrentTime.text = formatTime(currentPosition)
        binding.tvTotalTime.text = formatTime(duration)
    }

    private fun updateLyricsHighlight(position: Long) {
        if (parsedLyrics.isEmpty()) return

        var newIndex = -1
        for (i in parsedLyrics.indices) {
            if (parsedLyrics[i].first <= position) {
                newIndex = i
            } else {
                break
            }
        }

        if (newIndex != currentLyricIndex && newIndex >= 0) {
            currentLyricIndex = newIndex
            lyricsAdapter.setCurrentPosition(newIndex)

            // 只有在用户没有手动滚动时才自动居中
            if (!isUserScrolling) {
                scrollToCenter(newIndex)
            }
        }
    }

    private fun scrollToCenter(position: Int) {
        val layoutManager = binding.rvLyrics.layoutManager as? LinearLayoutManager ?: return
        
        // 计算 RecyclerView 的中心位置
        val recyclerViewHeight = binding.rvLyrics.height
        if (recyclerViewHeight <= 0) return
        
        // 使用 SmoothScroller 滚动到目标位置并居中
        val smoothScroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_START
            }
            
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                // 计算将视图居中的偏移量
                val viewHeight = viewEnd - viewStart
                val boxHeight = boxEnd - boxStart
                return (boxStart + boxHeight / 2) - (viewStart + viewHeight / 2)
            }
        }
        smoothScroller.targetPosition = position
        layoutManager.startSmoothScroll(smoothScroller)
    }

    private fun parseLyrics(lyricsText: String): List<Pair<Long, String>> {
        val lyricsList = mutableListOf<Pair<Long, String>>()
        val lines = lyricsText.lines()

        for (line in lines) {
            val regex = """\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""".toRegex()
            val matchResult = regex.find(line)
            if (matchResult != null) {
                val minutes = matchResult.groupValues[1].toLong()
                val seconds = matchResult.groupValues[2].toLong()
                val millis = matchResult.groupValues[3].toLong() * if (matchResult.groupValues[3].length == 2) 10 else 1
                val text = matchResult.groupValues[4].trim()
                val timeMillis = TimeUnit.MINUTES.toMillis(minutes) + TimeUnit.SECONDS.toMillis(seconds) + millis
                if (text.isNotEmpty()) {
                    lyricsList.add(timeMillis to text)
                }
            }
        }
        return lyricsList.sortedBy { it.first }
    }

    private fun seekTo(time: Long) {
        val intent = Intent(PlayerActivity.ACTION_SEEK_TO).apply {
            putExtra(PlayerActivity.EXTRA_SEEK_POSITION, time)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        
        // 立即更新当前位置和高亮
        currentPosition = time
        updateLyricsHighlight(time)
        binding.seekBar.progress = time.toInt()
        binding.tvCurrentTime.text = formatTime(time)
    }

    private fun loadBackground(coverUrl: String?) {
        if (!coverUrl.isNullOrEmpty()) {
            // 加载背景图片
            Glide.with(this)
                .load(coverUrl)
                .placeholder(R.drawable.bg_default_light_cyan)
                .error(R.drawable.bg_default_light_cyan)
                .into(binding.ivBackground)
            
            // 显示背景图片
            binding.ivBackground.visibility = View.VISIBLE
        } else {
            // 没有封面时隐藏背景图片
            binding.ivBackground.visibility = View.GONE
        }
    }

    private fun formatTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

}
