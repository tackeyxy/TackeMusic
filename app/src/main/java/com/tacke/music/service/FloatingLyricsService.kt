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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tacke.music.R
import com.tacke.music.ui.LyricSettingsActivity
import com.tacke.music.ui.PlayerActivity
import java.util.concurrent.TimeUnit

class FloatingLyricsService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var rootContainer: FrameLayout? = null
    private var lyricsContainer: LinearLayout? = null
    private var scrollCurrentLine: HorizontalScrollView? = null
    private var scrollNextLine: HorizontalScrollView? = null
    private var tvCurrentLine: TextView? = null
    private var tvNextLine: TextView? = null
    private var btnLock: ImageView? = null
    private var btnClose: ImageView? = null
    private var btnSettings: ImageView? = null
    private var controlContainer: LinearLayout? = null
    private var playbackControlContainer: LinearLayout? = null
    private var btnPrevious: ImageView? = null
    private var btnPlayPause: ImageView? = null
    private var btnNext: ImageView? = null

    // 快速调整控件
    private var quickSettingsContainer: LinearLayout? = null
    private var btnDecreaseSize: ImageView? = null
    private var btnIncreaseSize: ImageView? = null
    private var tvSizeValue: TextView? = null
    private var seekBarSize: SeekBar? = null
    private var colorPickerContainer: GridLayout? = null
    private var btnResetDefault: TextView? = null

    private var layoutParams: WindowManager.LayoutParams? = null
    private var isLocked = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // 长按相关变量
    private var isLongPress = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    // 控件自动隐藏相关
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private var hideControlsRunnable: Runnable? = null
    private val CONTROLS_SHOW_DURATION = 3000L // 控件显示持续时间（毫秒）
    private var isControlsVisible = false
    private var isQuickSettingsVisible = false

    private var parsedLyrics: List<Pair<Long, String>> = emptyList()
    private var currentPosition: Long = 0
    private var isPlaying = false
    private var currentLyricColor: Int = LyricSettingsActivity.DEFAULT_LYRIC_COLOR
    private var currentLyricSize: Int = LyricSettingsActivity.DEFAULT_LYRIC_SIZE
    private var currentSongName: String = ""
    private var currentArtists: String = ""
    
    // 歌词滚动动画相关
    private var currentLineAnimator: android.animation.ValueAnimator? = null
    private var nextLineAnimator: android.animation.ValueAnimator? = null
    private var currentLyricIndex: Int = -1
    private val SCROLL_DELAY = 1000L // 歌词显示后延迟1秒开始滚动
    private val SCROLL_SPEED_DP_PER_SEC = 30 // 滚动速度：每秒30dp

    // 触摸事件相关
    private val LONG_PRESS_DURATION = 400L // 长按触发时间（毫秒）- 稍微增加以避免误触
    private val MOVE_THRESHOLD = 10f // 移动阈值，超过此值认为是拖动而非点击（减小以提高灵敏度）
    private val DRAG_THRESHOLD = 20f // 拖动阈值，超过此值立即开始拖动（减小以提高灵敏度）

    companion object {
        const val ACTION_SHOW_FLOATING_LYRICS = "com.tacke.music.ACTION_SHOW_FLOATING_LYRICS"
        const val ACTION_HIDE_FLOATING_LYRICS = "com.tacke.music.ACTION_HIDE_FLOATING_LYRICS"
        const val ACTION_UPDATE_LYRICS = "com.tacke.music.ACTION_UPDATE_LYRICS"
        const val ACTION_UPDATE_POSITION = "com.tacke.music.ACTION_UPDATE_POSITION"
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.tacke.music.ACTION_PLAYBACK_STATE_CHANGED"
        const val ACTION_TOGGLE_LOCK = "com.tacke.music.ACTION_TOGGLE_LOCK"
        const val ACTION_UPDATE_SIZE = "com.tacke.music.ACTION_UPDATE_SIZE"
        const val ACTION_UPDATE_COLOR = "com.tacke.music.ACTION_UPDATE_COLOR"

        // 悬浮窗关闭广播通知
        const val ACTION_FLOATING_LYRICS_CLOSED = "com.tacke.music.ACTION_FLOATING_LYRICS_CLOSED"

        // 播放控制广播Action（与MusicPlaybackService保持一致）
        const val ACTION_PLAY_PAUSE = "com.tacke.music.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.tacke.music.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.tacke.music.ACTION_PREVIOUS"

        const val EXTRA_LYRICS = "lyrics"
        const val EXTRA_POSITION = "position"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_SONG_NAME = "song_name"
        const val EXTRA_ARTISTS = "artists"
        const val EXTRA_LYRIC_COLOR = "lyric_color"
        const val EXTRA_IS_LOCKED = "is_locked"
        const val EXTRA_LYRIC_SIZE = "lyric_size"

        private const val PREFS_NAME = "floating_lyrics_prefs"
        private const val KEY_IS_LOCKED = "is_locked"
        private const val KEY_POS_X = "pos_x"
        private const val KEY_POS_Y = "pos_y"
        private const val NOTIFICATION_CHANNEL_ID = "floating_lyrics_channel"
        private const val NOTIFICATION_ID = 1001

        // 字体大小步长
        private const val SIZE_STEP = 10
        // 字体大小范围
        private const val MIN_SIZE = 50
        private const val MAX_SIZE = 200

        fun isRunning(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean("is_running", false)
        }

        fun setRunning(context: Context, running: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_running", running).apply()
        }

        fun getIsLocked(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_IS_LOCKED, false)
        }

        fun setIsLocked(context: Context, locked: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_IS_LOCKED, locked).apply()
        }

        fun savePosition(context: Context, x: Int, y: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply()
        }

        fun getPosition(context: Context): Pair<Int, Int> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val x = prefs.getInt(KEY_POS_X, -1)
            val y = prefs.getInt(KEY_POS_Y, 200)
            return Pair(x, y)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        isLocked = getIsLocked(this)
        currentLyricColor = LyricSettingsActivity.getFloatingLyricColor(this)
        currentLyricSize = LyricSettingsActivity.getFloatingLyricSize(this)
        registerReceivers()
        
        // 初始化时检查悬浮窗权限，如果没有权限则停止服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                android.util.Log.e("FloatingLyrics", "没有悬浮窗权限，停止服务")
                stopSelf()
                return
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_FLOATING_LYRICS -> {
                // 启动前台服务
                startForeground(NOTIFICATION_ID, createNotification())
                if (floatingView == null) {
                    createFloatingWindow()
                }
                updateLyricsFromIntent(intent)
            }
            ACTION_HIDE_FLOATING_LYRICS -> {
                removeFloatingWindow()
                stopForeground(true)
                // 发送广播通知播放页悬浮窗已关闭
                sendFloatingLyricsClosedBroadcast()
                stopSelf()
            }
            ACTION_UPDATE_LYRICS -> {
                updateLyricsFromIntent(intent)
            }
            ACTION_UPDATE_POSITION -> {
                val position = intent.getLongExtra(EXTRA_POSITION, 0)
                updatePosition(position)
            }
            ACTION_PLAYBACK_STATE_CHANGED -> {
                isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
            }
            ACTION_TOGGLE_LOCK -> {
                toggleLock()
            }
            ACTION_UPDATE_SIZE -> {
                val sizePercent = intent.getIntExtra(EXTRA_LYRIC_SIZE, 100)
                currentLyricSize = sizePercent
                updateLyricSize(sizePercent)
                updateQuickSettingsUI()
            }
            ACTION_UPDATE_COLOR -> {
                val color = intent.getIntExtra(EXTRA_LYRIC_COLOR, currentLyricColor)
                currentLyricColor = color
                updateLyricColor(color)
                updateColorPickerSelection()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "悬浮歌词服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮歌词显示的通知"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        val intent = Intent(this, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_RESTORE)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("悬浮歌词运行中")
            .setContentText(currentSongName.ifEmpty { "正在播放" })
            .setSmallIcon(R.drawable.ic_floating_lyrics)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_LYRICS)
            addAction(ACTION_UPDATE_POSITION)
            addAction(ACTION_PLAYBACK_STATE_CHANGED)
            addAction(ACTION_TOGGLE_LOCK)
            addAction(ACTION_UPDATE_SIZE)
            addAction(ACTION_UPDATE_COLOR)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, filter)
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_LYRICS -> updateLyricsFromIntent(intent)
                ACTION_UPDATE_POSITION -> {
                    val position = intent.getLongExtra(EXTRA_POSITION, 0)
                    updatePosition(position)
                }
                ACTION_PLAYBACK_STATE_CHANGED -> {
                    isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                    updatePlayPauseButtonIcon()
                }
                ACTION_TOGGLE_LOCK -> toggleLock()
                ACTION_UPDATE_SIZE -> {
                    val sizePercent = intent.getIntExtra(EXTRA_LYRIC_SIZE, 100)
                    currentLyricSize = sizePercent
                    updateLyricSize(sizePercent)
                    updateQuickSettingsUI()
                }
                ACTION_UPDATE_COLOR -> {
                    val color = intent.getIntExtra(EXTRA_LYRIC_COLOR, currentLyricColor)
                    currentLyricColor = color
                    updateLyricColor(color)
                    updateColorPickerSelection()
                }
            }
        }
    }

    private fun createFloatingWindow() {
        // 再次检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                android.util.Log.e("FloatingLyrics", "createFloatingWindow: 没有悬浮窗权限")
                return
            }
        }
        
        // 使用Application Context并设置主题，避免Service Context没有主题属性
        val themedContext = applicationContext.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                it.createConfigurationContext(it.resources.configuration).apply {
                    setTheme(R.style.Theme_TackeMusic)
                }
            } else {
                it
            }
        }
        
        try {
            floatingView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_lyrics, null)
        } catch (e: Exception) {
            android.util.Log.e("FloatingLyrics", "创建悬浮窗视图失败: ${e.message}")
            e.printStackTrace()
            return
        }

        rootContainer = floatingView?.findViewById(R.id.rootContainer)
        lyricsContainer = floatingView?.findViewById(R.id.lyricsContainer)
        scrollCurrentLine = floatingView?.findViewById(R.id.scrollCurrentLine)
        scrollNextLine = floatingView?.findViewById(R.id.scrollNextLine)
        tvCurrentLine = floatingView?.findViewById(R.id.tvCurrentLine)
        tvNextLine = floatingView?.findViewById(R.id.tvNextLine)
        btnLock = floatingView?.findViewById(R.id.btnLock)
        btnClose = floatingView?.findViewById(R.id.btnClose)
        btnSettings = floatingView?.findViewById(R.id.btnSettings)
        controlContainer = floatingView?.findViewById(R.id.controlContainer)
        playbackControlContainer = floatingView?.findViewById(R.id.playbackControlContainer)
        btnPrevious = floatingView?.findViewById(R.id.btnPrevious)
        btnPlayPause = floatingView?.findViewById(R.id.btnPlayPause)
        btnNext = floatingView?.findViewById(R.id.btnNext)

        // 快速调整控件
        quickSettingsContainer = floatingView?.findViewById(R.id.quickSettingsContainer)
        btnDecreaseSize = floatingView?.findViewById(R.id.btnDecreaseSize)
        btnIncreaseSize = floatingView?.findViewById(R.id.btnIncreaseSize)
        tvSizeValue = floatingView?.findViewById(R.id.tvSizeValue)
        seekBarSize = floatingView?.findViewById(R.id.seekBarSize)
        colorPickerContainer = floatingView?.findViewById(R.id.colorPickerContainer)
        btnResetDefault = floatingView?.findViewById(R.id.btnResetDefault)

        // 设置歌词颜色
        updateLyricColor(currentLyricColor)

        // 应用歌词大小设置
        updateLyricSize(currentLyricSize)

        // 初始化快速调整控件
        setupQuickSettings()

        // 设置按钮点击事件
        btnLock?.setOnClickListener {
            android.util.Log.d("FloatingLyrics", "Lock button clicked, current state: isLocked=$isLocked")
            resetHideControlsTimer()
            toggleLock()
        }

        btnClose?.setOnClickListener {
            android.util.Log.d("FloatingLyrics", "Close button clicked")
            removeFloatingWindow()
            stopForeground(true)
            // 发送广播通知播放页悬浮窗已关闭
            sendFloatingLyricsClosedBroadcast()
            stopSelf()
        }

        btnSettings?.setOnClickListener {
            android.util.Log.d("FloatingLyrics", "Settings button clicked")
            resetHideControlsTimer()
            toggleQuickSettings()
        }

        // 为控制容器设置触摸监听，用户交互时重置计时器
        controlContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> {
                    resetHideControlsTimer()
                }
            }
            false
        }

        // 设置播放控制按钮点击事件
        btnPrevious?.setOnClickListener {
            android.util.Log.d("FloatingLyrics", "Previous button clicked")
            resetHideControlsTimer()
            sendPlaybackControlBroadcast(ACTION_PREVIOUS)
        }

        btnPlayPause?.setOnClickListener {
            android.util.Log.d("FloatingLyrics", "Play/Pause button clicked")
            resetHideControlsTimer()
            // 立即切换本地播放状态，提供即时视觉反馈
            isPlaying = !isPlaying
            updatePlayPauseButtonIcon()
            sendPlaybackControlBroadcast(ACTION_PLAY_PAUSE)
        }

        btnNext?.setOnClickListener {
            android.util.Log.d("FloatingLyrics", "Next button clicked")
            resetHideControlsTimer()
            sendPlaybackControlBroadcast(ACTION_NEXT)
        }

        // 为播放控制容器设置触摸监听，用户交互时重置计时器
        playbackControlContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> {
                    resetHideControlsTimer()
                }
            }
            false
        }

        // 设置触摸事件 - 全区域长按拖动，点击显示/隐藏控件
        setupTouchEvents()

        // 更新锁定状态UI
        updateLockState()

        // 计算默认宽度（以竖屏宽度为基准的 1/2）
        // 使用 applicationContext.resources 避免 Service Context 问题
        val displayMetrics = applicationContext.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        // 使用宽高中的较小值作为竖屏宽度基准
        val portraitWidth = minOf(screenWidth, screenHeight)
        val defaultWidth = (portraitWidth / 2).toInt()
        
        val params = WindowManager.LayoutParams(
            defaultWidth, // 设置默认宽度为竖屏宽度的 1/2
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            val (savedX, savedY) = getPosition(this@FloatingLyricsService)
            if (savedX == -1) {
                // 默认水平居中
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
            } else {
                // 使用保存的位置
                gravity = Gravity.TOP or Gravity.START
                x = savedX
            }
            y = savedY
        }

        layoutParams = params

        try {
            windowManager.addView(floatingView, params)
            setRunning(this, true)
            android.util.Log.d("FloatingLyrics", "悬浮窗创建成功")
        } catch (e: Exception) {
            android.util.Log.e("FloatingLyrics", "添加悬浮窗失败: ${e.message}")
            e.printStackTrace()
            // 清理资源
            floatingView = null
        }
    }

    private fun setupQuickSettings() {
        // 初始化字体大小控件
        updateQuickSettingsUI()

        // 减小字体按钮
        btnDecreaseSize?.setOnClickListener {
            resetHideControlsTimer()
            val newSize = (currentLyricSize - SIZE_STEP).coerceIn(MIN_SIZE, MAX_SIZE)
            if (newSize != currentLyricSize) {
                updateLyricSizeAndSave(newSize)
            }
        }

        // 增大字体按钮
        btnIncreaseSize?.setOnClickListener {
            resetHideControlsTimer()
            val newSize = (currentLyricSize + SIZE_STEP).coerceIn(MIN_SIZE, MAX_SIZE)
            if (newSize != currentLyricSize) {
                updateLyricSizeAndSave(newSize)
            }
        }

        // 滑动条
        seekBarSize?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val sizePercent = MIN_SIZE + progress
                    tvSizeValue?.text = "$sizePercent%"
                    updateLyricSize(sizePercent)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // 开始拖动时重置计时器
                resetHideControlsTimer()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val sizePercent = MIN_SIZE + (seekBar?.progress ?: 0)
                updateLyricSizeAndSave(sizePercent)
                // 操作完成后重置计时器
                resetHideControlsTimer()
            }
        })

        // 初始化颜色选择器
        setupColorPicker()

        // 恢复默认按钮
        btnResetDefault?.setOnClickListener {
            resetHideControlsTimer()
            resetToDefault()
        }

        // 为快速设置容器设置触摸监听，用户交互时重置计时器
        quickSettingsContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> {
                    resetHideControlsTimer()
                }
            }
            false
        }
    }

    private fun setupColorPicker() {
        colorPickerContainer?.removeAllViews()

        // 使用 applicationContext.resources 避免 Service Context 问题
        val density = applicationContext.resources.displayMetrics.density
        val circleSize = (24 * density).toInt()
        val margin = (3 * density).toInt()

        LyricSettingsActivity.PRESET_LYRIC_COLORS.forEach { color ->
            val colorView = FrameLayout(this).apply {
                // 使用 GridLayout.LayoutParams
                layoutParams = GridLayout.LayoutParams().apply {
                    width = circleSize + margin * 2
                    height = circleSize + margin * 2
                    setMargins(margin, margin, margin, margin)
                }

                // 颜色圆点
                val circle = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(circleSize, circleSize).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(color)
                    })
                }

                // 选中标记
                val checkMark = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(circleSize / 2, circleSize / 2).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(android.graphics.Color.WHITE)
                    visibility = if (color == currentLyricColor) View.VISIBLE else View.GONE
                    tag = "check_$color"
                }

                addView(circle)
                addView(checkMark)

                setOnClickListener {
                    resetHideControlsTimer()
                    updateLyricColorAndSave(color)
                }
            }
            colorPickerContainer?.addView(colorView)
        }
    }

    private fun updateColorPickerSelection() {
        colorPickerContainer?.let { container ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i) as? FrameLayout
                val checkMark = child?.findViewWithTag<ImageView>("check_${LyricSettingsActivity.PRESET_LYRIC_COLORS.getOrNull(i)}")
                checkMark?.visibility = if (LyricSettingsActivity.PRESET_LYRIC_COLORS.getOrNull(i) == currentLyricColor)
                    View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateLyricSizeAndSave(sizePercent: Int) {
        currentLyricSize = sizePercent
        updateLyricSize(sizePercent)
        LyricSettingsActivity.setFloatingLyricSize(this, sizePercent)
        updateQuickSettingsUI()
        // 发送广播通知其他组件
        val intent = Intent(ACTION_UPDATE_SIZE).apply {
            putExtra(EXTRA_LYRIC_SIZE, sizePercent)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateLyricColorAndSave(color: Int) {
        currentLyricColor = color
        updateLyricColor(color)
        LyricSettingsActivity.setFloatingLyricColor(this, color)
        updateColorPickerSelection()
        // 发送广播通知其他组件
        val intent = Intent(ACTION_UPDATE_COLOR).apply {
            putExtra(EXTRA_LYRIC_COLOR, color)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun resetToDefault() {
        // 重置字体大小
        currentLyricSize = LyricSettingsActivity.DEFAULT_LYRIC_SIZE
        updateLyricSize(currentLyricSize)
        LyricSettingsActivity.setFloatingLyricSize(this, currentLyricSize)

        // 重置颜色
        currentLyricColor = LyricSettingsActivity.DEFAULT_LYRIC_COLOR
        updateLyricColor(currentLyricColor)
        LyricSettingsActivity.setFloatingLyricColor(this, currentLyricColor)

        // 更新UI
        updateQuickSettingsUI()
        updateColorPickerSelection()

        // 发送广播
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_UPDATE_SIZE).putExtra(EXTRA_LYRIC_SIZE, currentLyricSize)
        )
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_UPDATE_COLOR).putExtra(EXTRA_LYRIC_COLOR, currentLyricColor)
        )
    }

    private fun updateQuickSettingsUI() {
        tvSizeValue?.text = "$currentLyricSize%"
        seekBarSize?.progress = currentLyricSize - MIN_SIZE
    }

    private fun toggleQuickSettings() {
        if (isQuickSettingsVisible) {
            hideQuickSettings()
        } else {
            showQuickSettings()
        }
    }

    private fun showQuickSettings() {
        // 先隐藏播放控制容器
        playbackControlContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(150)
            ?.withEndAction {
                playbackControlContainer?.visibility = View.GONE
                playbackControlContainer?.alpha = 1f

                // 显示快速设置容器
                quickSettingsContainer?.alpha = 0f
                quickSettingsContainer?.visibility = View.VISIBLE
                quickSettingsContainer?.animate()
                    ?.alpha(1f)
                    ?.setDuration(200)
                    ?.start()
            }
            ?.start()

        isQuickSettingsVisible = true
        isControlsVisible = true

        // 更新UI
        updateQuickSettingsUI()
        updateColorPickerSelection()

        // 启动自动隐藏任务
        startHideControlsTimer()
    }

    private fun hideQuickSettings() {
        quickSettingsContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(150)
            ?.withEndAction {
                quickSettingsContainer?.visibility = View.GONE
                quickSettingsContainer?.alpha = 1f
                isQuickSettingsVisible = false

                // 显示播放控制容器
                playbackControlContainer?.alpha = 0f
                playbackControlContainer?.visibility = View.VISIBLE
                playbackControlContainer?.animate()
                    ?.alpha(1f)
                    ?.setDuration(200)
                    ?.start()
            }
            ?.start()
    }

    /**
     * 重置隐藏计时器 - 在用户交互时调用
     */
    private fun resetHideControlsTimer() {
        if (isControlsVisible) {
            startHideControlsTimer()
        }
    }

    /**
     * 启动隐藏控件计时器
     */
    private fun startHideControlsTimer() {
        // 取消之前的隐藏任务
        hideControlsRunnable?.let { hideControlsHandler.removeCallbacks(it) }

        // 启动新的自动隐藏任务
        hideControlsRunnable = Runnable {
            animateHideControls()
        }
        hideControlsHandler.postDelayed(hideControlsRunnable!!, CONTROLS_SHOW_DURATION)
    }

    private fun setupTouchEvents() {
        // 禁用 HorizontalScrollView 的手动滑动，只允许自动滚动
        disableScrollViewTouch(scrollCurrentLine)
        disableScrollViewTouch(scrollNextLine)

        // 根容器触摸事件 - 用于检测是否点击在歌词区域外
        rootContainer?.setOnTouchListener { _, event ->
            when (event?.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 检查点击位置是否在歌词容器内
                    val location = IntArray(2)
                    lyricsContainer?.getLocationOnScreen(location)
                    val lyricsLeft = location[0]
                    val lyricsTop = location[1]
                    val lyricsRight = lyricsLeft + (lyricsContainer?.width ?: 0)
                    val lyricsBottom = lyricsTop + (lyricsContainer?.height ?: 0)

                    val isInsideLyrics = event.rawX >= lyricsLeft && event.rawX <= lyricsRight &&
                            event.rawY >= lyricsTop && event.rawY <= lyricsBottom

                    if (!isInsideLyrics && isControlsVisible) {
                        // 点击在歌词区域外，隐藏控件
                        hideControls()
                        // 消费此事件，防止传递给其他视图
                        return@setOnTouchListener true
                    }
                }
            }
            // 返回 false 表示不消费事件，允许事件传递给子视图
            false
        }

        // 歌词容器触摸事件 - 长按拖动/显示控件，点击显示/隐藏控件
        lyricsContainer?.setOnTouchListener(object : View.OnTouchListener {
            // 用于记录拖动时的实际屏幕位置
            private var startRawX = 0f
            private var startRawY = 0f
            private var startWindowX = 0
            private var startWindowY = 0
            private var hasMoved = false
            private var isDragging = false
            
            /**
             * 检查触摸点是否在控件区域（按钮、快速设置等）
             * 如果在控件区域，返回 true 表示不处理长按拖动
             */
            private fun isTouchInControlsArea(event: MotionEvent): Boolean {
                // 检查是否在控制按钮区域
                controlContainer?.let { container ->
                    if (container.visibility == View.VISIBLE) {
                        val location = IntArray(2)
                        container.getLocationOnScreen(location)
                        val left = location[0]
                        val top = location[1]
                        val right = left + container.width
                        val bottom = top + container.height
                        if (event.rawX >= left && event.rawX <= right &&
                            event.rawY >= top && event.rawY <= bottom) {
                            return true
                        }
                    }
                }
                
                // 检查是否在播放控制按钮区域
                playbackControlContainer?.let { container ->
                    if (container.visibility == View.VISIBLE) {
                        val location = IntArray(2)
                        container.getLocationOnScreen(location)
                        val left = location[0]
                        val top = location[1]
                        val right = left + container.width
                        val bottom = top + container.height
                        if (event.rawX >= left && event.rawX <= right &&
                            event.rawY >= top && event.rawY <= bottom) {
                            return true
                        }
                    }
                }
                
                // 检查是否在快速设置区域
                quickSettingsContainer?.let { container ->
                    if (container.visibility == View.VISIBLE) {
                        val location = IntArray(2)
                        container.getLocationOnScreen(location)
                        val left = location[0]
                        val top = location[1]
                        val right = left + container.width
                        val bottom = top + container.height
                        if (event.rawX >= left && event.rawX <= right &&
                            event.rawY >= top && event.rawY <= bottom) {
                            return true
                        }
                    }
                }
                
                return false
            }
            
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 如果触摸在控件区域，不处理长按拖动，让事件传递给子视图
                        if (isTouchInControlsArea(event!!)) {
                            return false
                        }
                        
                        // 获取当前窗口在屏幕上的实际位置
                        val location = IntArray(2)
                        floatingView?.getLocationOnScreen(location)
                        startWindowX = location[0]
                        startWindowY = location[1]
                        
                        startRawX = event.rawX
                        startRawY = event.rawY
                        isLongPress = false
                        hasMoved = false
                        isDragging = false

                        // 启动长按检测
                        longPressRunnable = Runnable {
                            isLongPress = true
                            if (isLocked) {
                                // 锁定状态下长按显示控件（带动画）
                                if (!isControlsVisible) {
                                    animateShowControls()
                                }
                            } else {
                                // 解锁状态下长按显示视觉反馈（准备拖动）
                                lyricsContainer?.animate()
                                    ?.alpha(0.7f)
                                    ?.setDuration(150)
                                    ?.start()
                            }
                        }
                        longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 如果触摸在控件区域，不处理拖动
                        if (isTouchInControlsArea(event!!)) {
                            // 取消长按检测
                            longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                            isLongPress = false
                            return false
                        }
                        
                        // 计算移动距离
                        val deltaX = kotlin.math.abs(event.rawX - startRawX)
                        val deltaY = kotlin.math.abs(event.rawY - startRawY)
                        
                        // 如果移动距离超过阈值，标记为已移动
                        if (deltaX > MOVE_THRESHOLD || deltaY > MOVE_THRESHOLD) {
                            hasMoved = true
                        }
                        
                        // 如果移动距离超过拖动阈值且未锁定，立即开始拖动（无需等待长按）
                        if ((deltaX > DRAG_THRESHOLD || deltaY > DRAG_THRESHOLD) && !isLocked && !isDragging) {
                            isDragging = true
                            // 取消长按检测
                            longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                            // 显示视觉反馈
                            lyricsContainer?.animate()
                                ?.alpha(0.7f)
                                ?.setDuration(150)
                                ?.start()
                        }

                        // 在解锁状态下，如果正在拖动或长按已触发，允许拖动
                        if (!isLocked && (isDragging || isLongPress)) {
                            // 获取屏幕尺寸
                            val screenWidth = applicationContext.resources.displayMetrics.widthPixels
                            val screenHeight = applicationContext.resources.displayMetrics.heightPixels
                            val windowWidth = floatingView?.width ?: 0
                            val windowHeight = floatingView?.height ?: 0
                            
                            // 计算新的位置：基于起始窗口位置加上总的触摸偏移量
                            val totalDeltaX = event.rawX - startRawX
                            val totalDeltaY = event.rawY - startRawY
                            
                            // 计算新的位置，确保窗口至少有部分可见（允许拖动到屏幕边缘，甚至部分超出屏幕）
                            // 允许窗口的 80% 可以超出屏幕边界，这样用户可以将悬浮窗拖到屏幕边缘
                            val minVisibleWidth = (windowWidth * 0.2).toInt().coerceAtLeast(50) // 至少保留50像素或20%宽度可见
                            val minVisibleHeight = (windowHeight * 0.2).toInt().coerceAtLeast(50) // 至少保留50像素或20%高度可见
                            
                            // X坐标范围：允许窗口右侧边缘至少露出 minVisibleWidth，或者左侧边缘在屏幕内
                            val minX = -(windowWidth - minVisibleWidth)
                            val maxX = screenWidth - minVisibleWidth
                            
                            // Y坐标范围：允许窗口底部边缘至少露出 minVisibleHeight，或者顶部边缘在屏幕内
                            val minY = -(windowHeight - minVisibleHeight)
                            val maxY = screenHeight - minVisibleHeight
                            
                            val newX = (startWindowX + totalDeltaX.toInt()).coerceIn(minX, maxX)
                            val newY = (startWindowY + totalDeltaY.toInt()).coerceIn(minY, maxY)
                            
                            // 切换到 START 模式并更新位置
                            layoutParams?.gravity = Gravity.TOP or Gravity.START
                            layoutParams?.x = newX
                            layoutParams?.y = newY
                            
                            try {
                                layoutParams?.let { windowManager.updateViewLayout(floatingView, it) }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // 如果触摸在控件区域，不处理
                        if (isTouchInControlsArea(event!!)) {
                            return false
                        }
                        
                        // 取消长按检测
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                        // 恢复视觉反馈（带动画）
                        lyricsContainer?.animate()
                            ?.alpha(1.0f)
                            ?.setDuration(150)
                            ?.start()

                        if (isLongPress || isDragging) {
                            // 长按或拖动结束
                            isLongPress = false
                            isDragging = false
                            if (!isLocked) {
                                // 解锁状态下保存位置
                                layoutParams?.let {
                                    savePosition(this@FloatingLyricsService, it.x, it.y)
                                }
                            }
                            // 锁定状态下长按已触发显示控件，不执行其他操作
                        } else if (!hasMoved) {
                            // 普通点击（没有移动过）
                            if (!isLocked) {
                                // 解锁状态下点击切换控件显示/隐藏
                                toggleControls()
                            }
                            // 锁定状态下点击不执行任何操作
                        }
                        // 重置状态
                        hasMoved = false
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // 取消长按检测
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        // 恢复视觉反馈（带动画）
                        lyricsContainer?.animate()
                            ?.alpha(1.0f)
                            ?.setDuration(150)
                            ?.start()
                        // 重置所有状态
                        isLongPress = false
                        isDragging = false
                        hasMoved = false
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * 禁用 ScrollView 的手动触摸滑动，只允许通过代码滚动
     */
    private fun disableScrollViewTouch(scrollView: HorizontalScrollView?) {
        scrollView?.setOnTouchListener { _, event ->
            // 消费所有触摸事件，阻止手动滑动
            // 但允许点击事件传递给子视图（TextView）
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 记录按下位置，用于判断是否是点击
                    scrollView.setTag(R.id.tag_scroll_start_pos, Pair(event.rawX, event.rawY))
                    false // 允许传递给子视图
                }
                MotionEvent.ACTION_MOVE -> {
                    // 阻止滑动，但检查是否是有效的点击
                    val tag = scrollView.getTag(R.id.tag_scroll_start_pos) as? Pair<*, *>
                    if (tag != null) {
                        val startX = tag.first as? Float ?: 0f
                        val startY = tag.second as? Float ?: 0f
                        val deltaX = kotlin.math.abs(event.rawX - startX)
                        val deltaY = kotlin.math.abs(event.rawY - startY)
                        // 如果移动超过阈值，则认为是滑动，阻止事件传递
                        if (deltaX > MOVE_THRESHOLD || deltaY > MOVE_THRESHOLD) {
                            true // 消费事件，阻止滑动
                        } else {
                            false // 允许传递给子视图
                        }
                    } else {
                        true // 消费事件
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scrollView.setTag(R.id.tag_scroll_start_pos, null)
                    false // 允许传递给子视图
                }
                else -> true // 消费其他所有事件
            }
        }
    }

    private fun toggleControls() {
        android.util.Log.d("FloatingLyrics", "toggleControls called, isControlsVisible=$isControlsVisible, isLocked=$isLocked")
        if (isControlsVisible) {
            animateHideControls()
        } else {
            animateShowControls()
        }
    }

    /**
     * 动画显示控件
     */
    private fun animateShowControls() {
        android.util.Log.d("FloatingLyrics", "animateShowControls called")
        // 确保快速设置面板是隐藏的
        quickSettingsContainer?.visibility = View.GONE
        isQuickSettingsVisible = false

        // 显示控制容器（带动画）
        controlContainer?.alpha = 0f
        controlContainer?.visibility = View.VISIBLE
        controlContainer?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.start()

        // 显示播放控制容器（带动画）
        playbackControlContainer?.alpha = 0f
        playbackControlContainer?.visibility = View.VISIBLE
        playbackControlContainer?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setStartDelay(50)
            ?.start()

        isControlsVisible = true
        android.util.Log.d("FloatingLyrics", "Controls shown, isControlsVisible set to true")

        // 更新播放按钮图标
        updatePlayPauseButtonIcon()

        // 启动自动隐藏任务
        startHideControlsTimer()
    }

    /**
     * 动画隐藏控件
     */
    private fun animateHideControls() {
        if (!isControlsVisible) return

        // 隐藏快速设置容器
        quickSettingsContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(150)
            ?.withEndAction {
                quickSettingsContainer?.visibility = View.GONE
                quickSettingsContainer?.alpha = 1f
            }
            ?.start()

        // 隐藏播放控制容器
        playbackControlContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(150)
            ?.withEndAction {
                playbackControlContainer?.visibility = View.GONE
                playbackControlContainer?.alpha = 1f
            }
            ?.start()

        // 隐藏控制容器
        controlContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(150)
            ?.setStartDelay(50)
            ?.withEndAction {
                controlContainer?.visibility = View.GONE
                controlContainer?.alpha = 1f
                isControlsVisible = false
                isQuickSettingsVisible = false
            }
            ?.start()

        // 取消隐藏任务
        hideControlsRunnable?.let { hideControlsHandler.removeCallbacks(it) }
    }

    /**
     * 立即隐藏控件（无动画）- 用于触摸外部时
     */
    private fun hideControls() {
        controlContainer?.visibility = View.GONE
        playbackControlContainer?.visibility = View.GONE
        quickSettingsContainer?.visibility = View.GONE
        isControlsVisible = false
        isQuickSettingsVisible = false
        hideControlsRunnable?.let { hideControlsHandler.removeCallbacks(it) }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        android.util.Log.d("FloatingLyrics", "toggleLock called, new state: isLocked=$isLocked")
        setIsLocked(this, isLocked)
        updateLockState()
    }

    private fun updateLockState() {
        btnLock?.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_lock_open)

        // 更新触摸标志 - 始终保持 NOT_TOUCH_MODAL 以允许按钮点击
        layoutParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        try {
            layoutParams?.let { windowManager.updateViewLayout(floatingView, it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateLyricColor(color: Int) {
        currentLyricColor = color
        tvCurrentLine?.setTextColor(color)
        // 下一行使用白色半透明
        tvNextLine?.setTextColor(0x80FFFFFF.toInt())
    }

    private fun updateLyricSize(sizePercent: Int) {
        val scale = sizePercent / 100f
        val currentTextSize = 16f * scale
        val nextTextSize = 13f * scale

        tvCurrentLine?.textSize = currentTextSize
        tvNextLine?.textSize = nextTextSize
        
        // 字体大小改变后，重新启动滚动动画
        scrollCurrentLine?.scrollTo(0, 0)
        scrollNextLine?.scrollTo(0, 0)
        currentLineAnimator?.cancel()
        nextLineAnimator?.cancel()
        startLyricScrollAnimation()
    }

    private fun updateLyricsFromIntent(intent: Intent) {
        val lyrics = intent.getStringExtra(EXTRA_LYRICS)
        currentSongName = intent.getStringExtra(EXTRA_SONG_NAME) ?: ""
        currentArtists = intent.getStringExtra(EXTRA_ARTISTS) ?: ""

        // 更新颜色
        val newColor = intent.getIntExtra(EXTRA_LYRIC_COLOR, currentLyricColor)
        if (newColor != currentLyricColor) {
            currentLyricColor = newColor
            updateLyricColor(newColor)
            updateColorPickerSelection()
        }

        // 更新大小
        val newSize = intent.getIntExtra(EXTRA_LYRIC_SIZE, 0)
        if (newSize > 0) {
            currentLyricSize = newSize
            updateLyricSize(newSize)
            updateQuickSettingsUI()
        }

        // 解析歌词
        lyrics?.let {
            parsedLyrics = parseLyrics(it)
            updateLyricsDisplay()
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

    private fun updatePosition(position: Long) {
        currentPosition = position
        updateLyricsDisplay()
    }

    private fun updateLyricsDisplay() {
        if (parsedLyrics.isEmpty()) {
            tvCurrentLine?.text = currentSongName
            tvNextLine?.text = currentArtists
            return
        }

        // 找到当前歌词位置
        var newIndex = -1
        for (i in parsedLyrics.indices) {
            if (parsedLyrics[i].first <= currentPosition) {
                newIndex = i
            } else {
                break
            }
        }

        // 检查歌词是否发生变化
        val isLyricChanged = newIndex != currentLyricIndex
        currentLyricIndex = newIndex

        if (currentLyricIndex >= 0) {
            val currentText = parsedLyrics[currentLyricIndex].second
            val nextText = if (currentLyricIndex + 1 < parsedLyrics.size) {
                parsedLyrics[currentLyricIndex + 1].second
            } else ""

            // 如果歌词发生变化，更新文本并启动滚动动画
            if (isLyricChanged) {
                tvCurrentLine?.text = currentText
                tvNextLine?.text = nextText
                
                // 重置滚动位置
                scrollCurrentLine?.scrollTo(0, 0)
                scrollNextLine?.scrollTo(0, 0)
                
                // 取消之前的动画
                currentLineAnimator?.cancel()
                nextLineAnimator?.cancel()
                
                // 启动新的滚动动画
                startLyricScrollAnimation()
            }
        } else {
            val currentText = parsedLyrics.firstOrNull()?.second ?: currentSongName
            val nextText = if (parsedLyrics.size > 1) parsedLyrics[1].second else currentArtists
            
            if (isLyricChanged) {
                tvCurrentLine?.text = currentText
                tvNextLine?.text = nextText
                
                scrollCurrentLine?.scrollTo(0, 0)
                scrollNextLine?.scrollTo(0, 0)
                
                currentLineAnimator?.cancel()
                nextLineAnimator?.cancel()
                
                startLyricScrollAnimation()
            }
        }
    }
    
    /**
     * 启动歌词滚动动画
     * 当歌词长度超过显示区域时，自动滚动显示
     */
    private fun startLyricScrollAnimation() {
        // 延迟后开始滚动
        scrollCurrentLine?.postDelayed({
            startScrollAnimationForView(scrollCurrentLine, tvCurrentLine)
        }, SCROLL_DELAY)
        
        scrollNextLine?.postDelayed({
            startScrollAnimationForView(scrollNextLine, tvNextLine)
        }, SCROLL_DELAY)
    }
    
    /**
     * 为指定的ScrollView和TextView启动滚动动画
     */
    private fun startScrollAnimationForView(scrollView: HorizontalScrollView?, textView: TextView?) {
        if (scrollView == null || textView == null) return
        
        val textWidth = textView.width
        val scrollWidth = scrollView.width
        
        // 只有当文本宽度超过滚动视图宽度时才需要滚动
        if (textWidth > scrollWidth) {
            val scrollDistance = textWidth - scrollWidth
            val density = applicationContext.resources.displayMetrics.density
            val scrollSpeedPxPerSec = SCROLL_SPEED_DP_PER_SEC * density
            val duration = (scrollDistance / scrollSpeedPxPerSec * 1000).toLong().coerceAtLeast(2000)
            
            // 创建滚动动画
            val animator = android.animation.ValueAnimator.ofInt(0, scrollDistance)
            animator.duration = duration
            animator.interpolator = android.view.animation.LinearInterpolator()
            animator.addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                scrollView.scrollTo(value, 0)
            }
            
            // 保存动画引用
            if (scrollView == scrollCurrentLine) {
                currentLineAnimator = animator
            } else {
                nextLineAnimator = animator
            }
            
            animator.start()
        }
    }
    
    private fun removeFloatingWindow() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        floatingView = null
        setRunning(this, false)
    }

    /**
     * 发送悬浮窗已关闭的广播通知
     */
    private fun sendFloatingLyricsClosedBroadcast() {
        val intent = Intent(ACTION_FLOATING_LYRICS_CLOSED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * 发送播放控制广播到MusicPlaybackService
     */
    private fun sendPlaybackControlBroadcast(action: String) {
        val intent = Intent(action)
        // 使用LocalBroadcastManager发送广播，确保应用内通信
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * 更新播放/暂停按钮图标
     */
    private fun updatePlayPauseButtonIcon() {
        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // 取消滚动动画
        currentLineAnimator?.cancel()
        nextLineAnimator?.cancel()
        removeFloatingWindow()
    }
}
