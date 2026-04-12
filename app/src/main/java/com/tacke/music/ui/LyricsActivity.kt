package com.tacke.music.ui

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.tacke.music.R
import com.tacke.music.data.api.RetrofitClient
import com.tacke.music.data.model.Comment
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.ui.adapter.CommentAdapter
import com.tacke.music.ui.adapter.LyricsAdapter
import com.tacke.music.util.ImmersiveStatusBarHelper
import com.tacke.music.utils.LyricStyleSettings
import com.tacke.music.utils.SongCoverLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class LyricsActivity : AppCompatActivity() {

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

    // 歌曲信息（用于重新获取歌词）
    private var songName: String = ""
    private var songArtists: String = ""
    private var songId: String = ""
    private var platform: MusicRepository.Platform = MusicRepository.Platform.KUWO
    private var coverUrl: String? = null
    private var isLoadingLyrics = false
    private var pendingLyricJumpTime: Long? = null
    private val lyricStylePrefs by lazy { getSharedPreferences(LyricStyleSettings.PREFS_NAME, Context.MODE_PRIVATE) }
    private val lyricStyleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (
            key == LyricStyleSettings.KEY_FULLSCREEN_LYRIC_COLOR ||
            key == LyricStyleSettings.KEY_FULLSCREEN_LYRIC_SIZE
        ) {
            runOnUiThread {
                applyFullscreenLyricStyle()
            }
        }
    }

    // 评论相关
    private var commentAdapter: com.tacke.music.ui.adapter.CommentAdapter? = null
    private var currentCommentTab = 0 // 0: 热门, 1: 最新
    private var commentPage = 1
    private val commentPageSize = 20
    private var isLoadingComments = false
    private var hasMoreComments = true
    private var actualCommentSource: CommentSource? = null
    private var actualSongId: String = ""

    enum class CommentSource {
        KUWO, NETEASE
    }

    companion object {
        const val REQUEST_CODE = 1001
        const val ACTION_REQUEST_PLAYBACK_STATUS = "com.tacke.music.REQUEST_PLAYBACK_STATUS"
        private const val TAG = "LyricsActivity"

        fun startForResult(
            activity: AppCompatActivity,
            songName: String,
            songArtists: String,
            coverUrl: String?,
            lyrics: String?,
            currentPosition: Long,
            duration: Long,
            isPlaying: Boolean,
            songId: String = "",
            platform: MusicRepository.Platform = MusicRepository.Platform.KUWO
        ) {
            val intent = Intent(activity, LyricsActivity::class.java).apply {
                putExtra("song_name", songName)
                putExtra("song_artists", songArtists)
                putExtra("cover_url", coverUrl)
                putExtra("lyrics", lyrics)
                putExtra("current_position", currentPosition)
                putExtra("duration", duration)
                putExtra("is_playing", isPlaying)
                putExtra("song_id", songId)
                putExtra("platform", platform.name)
            }
            activity.startActivityForResult(intent, REQUEST_CODE)
        }
    }

    // Views
    private lateinit var ivBackground: ImageView
    private lateinit var btnBack: ImageButton
    private lateinit var tvSongName: TextView
    private lateinit var tvArtist: TextView
    private lateinit var rvLyrics: RecyclerView
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private var btnLocateCurrent: ImageButton? = null
    private var btnJumpToLyric: ImageButton? = null

    // 横屏评论相关 Views
    private var rvComments: RecyclerView? = null
    private var tvCommentCount: TextView? = null
    private var btnHotComments: TextView? = null
    private var btnLatestComments: TextView? = null
    private var swipeRefreshLayoutComments: SwipeRefreshLayout? = null
    private var progressBarComments: ProgressBar? = null
    private var tvEmptyComments: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics)

        // 设置沉浸式状态栏
        ImmersiveStatusBarHelper.setup(
            activity = this,
            lightStatusBar = false,
            lightNavigationBar = true
        )

        initViews()
        setupRecyclerView()
        setupSeekBar()
        setupClickListeners()
        setupGestureDetector()
        setupPlaybackReceiver()
        loadSongInfo()

        // 如果是横屏，初始化评论功能
        if (isLandscape()) {
            setupComments()
        }
    }

    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun initViews() {
        ivBackground = findViewById(R.id.ivBackground)
        btnBack = findViewById(R.id.btnBack)
        tvSongName = findViewById(R.id.tvSongName)
        tvArtist = findViewById(R.id.tvArtist)
        rvLyrics = findViewById(R.id.rvLyrics)
        seekBar = findViewById(R.id.seekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        btnLocateCurrent = findViewById(R.id.btnLocateCurrent)
        btnJumpToLyric = findViewById(R.id.btnJumpToLyric)

        // 横屏评论相关 Views
        rvComments = findViewById(R.id.rvComments)
        tvCommentCount = findViewById(R.id.tvCommentCount)
        btnHotComments = findViewById(R.id.btnHotComments)
        btnLatestComments = findViewById(R.id.btnLatestComments)
        swipeRefreshLayoutComments = findViewById(R.id.swipeRefreshLayoutComments)
        progressBarComments = findViewById(R.id.progressBarComments)
        tvEmptyComments = findViewById(R.id.tvEmptyComments)
    }

    private fun setupComments() {
        if (rvComments == null) return

        commentAdapter = CommentAdapter()
        rvComments?.layoutManager = LinearLayoutManager(this)
        rvComments?.adapter = commentAdapter

        // 评论标签切换
        btnHotComments?.setOnClickListener {
            switchCommentTab(0)
        }
        btnLatestComments?.setOnClickListener {
            switchCommentTab(1)
        }

        // 设置下拉刷新
        swipeRefreshLayoutComments?.apply {
            setColorSchemeResources(
                R.color.primary,
                R.color.accent_cyan,
                R.color.accent_purple
            )
            setOnRefreshListener {
                refreshComments()
            }
        }

        // 设置加载更多监听器
        rvComments?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0) { // 只向下滚动时触发
                    checkLoadMoreComments()
                }
            }
        })

        // 强制加载评论（重置所有状态）
        loadComments(forceReload = true)
    }

    /**
     * 检查是否需要加载更多评论
     */
    private fun checkLoadMoreComments() {
        val layoutManager = rvComments?.layoutManager as? LinearLayoutManager ?: return
        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        val totalItemCount = layoutManager.itemCount

        // 距离底部5个item时触发加载
        if (lastVisibleItem >= totalItemCount - 5) {
            loadMoreComments()
        }
    }

    /**
     * 加载更多评论
     */
    private fun loadMoreComments() {
        if (isLoadingComments || !hasMoreComments) return
        
        commentPage++
        loadComments()
    }

    private fun refreshComments() {
        lifecycleScope.launch {
            try {
                commentAdapter?.submitComments(emptyList<Comment>())
                loadComments(forceReload = true)
                Toast.makeText(this@LyricsActivity, "刷新成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@LyricsActivity, "刷新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                swipeRefreshLayoutComments?.isRefreshing = false
            }
        }
    }

    private fun switchCommentTab(tab: Int) {
        if (currentCommentTab == tab) return

        currentCommentTab = tab

        // 更新UI
        updateCommentTabUI()

        // 清空列表并强制重新加载
        commentAdapter?.submitComments(emptyList<Comment>())
        loadComments(forceReload = true)
    }

    private fun updateCommentTabUI() {
        if (currentCommentTab == 0) {
            btnHotComments?.setBackgroundResource(R.drawable.bg_tab_selected)
            btnHotComments?.alpha = 1.0f
            btnLatestComments?.setBackgroundResource(0)
            btnLatestComments?.alpha = 0.6f
        } else {
            btnHotComments?.setBackgroundResource(0)
            btnHotComments?.alpha = 0.6f
            btnLatestComments?.setBackgroundResource(R.drawable.bg_tab_selected)
            btnLatestComments?.alpha = 1.0f
        }
    }

    private fun loadComments(forceReload: Boolean = false) {
        // 如果不是强制重载，检查是否正在加载或没有更多数据
        if (!forceReload && (isLoadingComments || !hasMoreComments)) {
            Log.d(TAG, "跳过加载: isLoading=$isLoadingComments, hasMore=$hasMoreComments")
            return
        }
        
        // 强制重载时重置状态
        if (forceReload) {
            commentPage = 1
            hasMoreComments = true
            isLoadingComments = false
            actualCommentSource = null
            actualSongId = ""
        }
        
        isLoadingComments = true

        progressBarComments?.visibility = View.VISIBLE
        tvEmptyComments?.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val comments = if (songId.startsWith("local_")) {
                    loadLocalSongComments()
                } else {
                    when (platform) {
                        MusicRepository.Platform.KUWO -> loadKuwoComments()
                        MusicRepository.Platform.NETEASE -> loadNeteaseComments()
                        else -> emptyList()
                    }
                }

                withContext(Dispatchers.Main) {
                    isLoadingComments = false
                    progressBarComments?.visibility = View.GONE

                    if (comments.isEmpty() && commentPage == 1) {
                        tvEmptyComments?.visibility = View.VISIBLE
                    } else {
                        tvEmptyComments?.visibility = View.GONE
                        if (commentPage == 1) {
                            commentAdapter?.submitComments(comments)
                        } else {
                            val currentList = commentAdapter?.currentList ?: emptyList()
                            val currentComments = mutableListOf<Comment>()
                            for (item in currentList) {
                                if (item is CommentAdapter.ListItem.CommentItem) {
                                    currentComments.add(item.comment)
                                }
                            }
                            currentComments.addAll(comments)
                            commentAdapter?.submitComments(currentComments)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载评论失败", e)
                withContext(Dispatchers.Main) {
                    isLoadingComments = false
                    progressBarComments?.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun loadLocalSongComments(): List<Comment> = withContext(Dispatchers.IO) {
        val searchKeyword = "$songName $songArtists"
        Log.d(TAG, "本地歌曲搜索关键词: $searchKeyword")

        try {
            val kuwoSongId = async { searchKuwoSong(searchKeyword) }.await()
            val neteaseSongId = async { searchNeteaseSong(searchKeyword) }.await()

            Log.d(TAG, "酷我搜索结果: $kuwoSongId, 网易云搜索结果: $neteaseSongId")

            if (kuwoSongId == null && neteaseSongId == null) {
                return@withContext emptyList()
            }

            val kuwoTotal = kuwoSongId?.let { getKuwoCommentTotal(it) } ?: 0
            val neteaseTotal = neteaseSongId?.let { getNeteaseCommentTotal(it) } ?: 0

            Log.d(TAG, "酷我评论数: $kuwoTotal, 网易云评论数: $neteaseTotal")

            val (selectedSource, selectedSongId, total) = when {
                kuwoSongId != null && neteaseSongId == null -> Triple(CommentSource.KUWO, kuwoSongId, kuwoTotal)
                kuwoSongId == null && neteaseSongId != null -> Triple(CommentSource.NETEASE, neteaseSongId, neteaseTotal)
                kuwoTotal >= neteaseTotal -> Triple(CommentSource.KUWO, kuwoSongId!!, kuwoTotal)
                else -> Triple(CommentSource.NETEASE, neteaseSongId!!, neteaseTotal)
            }

            actualCommentSource = selectedSource
            actualSongId = selectedSongId

            withContext(Dispatchers.Main) {
                tvCommentCount?.text = "($total)"
            }

            when (selectedSource) {
                CommentSource.KUWO -> loadKuwoCommentsWithId(selectedSongId)
                CommentSource.NETEASE -> loadNeteaseCommentsWithId(selectedSongId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载本地歌曲评论失败", e)
            emptyList()
        }
    }

    private suspend fun searchKuwoSong(keyword: String): String? {
        return try {
            val response = RetrofitClient.kuwoApi.searchMusic(keywords = keyword)
            val jsonString = response.string()
            val jsonObject = com.google.gson.JsonParser.parseString(jsonString).asJsonObject
            val abslist = jsonObject.getAsJsonArray("abslist")

            if (abslist != null && abslist.size() > 0) {
                val firstItem = abslist[0].asJsonObject
                val musicRid = firstItem.get("MUSICRID")?.asString
                musicRid?.replace("MUSIC_", "")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "搜索酷我歌曲失败", e)
            null
        }
    }

    private suspend fun searchNeteaseSong(keyword: String): String? {
        return try {
            val response = RetrofitClient.musicSearchApi.searchSongs(name = keyword)
            val neteaseResult = response.firstOrNull { it.source == "netease" || it.source == "wy" }
            neteaseResult?.id
        } catch (e: Exception) {
            Log.e(TAG, "搜索网易云歌曲失败", e)
            null
        }
    }

    private suspend fun getKuwoCommentTotal(songId: String): Int {
        return try {
            val response = RetrofitClient.kuwoCommentApi.getComments(
                type = "get_comment",
                page = 1,
                rows = 1,
                songId = songId
            )
            response.total ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun getNeteaseCommentTotal(songId: String): Int {
        return try {
            val response = RetrofitClient.neteaseCommentApi.getLatestComments(
                songId = songId,
                limit = 1,
                offset = 0
            )
            response.total ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun loadKuwoComments(): List<Comment> {
        return loadKuwoCommentsWithId(songId)
    }

    private suspend fun loadKuwoCommentsWithId(id: String): List<Comment> {
        val type = if (currentCommentTab == 0) "get_rec_comment" else "get_comment"
        return try {
            val response = RetrofitClient.kuwoCommentApi.getComments(
                type = type,
                page = commentPage,
                rows = commentPageSize,
                songId = id
            )

            val total = response.total ?: 0
            withContext(Dispatchers.Main) {
                tvCommentCount?.text = "($total)"
            }

            response.rows?.map { item ->
                Comment(
                    id = item.id ?: "",
                    content = item.content,
                    userName = item.userName ?: "未知用户",
                    userAvatar = item.userAvatar,
                    time = item.time,
                    likeCount = item.likeCount?.toIntOrNull() ?: 0
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun loadNeteaseComments(): List<Comment> {
        return loadNeteaseCommentsWithId(songId)
    }

    private suspend fun loadNeteaseCommentsWithId(id: String): List<Comment> {
        return try {
            val response = if (currentCommentTab == 0) {
                RetrofitClient.neteaseCommentApi.getHotComments(
                    songId = id,
                    limit = commentPageSize,
                    offset = (commentPage - 1) * commentPageSize
                )
            } else {
                RetrofitClient.neteaseCommentApi.getLatestComments(
                    songId = id,
                    limit = commentPageSize,
                    offset = (commentPage - 1) * commentPageSize
                )
            }

            val total = response.total ?: 0
            withContext(Dispatchers.Main) {
                tvCommentCount?.text = "($total)"
            }

            val commentList = if (currentCommentTab == 0) response.hotComments else response.comments
            commentList?.map { item ->
                Comment(
                    id = item.commentId?.toString() ?: "",
                    content = item.content,
                    userName = item.user?.nickname ?: "未知用户",
                    userAvatar = item.user?.avatarUrl,
                    time = item.time,
                    likeCount = item.likedCount ?: 0
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
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
            seekBar.max = duration.toInt()
            seekBar.progress = currentPosition.toInt()
            tvCurrentTime.text = formatTime(currentPosition)
            tvTotalTime.text = formatTime(duration)
        }
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress.toLong())
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
                val seekPosition = seekBar?.progress?.toLong() ?: 0
                val intent = Intent(PlayerActivity.ACTION_SEEK_TO).apply {
                    putExtra(PlayerActivity.EXTRA_SEEK_POSITION, seekPosition)
                }
                LocalBroadcastManager.getInstance(this@LyricsActivity).sendBroadcast(intent)
                currentPosition = seekPosition
                updateLyricsHighlight(seekPosition)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(PlayerActivity.ACTION_PLAYBACK_UPDATE)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(playbackReceiver, filter)
        lyricStylePrefs.registerOnSharedPreferenceChangeListener(lyricStyleListener)
        applyFullscreenLyricStyle()
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_REQUEST_PLAYBACK_STATUS))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playbackReceiver)
        lyricStylePrefs.unregisterOnSharedPreferenceChangeListener(lyricStyleListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // 保存当前状态
        val currentParsedLyrics = parsedLyrics
        val currentLyricIndex = this.currentLyricIndex
        val currentPosition = this.currentPosition
        val currentDuration = this.duration
        val currentIsPlaying = this.isPlaying
        val currentSongName = this.songName
        val currentSongArtists = this.songArtists
        val currentSongId = this.songId
        val currentPlatform = this.platform
        val currentCoverUrl = this.coverUrl

        // 重新加载布局
        setContentView(R.layout.activity_lyrics)

        // 恢复状态
        this.songName = currentSongName
        this.songArtists = currentSongArtists
        this.songId = currentSongId
        this.platform = currentPlatform
        this.coverUrl = currentCoverUrl
        this.parsedLyrics = currentParsedLyrics
        this.currentLyricIndex = currentLyricIndex
        this.currentPosition = currentPosition
        this.duration = currentDuration
        this.isPlaying = currentIsPlaying

        // 重新初始化
        initViews()
        setupRecyclerView()
        setupClickListeners()
        setupGestureDetector()
        setupPlaybackReceiver()
        setupSeekBar()

        // 恢复UI
        tvSongName.text = songName
        tvArtist.text = songArtists

        // 恢复歌词
        lyricsAdapter.submitList(parsedLyrics.map { it.second })
        updateLyricsHighlight(currentPosition)

        // 恢复背景
        loadBackground(coverUrl)

        // 恢复进度
        updateProgressUI()

        // 如果是横屏，初始化评论
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setupComments()
        }
    }

    private fun setupRecyclerView() {
        lyricsAdapter = LyricsAdapter(
            onLyricClick = { _ -> },
            onLyricLongClick = { position ->
                onLyricLineLongPressed(position)
            },
            onLyricJumpClick = { position ->
                if (position in parsedLyrics.indices) {
                    seekTo(parsedLyrics[position].first)
                }
            }
        )

        layoutManager = LinearLayoutManager(this)
        rvLyrics.layoutManager = layoutManager
        rvLyrics.adapter = lyricsAdapter
        rvLyrics.addItemDecoration(LyricsItemDecoration())

        rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        isUserScrolling = true
                        showLocateButton()
                        userScrollTimeout?.removeCallbacksAndMessages(null)
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        userScrollTimeout?.removeCallbacksAndMessages(null)
                        userScrollTimeout = android.os.Handler(android.os.Looper.getMainLooper())
                        userScrollTimeout?.postDelayed({
                            isUserScrolling = false
                            hideLocateButton()
                            hideLyricJumpButton()
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
        btnLocateCurrent?.visibility = View.VISIBLE
    }

    private fun hideLocateButton() {
        btnLocateCurrent?.visibility = View.GONE
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnLocateCurrent?.setOnClickListener {
            isUserScrolling = false
            hideLocateButton()
            if (currentLyricIndex >= 0) {
                scrollToCenter(currentLyricIndex)
            }
        }

        btnJumpToLyric?.setOnClickListener {
            val target = pendingLyricJumpTime
            if (target != null) {
                seekTo(target)
            }
            hideLyricJumpButton()
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

                if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY)) {
                    if (kotlin.math.abs(diffX) > SWIPE_THRESHOLD && kotlin.math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            finish()
                            return true
                        }
                    }
                }
                return false
            }
        })

        findViewById<View>(android.R.id.content).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun loadSongInfo() {
        hideLyricJumpButton()

        songName = intent.getStringExtra("song_name") ?: "未知歌曲"
        songArtists = intent.getStringExtra("song_artists") ?: "未知歌手"
        coverUrl = intent.getStringExtra("cover_url")
        val lyricsText = intent.getStringExtra("lyrics")
        songId = intent.getStringExtra("song_id") ?: ""
        val platformStr = intent.getStringExtra("platform") ?: "KUWO"
        platform = try {
            MusicRepository.Platform.valueOf(platformStr)
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }
        currentPosition = intent.getLongExtra("current_position", 0)
        duration = intent.getLongExtra("duration", 0)
        isPlaying = intent.getBooleanExtra("is_playing", false)

        // 设置歌曲信息
        tvSongName.text = songName
        tvArtist.text = songArtists

        // 启用跑马灯效果
        tvSongName.isSelected = true
        tvArtist.isSelected = true

        // 加载背景
        if (!coverUrl.isNullOrEmpty()) {
            loadBackground(coverUrl)
        } else {
            lifecycleScope.launch {
                try {
                    val song = com.tacke.music.data.model.Song(
                        index = 0,
                        id = songId,
                        name = songName,
                        artists = songArtists,
                        platform = platform.name,
                        coverUrl = coverUrl
                    )
                    val coverResult = SongCoverLoader.loadCover(
                        this@LyricsActivity,
                        song,
                        SongCoverLoader.SourceType.PLAYLIST_DETAIL,
                        null
                    )
                    if (coverResult.coverUrl != null) {
                        coverUrl = coverResult.coverUrl
                        loadBackground(coverUrl)
                    }
                } catch (e: Exception) {
                    // 静默处理
                }
            }
        }

        // 解析歌词
        if (!lyricsText.isNullOrEmpty()) {
            parsedLyrics = parseLyrics(lyricsText)
            if (parsedLyrics.isNotEmpty()) {
                lyricsAdapter.submitList(parsedLyrics.map { it.second })
                updateLyricsHighlight(currentPosition)
            } else {
                lyricsAdapter.submitList(listOf("纯音乐，请欣赏"))
            }
        } else {
            parsedLyrics = emptyList()
            lyricsAdapter.submitList(listOf("正在加载歌词..."))
            loadLyricsFromNetwork()
        }

        // 初始化进度
        seekBar.max = duration.toInt()
        seekBar.progress = currentPosition.toInt()
        tvCurrentTime.text = formatTime(currentPosition)
        tvTotalTime.text = formatTime(duration)
    }

    private fun loadLyricsFromNetwork() {
        if (isLoadingLyrics || songId.isEmpty()) {
            if (songId.isEmpty()) {
                lyricsAdapter.submitList(listOf("暂无歌词"))
            }
            return
        }

        isLoadingLyrics = true
        lifecycleScope.launch {
            try {
                val cachedRepository = CachedMusicRepository(this@LyricsActivity)
                val playbackQuality = SettingsActivity.getPlaybackQuality(this@LyricsActivity)
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongDetail(
                        platform = platform,
                        songId = songId,
                        quality = playbackQuality,
                        coverUrlFromSearch = coverUrl,
                        songName = songName,
                        artists = songArtists
                    )
                }

                if (detail?.lyrics != null && detail.lyrics.isNotEmpty()) {
                    parsedLyrics = parseLyrics(detail.lyrics)
                    if (parsedLyrics.isNotEmpty()) {
                        lyricsAdapter.submitList(parsedLyrics.map { it.second })
                        updateLyricsHighlight(currentPosition)
                        Toast.makeText(this@LyricsActivity, "歌词加载成功", Toast.LENGTH_SHORT).show()
                    } else {
                        lyricsAdapter.submitList(listOf("纯音乐，请欣赏"))
                    }
                } else {
                    lyricsAdapter.submitList(listOf("暂无歌词"))
                }
            } catch (e: Exception) {
                lyricsAdapter.submitList(listOf("暂无歌词"))
            } finally {
                isLoadingLyrics = false
            }
        }
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

            if (!isUserScrolling) {
                scrollToCenter(newIndex)
            }
        }
    }

    private fun applyFullscreenLyricStyle() {
        lyricsAdapter.notifyDataSetChanged()
    }

    private fun scrollToCenter(position: Int) {
        val layoutManager = rvLyrics.layoutManager as? LinearLayoutManager ?: return
        val recyclerViewHeight = rvLyrics.height
        if (recyclerViewHeight <= 0) return

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
        currentPosition = time
        updateLyricsHighlight(time)
        seekBar.progress = time.toInt()
        tvCurrentTime.text = formatTime(time)
        hideLyricJumpButton()
    }

    private fun onLyricLineLongPressed(position: Int) {
        if (position !in parsedLyrics.indices) return
        val target = parsedLyrics[position].first
        pendingLyricJumpTime = target
        lyricsAdapter.setJumpTarget(position, formatTime(target))
        btnJumpToLyric?.visibility = View.GONE
    }

    private fun hideLyricJumpButton() {
        pendingLyricJumpTime = null
        btnJumpToLyric?.visibility = View.GONE
        lyricsAdapter.clearJumpTarget()
    }

    private fun loadBackground(coverUrl: String?) {
        val normalizedUrl = normalizeCoverUrl(coverUrl)
        if (!normalizedUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(normalizedUrl)
                .placeholder(R.drawable.bg_default_light_cyan)
                .error(R.drawable.bg_default_light_cyan)
                .into(ivBackground)
            ivBackground.visibility = View.VISIBLE
        } else {
            ivBackground.visibility = View.GONE
        }
    }

    private fun normalizeCoverUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var trimmed = url.trim()

        if (trimmed.startsWith("http", ignoreCase = true)) {
            val localPathIndex = trimmed.indexOf("//data/user/0/")
            if (localPathIndex > 0) {
                trimmed = trimmed.substring(localPathIndex)
            } else {
                val cachePathIndex = trimmed.indexOf("/data/user/0/")
                if (cachePathIndex > 0) {
                    trimmed = trimmed.substring(cachePathIndex)
                }
            }
        }

        if (trimmed.startsWith("/")) {
            val queryIndex = trimmed.indexOf("?")
            if (queryIndex > 0) {
                trimmed = trimmed.substring(0, queryIndex)
            }
            return trimmed
        }

        return trimmed
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
