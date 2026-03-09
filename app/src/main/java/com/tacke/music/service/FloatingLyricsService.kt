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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tacke.music.R
import com.tacke.music.ui.PlayerActivity
import java.util.concurrent.TimeUnit

class FloatingLyricsService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var lyricsContainer: LinearLayout? = null
    private var tvCurrentLine: TextView? = null
    private var tvNextLine: TextView? = null
    private var btnLock: ImageView? = null
    private var btnClose: ImageView? = null
    private var dragHandle: View? = null

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
    private val LONG_PRESS_DURATION = 300L // 长按触发时间（毫秒）

    private var parsedLyrics: List<Pair<Long, String>> = emptyList()
    private var currentPosition: Long = 0
    private var isPlaying = false
    private var currentLyricColor: Int = 0xFF00CED1.toInt()
    private var currentSongName: String = ""
    private var currentArtists: String = ""

    companion object {
        const val ACTION_SHOW_FLOATING_LYRICS = "com.tacke.music.ACTION_SHOW_FLOATING_LYRICS"
        const val ACTION_HIDE_FLOATING_LYRICS = "com.tacke.music.ACTION_HIDE_FLOATING_LYRICS"
        const val ACTION_UPDATE_LYRICS = "com.tacke.music.ACTION_UPDATE_LYRICS"
        const val ACTION_UPDATE_POSITION = "com.tacke.music.ACTION_UPDATE_POSITION"
        const val ACTION_PLAYBACK_STATE_CHANGED = "com.tacke.music.ACTION_PLAYBACK_STATE_CHANGED"
        const val ACTION_TOGGLE_LOCK = "com.tacke.music.ACTION_TOGGLE_LOCK"
        const val ACTION_UPDATE_SIZE = "com.tacke.music.ACTION_UPDATE_SIZE"

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
        currentLyricColor = com.tacke.music.ui.LyricSettingsActivity.getFloatingLyricColor(this)
        registerReceivers()
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
                updateLyricSize(sizePercent)
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
                }
                ACTION_TOGGLE_LOCK -> toggleLock()
                ACTION_UPDATE_SIZE -> {
                    val sizePercent = intent.getIntExtra(EXTRA_LYRIC_SIZE, 100)
                    updateLyricSize(sizePercent)
                }
            }
        }
    }

    private fun createFloatingWindow() {
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
        floatingView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_lyrics, null)

        lyricsContainer = floatingView?.findViewById(R.id.lyricsContainer)
        tvCurrentLine = floatingView?.findViewById(R.id.tvCurrentLine)
        tvNextLine = floatingView?.findViewById(R.id.tvNextLine)
        btnLock = floatingView?.findViewById(R.id.btnLock)
        btnClose = floatingView?.findViewById(R.id.btnClose)
        dragHandle = floatingView?.findViewById(R.id.dragHandle)

        // 设置歌词颜色
        updateLyricColor(currentLyricColor)

        // 应用歌词大小设置
        val lyricSize = com.tacke.music.ui.LyricSettingsActivity.getFloatingLyricSize(this)
        updateLyricSize(lyricSize)

        // 设置按钮点击事件
        btnLock?.setOnClickListener {
            android.util.Log.d("FloatingLyrics", "Lock button clicked, current state: isLocked=$isLocked")
            toggleLock()
        }

        btnClose?.setOnClickListener {
            android.util.Log.d("FloatingLyrics", "Close button clicked")
            removeFloatingWindow()
            stopForeground(true)
            stopSelf()
        }

        // 设置拖动和点击事件 - 改为长按拖动
        setupDragAndClick()

        // 更新锁定状态UI
        updateLockState()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
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
            gravity = Gravity.TOP or Gravity.START
            val (savedX, savedY) = getPosition(this@FloatingLyricsService)
            x = if (savedX == -1) {
                // 默认居中
                val displayMetrics = resources.displayMetrics
                (displayMetrics.widthPixels - 600) / 2
            } else savedX
            y = savedY
        }

        layoutParams = params

        try {
            windowManager.addView(floatingView, params)
            setRunning(this, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupDragAndClick() {
        // 长按拖动处理 - 只在解锁状态下允许拖动
        lyricsContainer?.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (isLocked) {
                    // 锁定状态下只处理点击事件（打开播放页）
                    when (event?.action) {
                        MotionEvent.ACTION_UP -> {
                            // 点击歌词区域打开播放页
                            val intent = Intent(this@FloatingLyricsService, PlayerActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_RESTORE)
                            }
                            startActivity(intent)
                        }
                    }
                    return false
                }

                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams?.x ?: 0
                        initialY = layoutParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isLongPress = false

                        // 启动长按检测
                        longPressRunnable = Runnable {
                            isLongPress = true
                            // 显示视觉反馈
                            lyricsContainer?.alpha = 0.7f
                        }
                        longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_DURATION)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 如果移动距离超过阈值，取消长按
                        val deltaX = kotlin.math.abs(event.rawX - initialTouchX)
                        val deltaY = kotlin.math.abs(event.rawY - initialTouchY)
                        if ((deltaX > 10 || deltaY > 10) && !isLongPress) {
                            longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        }

                        // 只有在长按触发后才允许拖动
                        if (isLongPress) {
                            val moveDeltaX = (event.rawX - initialTouchX).toInt()
                            val moveDeltaY = (event.rawY - initialTouchY).toInt()
                            layoutParams?.x = initialX + moveDeltaX
                            layoutParams?.y = initialY + moveDeltaY
                            try {
                                layoutParams?.let { windowManager.updateViewLayout(floatingView, it) }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // 取消长按检测
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                        // 恢复视觉反馈
                        lyricsContainer?.alpha = 1.0f

                        if (isLongPress) {
                            // 长按结束，保存位置
                            isLongPress = false
                            layoutParams?.let {
                                savePosition(this@FloatingLyricsService, it.x, it.y)
                            }
                        } else {
                            // 普通点击，打开播放页
                            val intent = Intent(this@FloatingLyricsService, PlayerActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_RESTORE)
                            }
                            startActivity(intent)
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // 取消长按检测
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        lyricsContainer?.alpha = 1.0f
                        isLongPress = false
                        return true
                    }
                }
                return false
            }
        })

        // 点击歌词区域打开播放页（作为备用）
        lyricsContainer?.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_RESTORE)
            }
            startActivity(intent)
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        android.util.Log.d("FloatingLyrics", "toggleLock called, new state: isLocked=$isLocked")
        setIsLocked(this, isLocked)
        updateLockState()
    }

    private fun updateLockState() {
        dragHandle?.visibility = if (isLocked) View.GONE else View.VISIBLE
        btnLock?.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_lock_open)

        // 更新触摸标志 - 始终保持 NOT_TOUCH_MODAL 以允许按钮点击
        // 锁定状态下只隐藏拖动把手，不禁用触摸
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
    }

    private fun updateLyricsFromIntent(intent: Intent) {
        val lyrics = intent.getStringExtra(EXTRA_LYRICS)
        currentSongName = intent.getStringExtra(EXTRA_SONG_NAME) ?: ""
        currentArtists = intent.getStringExtra(EXTRA_ARTISTS) ?: ""

        // 更新颜色
        val newColor = intent.getIntExtra(EXTRA_LYRIC_COLOR, currentLyricColor)
        if (newColor != currentLyricColor) {
            updateLyricColor(newColor)
        }

        // 更新大小
        val newSize = intent.getIntExtra(EXTRA_LYRIC_SIZE, 0)
        if (newSize > 0) {
            updateLyricSize(newSize)
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
        var currentIndex = -1
        for (i in parsedLyrics.indices) {
            if (parsedLyrics[i].first <= currentPosition) {
                currentIndex = i
            } else {
                break
            }
        }

        if (currentIndex >= 0) {
            tvCurrentLine?.text = parsedLyrics[currentIndex].second
            // 下一行
            if (currentIndex + 1 < parsedLyrics.size) {
                tvNextLine?.text = parsedLyrics[currentIndex + 1].second
            } else {
                tvNextLine?.text = ""
            }
        } else {
            tvCurrentLine?.text = parsedLyrics.firstOrNull()?.second ?: currentSongName
            if (parsedLyrics.size > 1) {
                tvNextLine?.text = parsedLyrics[1].second
            } else {
                tvNextLine?.text = currentArtists
            }
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        removeFloatingWindow()
    }
}
