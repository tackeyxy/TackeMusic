package com.tacke.music.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tacke.music.R
import com.tacke.music.data.preferences.PlaybackPreferences
import com.tacke.music.ui.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class FloatingLyricsService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var lyricsContainer: FrameLayout? = null
    private var lyricsDisplayContainer: LinearLayout? = null
    private var topControlsContainer: LinearLayout? = null
    private var bottomControlsContainer: LinearLayout? = null
    private var tvCurrentLyric: TextView? = null
    private var tvNextLyric: TextView? = null
    private var btnLock: ImageView? = null
    private var btnClose: ImageView? = null
    private var btnPrevious: ImageView? = null
    private var btnPlayPause: ImageView? = null
    private var btnNext: ImageView? = null
    private var btnFontDecrease: ImageView? = null
    private var btnFontIncrease: ImageView? = null
    private var colorPickerContainer: LinearLayout? = null
    
    private var isLocked = false
    private var isPlaying = false
    private var currentLyricColor = -1 // 默认使用主题色
    private var currentLyricSize = 20f // sp
    private var nextLyricSize = 14f // sp
    private var hideControlsRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var parsedLyrics: List<Pair<Long, String>> = emptyList()
    private var currentSongId: String = ""
    private var currentSongName: String = ""
    private var currentSongArtists: String = ""
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    
    // 8种颜色选项（移除白色和蓝灰色）
    private val colorOptions = listOf(
        0xFF4FC3F7.toInt(), // 浅蓝
        0xFF81C784.toInt(), // 浅绿
        0xFFFFF176.toInt(), // 浅黄
        0xFFFF8A65.toInt(), // 浅橙
        0xFFF06292.toInt(), // 粉红
        0xFFBA68C8.toInt(), // 浅紫
        0xFF4DD0E1.toInt(), // 青色
        0xFFA1887F.toInt()  // 棕色
    )
    
    companion object {
        private const val TAG = "FloatingLyricsService"
        private const val CHANNEL_ID = "floating_lyrics_channel"
        private const val NOTIFICATION_ID = 2
        private const val PREFS_NAME = "floating_lyrics_prefs"
        private const val KEY_IS_LOCKED = "is_locked"
        private const val KEY_LYRIC_COLOR = "lyric_color"
        private const val KEY_LYRIC_SIZE = "lyric_size"
        private const val KEY_POS_X = "pos_x"
        private const val KEY_POS_Y = "pos_y"
        private const val KEY_FIRST_TIME = "first_time"
        
        const val ACTION_SHOW = "com.tacke.music.ACTION_SHOW_FLOATING_LYRICS"
        const val ACTION_HIDE = "com.tacke.music.ACTION_HIDE_FLOATING_LYRICS"
        const val ACTION_UPDATE_LYRICS = "com.tacke.music.ACTION_UPDATE_LYRICS"
        const val ACTION_UPDATE_PLAYBACK = "com.tacke.music.ACTION_UPDATE_PLAYBACK"
        const val ACTION_SONG_CHANGED = "com.tacke.music.ACTION_SONG_CHANGED"
        const val ACTION_RESET_POSITION = "com.tacke.music.ACTION_RESET_POSITION"
        
        const val EXTRA_LYRICS = "lyrics"
        const val EXTRA_CURRENT_POSITION = "current_position"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_SONG_ID = "song_id"
        const val EXTRA_SONG_NAME = "song_name"
        const val EXTRA_SONG_ARTISTS = "song_artists"
        
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadSettings()
        createNotificationChannel()
        registerReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_SHOW -> {
                // 先保存传入的歌曲信息
                val songId = intent.getStringExtra(EXTRA_SONG_ID) ?: ""
                val songName = intent.getStringExtra(EXTRA_SONG_NAME) ?: ""
                val songArtists = intent.getStringExtra(EXTRA_SONG_ARTISTS) ?: ""
                val lyrics = intent.getStringExtra(EXTRA_LYRICS) ?: ""
                
                if (floatingView == null) {
                    createFloatingWindow()
                }
                
                // 更新歌曲信息（如果有）
                if (songId.isNotEmpty()) {
                    onSongChanged(songId, songName, songArtists, lyrics)
                }
                
                startForeground(NOTIFICATION_ID, createNotification())
                isRunning = true
            }
            ACTION_HIDE -> {
                removeFloatingWindow()
                stopSelf()
                isRunning = false
            }
            ACTION_UPDATE_LYRICS -> {
                val lyrics = intent.getStringExtra(EXTRA_LYRICS)
                lyrics?.let { updateLyrics(it) }
            }
            ACTION_UPDATE_PLAYBACK -> {
                val position = intent.getLongExtra(EXTRA_CURRENT_POSITION, 0)
                val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                updatePlaybackState(position, playing)
            }
            ACTION_SONG_CHANGED -> {
                val songId = intent.getStringExtra(EXTRA_SONG_ID) ?: ""
                val songName = intent.getStringExtra(EXTRA_SONG_NAME) ?: ""
                val songArtists = intent.getStringExtra(EXTRA_SONG_ARTISTS) ?: ""
                val lyrics = intent.getStringExtra(EXTRA_LYRICS)
                onSongChanged(songId, songName, songArtists, lyrics)
            }
            ACTION_RESET_POSITION -> {
                resetPosition()
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        removeFloatingWindow()
        unregisterReceivers()
        saveSettings()
        isRunning = false
    }

    private fun loadSettings() {
        isLocked = prefs.getBoolean(KEY_IS_LOCKED, false)
        currentLyricColor = prefs.getInt(KEY_LYRIC_COLOR, -1)
        currentLyricSize = prefs.getFloat(KEY_LYRIC_SIZE, 20f)
        nextLyricSize = currentLyricSize * 0.7f
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putBoolean(KEY_IS_LOCKED, isLocked)
            putInt(KEY_LYRIC_COLOR, currentLyricColor)
            putFloat(KEY_LYRIC_SIZE, currentLyricSize)
            apply()
        }
    }

    private fun createFloatingWindow() {
        Log.d(TAG, "createFloatingWindow")
        
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.layout_floating_lyrics, null)
        
        // 初始化视图
        lyricsContainer = floatingView?.findViewById(R.id.lyricsContainer)
        lyricsDisplayContainer = floatingView?.findViewById(R.id.lyricsDisplayContainer)
        topControlsContainer = floatingView?.findViewById(R.id.topControlsContainer)
        bottomControlsContainer = floatingView?.findViewById(R.id.bottomControlsContainer)
        tvCurrentLyric = floatingView?.findViewById(R.id.tvCurrentLyric)
        tvNextLyric = floatingView?.findViewById(R.id.tvNextLyric)
        btnLock = floatingView?.findViewById(R.id.btnLock)
        btnClose = floatingView?.findViewById(R.id.btnClose)
        btnPrevious = floatingView?.findViewById(R.id.btnPrevious)
        btnPlayPause = floatingView?.findViewById(R.id.btnPlayPause)
        btnNext = floatingView?.findViewById(R.id.btnNext)
        btnFontDecrease = floatingView?.findViewById(R.id.btnFontDecrease)
        btnFontIncrease = floatingView?.findViewById(R.id.btnFontIncrease)
        colorPickerContainer = floatingView?.findViewById(R.id.colorPickerContainer)
        
        // 设置歌词颜色
        updateLyricColor()
        updateLyricSize()
        
        // 设置颜色选择器
        setupColorPicker()
        
        // 设置控件点击事件
        setupControlListeners()
        
        // 设置触摸事件
        setupTouchListener()
        
        // 设置锁定状态
        updateLockState()
        
        // 创建窗口参数
        val params = createWindowParams()
        
        try {
            windowManager.addView(floatingView, params)
            Log.d(TAG, "Floating window added")
            
            // 确保初始状态：显示背景和控制栏
            lyricsContainer?.setBackgroundResource(R.drawable.bg_floating_lyrics)
            topControlsContainer?.visibility = View.VISIBLE
            bottomControlsContainer?.visibility = View.VISIBLE
            
            // 延迟隐藏控件
            scheduleHideControls()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating window", e)
        }
    }

    private fun createWindowParams(): WindowManager.LayoutParams {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        
        // 适配刘海屏和挖孔屏
        val layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            0
        }
        
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            
            // 设置刘海屏适配模式
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                this.layoutInDisplayCutoutMode = layoutInDisplayCutoutMode
            }
            
            // 检查是否是首次开启
            val isFirstTime = prefs.getBoolean(KEY_FIRST_TIME, true)
            
            if (isFirstTime) {
                // 首次开启：重置位置在屏幕顶部2/3高度且水平居中
                val metrics = getDisplayMetrics()
                x = (metrics.widthPixels - dpToPx(360)) / 2
                y = (metrics.heightPixels * 2 / 3)

                // 标记为非首次
                prefs.edit().putBoolean(KEY_FIRST_TIME, false).apply()

                // 保存初始位置
                prefs.edit().apply {
                    putInt(KEY_POS_X, x)
                    putInt(KEY_POS_Y, y)
                    apply()
                }
            } else {
                // 从保存的位置恢复
                val savedX = prefs.getInt(KEY_POS_X, -1)
                val savedY = prefs.getInt(KEY_POS_Y, -1)
                if (savedX >= 0 && savedY >= 0) {
                    x = savedX
                    y = savedY
                } else {
                    // 默认位置：屏幕顶部2/3高度且水平居中
                    val metrics = getDisplayMetrics()
                    x = (metrics.widthPixels - dpToPx(360)) / 2
                    y = (metrics.heightPixels * 2 / 3)
                }
            }
        }
    }
    
    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics
    }
    
    private fun getScreenWidth(): Int {
        return getDisplayMetrics().widthPixels
    }
    
    private fun getScreenHeight(): Int {
        return getDisplayMetrics().heightPixels
    }

    private fun setupColorPicker() {
        colorPickerContainer?.removeAllViews()
        
        colorOptions.forEach { color ->
            val colorView = createColorCircle(color)
            colorPickerContainer?.addView(colorView)
        }
    }

    private fun createColorCircle(color: Int): View {
        // 增大颜色圆圈尺寸和间隙，使颜色块更容易点击
        val size = dpToPx(22)
        val margin = dpToPx(4)

        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size + margin * 2, size + margin * 2)
        }

        val circle = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            setOnClickListener {
                currentLyricColor = color
                updateLyricColor()
                setupColorPicker() // 刷新选中状态
                scheduleHideControls()
            }
        }

        // 如果当前选中此颜色，添加勾选标记
        if (color == currentLyricColor) {
            val checkMark = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dpToPx(14),
                    dpToPx(14)
                ).apply {
                    gravity = Gravity.CENTER
                }
                setImageResource(R.drawable.ic_check)
                setColorFilter(0xFFFFFFFF.toInt())
            }
            container.addView(circle)
            container.addView(checkMark)
        } else {
            container.addView(circle)
        }

        return container
    }

    private fun setupControlListeners() {
        // 锁定按钮
        btnLock?.setOnClickListener {
            isLocked = !isLocked
            updateLockState()
            scheduleHideControls()
        }
        
        // 关闭按钮
        btnClose?.setOnClickListener {
            stopSelf()
        }
        
        // 上一曲
        btnPrevious?.setOnClickListener {
            sendPlaybackControl(MusicPlaybackService.ACTION_PREVIOUS)
            scheduleHideControls()
        }
        
        // 播放/暂停
        btnPlayPause?.setOnClickListener {
            sendPlaybackControl(MusicPlaybackService.ACTION_PLAY_PAUSE)
            scheduleHideControls()
        }
        
        // 下一曲
        btnNext?.setOnClickListener {
            sendPlaybackControl(MusicPlaybackService.ACTION_NEXT)
            scheduleHideControls()
        }
        
        // 字体减小
        btnFontDecrease?.setOnClickListener {
            if (currentLyricSize > 12f) {
                currentLyricSize -= 2f
                updateLyricSize()
            }
            scheduleHideControls()
        }
        
        // 字体增大
        btnFontIncrease?.setOnClickListener {
            if (currentLyricSize < 32f) {
                currentLyricSize += 2f
                updateLyricSize()
            }
            scheduleHideControls()
        }
    }

    private fun setupTouchListener() {
        floatingView?.let { view ->
            val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isLocked) {
                        // 锁定状态下双击显示控件
                        showControls()
                        scheduleHideControls()
                        return true
                    }
                    return false
                }

                override fun onLongPress(e: MotionEvent) {
                    // 长按事件 - 在未锁定状态下开始拖动
                    if (!isLocked) {
                        // 长按后显示控件，准备拖动
                        showControls()
                    }
                }
            })

            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f
            var isDragging = false
            var hasMoved = false

            // 为歌词显示区域设置点击监听器，用于显示控件
            lyricsDisplayContainer?.setOnClickListener {
                if (!isLocked) {
                    showControls()
                    scheduleHideControls()
                }
            }

            // 将触摸监听器设置给根容器，确保能接收所有触摸事件
            lyricsContainer?.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val params = view.layoutParams as WindowManager.LayoutParams
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        isDragging = false
                        hasMoved = false

                        // 如果控件是隐藏的，显示它们
                        if (topControlsContainer?.visibility != View.VISIBLE) {
                            showControls()
                        }
                        return@setOnTouchListener true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (isLocked) return@setOnTouchListener true

                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()

                        // 检测是否开始移动（超过阈值）
                        if (!hasMoved && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                            hasMoved = true
                            isDragging = true
                        }

                        if (isDragging) {
                            val params = view.layoutParams as WindowManager.LayoutParams
                            params.x = initialX + dx
                            params.y = initialY + dy

                            val screenWidth = getScreenWidth()
                            val screenHeight = getScreenHeight()
                            val viewWidth = view.width
                            val viewHeight = view.height

                            val minX = -viewWidth + dpToPx(50)
                            val maxX = screenWidth - dpToPx(50)
                            val minY = dpToPx(24)
                            val maxY = screenHeight - dpToPx(50)

                            params.x = params.x.coerceIn(minX, maxX)
                            params.y = params.y.coerceIn(minY, maxY)

                            windowManager.updateViewLayout(view, params)

                            prefs.edit().apply {
                                putInt(KEY_POS_X, params.x)
                                putInt(KEY_POS_Y, params.y)
                                apply()
                            }
                        }
                        return@setOnTouchListener true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!isDragging && !isLocked) {
                            // 如果没有拖动且未锁定，显示控件并启动隐藏计时
                            showControls()
                            scheduleHideControls()
                        } else if (isDragging) {
                            // 如果发生了拖动，启动隐藏计时
                            scheduleHideControls()
                        }
                        isDragging = false
                        return@setOnTouchListener true
                    }
                }
                false
            }

            // 为歌词显示容器设置触摸监听器，支持点击显示控件和双击检测
            lyricsDisplayContainer?.setOnTouchListener { _, event ->
                // 首先将事件传递给手势检测器（用于检测双击）
                val gestureHandled = gestureDetector.onTouchEvent(event)
                if (gestureHandled) {
                    return@setOnTouchListener true
                }

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 记录触摸起始位置
                        val params = view.layoutParams as WindowManager.LayoutParams
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        isDragging = false
                        hasMoved = false
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isLocked) return@setOnTouchListener true

                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()

                        if (!hasMoved && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) {
                            hasMoved = true
                            isDragging = true
                        }

                        if (isDragging) {
                            val params = view.layoutParams as WindowManager.LayoutParams
                            params.x = initialX + dx
                            params.y = initialY + dy

                            val screenWidth = getScreenWidth()
                            val screenHeight = getScreenHeight()

                            params.x = params.x.coerceIn(-view.width + dpToPx(50), screenWidth - dpToPx(50))
                            params.y = params.y.coerceIn(dpToPx(24), screenHeight - dpToPx(50))

                            windowManager.updateViewLayout(view, params)

                            prefs.edit().apply {
                                putInt(KEY_POS_X, params.x)
                                putInt(KEY_POS_Y, params.y)
                                apply()
                            }
                        }
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging && !isLocked) {
                            // 点击歌词区域显示控件
                            showControls()
                            scheduleHideControls()
                        } else if (isDragging) {
                            scheduleHideControls()
                        }
                        isDragging = false
                        return@setOnTouchListener true
                    }
                }
                false
            }

            // 为控制栏设置触摸监听器，防止事件冲突
            topControlsContainer?.setOnTouchListener { _, event ->
                // 控制栏区域不处理拖动，让事件传递给子视图（按钮）
                false
            }
            bottomControlsContainer?.setOnTouchListener { _, event ->
                // 控制栏区域不处理拖动，让事件传递给子视图（按钮）
                false
            }
        }
    }

    private fun updateLockState() {
        btnLock?.setImageResource(
            if (isLocked) R.drawable.ic_lock else R.drawable.ic_lock_open
        )
        
        // 更新背景可点击性
        if (isLocked) {
            // 锁定状态下，背景不拦截事件，允许点击穿透
            lyricsContainer?.isClickable = false
        } else {
            lyricsContainer?.isClickable = true
        }
    }

    private fun updateLyricColor() {
        val color = if (currentLyricColor != -1) currentLyricColor else {
            ContextCompat.getColor(this, R.color.primary)
        }
        tvCurrentLyric?.setTextColor(color)
    }

    private fun updateLyricSize() {
        tvCurrentLyric?.textSize = currentLyricSize
        nextLyricSize = currentLyricSize * 0.7f
        tvNextLyric?.textSize = nextLyricSize
    }

    private fun showControls() {
        val needAnimation = topControlsContainer?.visibility != View.VISIBLE
        
        // 显示控制栏和背景
        topControlsContainer?.visibility = View.VISIBLE
        bottomControlsContainer?.visibility = View.VISIBLE
        
        // 显示背景并恢复padding
        lyricsContainer?.setBackgroundResource(R.drawable.bg_floating_lyrics)
        lyricsContainer?.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        
        // 恢复歌词区域的padding（为控制栏留出空间）
        lyricsDisplayContainer?.setPadding(
            dpToPx(8),
            dpToPx(48),
            dpToPx(8),
            dpToPx(48)
        )
        
        if (needAnimation) {
            val animation = AlphaAnimation(0f, 1f).apply {
                duration = 200
            }
            topControlsContainer?.startAnimation(animation)
            bottomControlsContainer?.startAnimation(animation)
        }
    }

    private fun hideControls() {
        if (topControlsContainer?.visibility == View.VISIBLE) {
            val animation = AlphaAnimation(1f, 0f).apply {
                duration = 200
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(animation: Animation?) {}
                    override fun onAnimationEnd(animation: Animation?) {
                        topControlsContainer?.visibility = View.GONE
                        bottomControlsContainer?.visibility = View.GONE
                        
                        // 隐藏背景，仅显示歌词 - 使用透明背景
                        lyricsContainer?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        lyricsContainer?.setPadding(0, 0, 0, 0)
                        
                        // 移除歌词区域的padding，使歌词居中显示
                        lyricsDisplayContainer?.setPadding(0, 0, 0, 0)
                    }
                    override fun onAnimationRepeat(animation: Animation?) {}
                })
            }
            topControlsContainer?.startAnimation(animation)
            bottomControlsContainer?.startAnimation(animation)
        }
    }

    private fun scheduleHideControls() {
        hideControlsRunnable?.let { handler.removeCallbacks(it) }
        hideControlsRunnable = Runnable {
            hideControls()
        }
        handler.postDelayed(hideControlsRunnable!!, 3000)
    }

    private fun updateLyrics(lyricsText: String) {
        parsedLyrics = parseLyrics(lyricsText)
    }

    private fun updatePlaybackState(position: Long, playing: Boolean) {
        isPlaying = playing
        btnPlayPause?.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        
        // 更新歌词显示
        updateLyricsDisplay(position)
    }

    private fun updateLyricsDisplay(currentPosition: Long) {
        if (parsedLyrics.isEmpty()) {
            tvCurrentLyric?.text = currentSongName
            tvNextLyric?.text = currentSongArtists
            return
        }
        
        var currentIndex = -1
        for (i in parsedLyrics.indices) {
            if (parsedLyrics[i].first <= currentPosition) {
                currentIndex = i
            } else {
                break
            }
        }
        
        if (currentIndex >= 0) {
            tvCurrentLyric?.text = parsedLyrics[currentIndex].second
            val nextIndex = currentIndex + 1
            if (nextIndex < parsedLyrics.size) {
                tvNextLyric?.text = parsedLyrics[nextIndex].second
            } else {
                tvNextLyric?.text = ""
            }
        } else {
            tvCurrentLyric?.text = parsedLyrics.firstOrNull()?.second ?: currentSongName
            tvNextLyric?.text = if (parsedLyrics.size > 1) parsedLyrics[1].second else currentSongArtists
        }
    }

    private fun onSongChanged(songId: String, songName: String, songArtists: String, lyrics: String?) {
        currentSongId = songId
        currentSongName = songName
        currentSongArtists = songArtists
        
        // 更新歌词数据
        if (!lyrics.isNullOrEmpty()) {
            updateLyrics(lyrics)
            Log.d(TAG, "歌词已更新，共 ${parsedLyrics.size} 行")
        } else {
            parsedLyrics = emptyList()
            Log.d(TAG, "歌词为空，显示歌曲信息")
        }
        
        // 立即更新歌词显示
        updateLyricsDisplay(0)
        
        // 更新通知
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun resetPosition() {
        floatingView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            val metrics = getDisplayMetrics()
            
            // 重置到屏幕顶部2/3高度且水平居中
            params.x = (metrics.widthPixels - view.width) / 2
            params.y = (metrics.heightPixels * 2 / 3)
            
            // 确保位置有效
            params.x = params.x.coerceAtLeast(0)
            params.y = params.y.coerceAtLeast(dpToPx(24))
            
            windowManager.updateViewLayout(view, params)
            
            // 保存新位置
            prefs.edit().apply {
                putInt(KEY_POS_X, params.x)
                putInt(KEY_POS_Y, params.y)
                apply()
            }
            
            Log.d(TAG, "Position reset to x=${params.x}, y=${params.y}")
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

    private fun sendPlaybackControl(action: String) {
        val intent = Intent(action)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun removeFloatingWindow() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating window", e)
            }
            floatingView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮歌词",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮歌词服务通知"
                setSound(null, null)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_RESTORE)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val hideIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingLyricsService::class.java).apply {
                action = ACTION_HIDE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮歌词")
            .setContentText(if (isPlaying) "正在播放: $currentSongName" else "已暂停")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, "关闭悬浮歌词", hideIntent)
            .setOngoing(true)
            .build()
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_LYRICS)
            addAction(ACTION_UPDATE_PLAYBACK)
            addAction(ACTION_SONG_CHANGED)
            addAction(ACTION_RESET_POSITION)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, filter)
    }

    private fun unregisterReceivers() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            // 忽略未注册的错误
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_LYRICS -> {
                    val lyrics = intent.getStringExtra(EXTRA_LYRICS)
                    lyrics?.let { updateLyrics(it) }
                }
                ACTION_UPDATE_PLAYBACK -> {
                    val position = intent.getLongExtra(EXTRA_CURRENT_POSITION, 0)
                    val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                    updatePlaybackState(position, playing)
                }
                ACTION_SONG_CHANGED -> {
                    val songId = intent.getStringExtra(EXTRA_SONG_ID) ?: ""
                    val songName = intent.getStringExtra(EXTRA_SONG_NAME) ?: ""
                    val songArtists = intent.getStringExtra(EXTRA_SONG_ARTISTS) ?: ""
                    val lyrics = intent.getStringExtra(EXTRA_LYRICS)
                    onSongChanged(songId, songName, songArtists, lyrics)
                }
                ACTION_RESET_POSITION -> {
                    resetPosition()
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
