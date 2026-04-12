package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.tacke.music.R
import com.tacke.music.data.repository.CommentRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.ui.adapter.CommentAdapter
import com.tacke.music.ui.viewmodel.CommentViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 歌曲评论页面 - 重构后版本
 * 优化点：
 * 1. 使用ViewModel管理状态
 * 2. 支持评论缓存
 * 3. 优化加载更多逻辑（防抖）
 * 4. 显示加载更多状态
 */
class SongCommentsActivity : AppCompatActivity() {

    private lateinit var tvSongName: TextView
    private lateinit var tvArtist: TextView
    private var tvCommentCount: TextView? = null
    private var tvCommentCountHeader: TextView? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var tabLayout: LinearLayout
    private lateinit var btnHotComments: TextView
    private lateinit var btnLatestComments: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: View
    private lateinit var emptyState: View
    private lateinit var errorState: View
    private lateinit var btnBack: View
    private lateinit var btnRetry: View
    private var ivAlbumCover: ImageView? = null

    private lateinit var commentAdapter: CommentAdapter

    private val viewModel: CommentViewModel by viewModels()

    private var songId: String = ""
    private var songName: String = ""
    private var artist: String = ""
    private var songCover: String = ""
    private lateinit var platform: MusicRepository.Platform

    companion object {
        private const val EXTRA_SONG_ID = "song_id"
        private const val EXTRA_SONG_NAME = "song_name"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_PLATFORM = "platform"
        private const val EXTRA_SONG_COVER = "song_cover"

        fun start(
            context: Context,
            songId: String,
            songName: String,
            artist: String,
            platform: MusicRepository.Platform,
            songCover: String? = null
        ) {
            val intent = Intent(context, SongCommentsActivity::class.java).apply {
                putExtra(EXTRA_SONG_ID, songId)
                putExtra(EXTRA_SONG_NAME, songName)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_PLATFORM, platform.name)
                putExtra(EXTRA_SONG_COVER, songCover)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_comments)

        initViews()
        parseIntent()
        setupUI()
        setupRecyclerView()
        setupTabLayout()
        setupClickListeners()
        
        // 先观察ViewModel，确保能收到初始状态
        observeViewModel()

        // 延迟一帧初始化ViewModel，确保观察者已经就绪
        recyclerView.post {
            // 初始化ViewModel
            // 注意：不使用forceReload，让ViewModel自己判断是否需要重新加载
            // 如果ViewModel已经有数据且是同一首歌，会直接显示已有数据
            viewModel.init(
                songId = songId,
                songName = songName,
                artist = artist,
                platform = platform,
                sortType = CommentRepository.SortType.HOT,
                forceReload = false  // 让ViewModel自己判断是否需要重新加载
            )
        }
    }

    private fun initViews() {
        tvSongName = findViewById(R.id.tvSongName)
        tvArtist = findViewById(R.id.tvArtist)
        tvCommentCount = findViewById(R.id.tvCommentCount)
        tvCommentCountHeader = findViewById(R.id.tvCommentCountHeader)
        recyclerView = findViewById(R.id.recyclerView)
        tabLayout = findViewById(R.id.tabLayout)
        btnHotComments = findViewById(R.id.btnHotComments)
        btnLatestComments = findViewById(R.id.btnLatestComments)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        progressBar = findViewById(R.id.progressBar)
        emptyState = findViewById(R.id.emptyState)
        errorState = findViewById(R.id.errorState)
        btnBack = findViewById(R.id.btnBack)
        btnRetry = findViewById(R.id.btnRetry)
        ivAlbumCover = findViewById(R.id.ivAlbumCover)
    }

    private fun parseIntent() {
        songId = intent.getStringExtra(EXTRA_SONG_ID) ?: ""
        songName = intent.getStringExtra(EXTRA_SONG_NAME) ?: ""
        artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
        songCover = intent.getStringExtra(EXTRA_SONG_COVER) ?: ""
        val platformStr = intent.getStringExtra(EXTRA_PLATFORM) ?: "KUWO"
        platform = try {
            MusicRepository.Platform.valueOf(platformStr)
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }
    }

    private fun setupUI() {
        tvSongName.text = songName
        tvArtist.text = artist

        // 加载封面（横屏模式下）
        if (ivAlbumCover != null && !songCover.isNullOrBlank()) {
            Glide.with(this)
                .load(songCover)
                .placeholder(R.drawable.ic_album_default)
                .error(R.drawable.ic_album_default)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(ivAlbumCover!!)
        }

        // 应用封面样式
        applyCoverStyle()
    }

    private fun applyCoverStyle() {
        val albumCardView = findViewById<androidx.cardview.widget.CardView?>(R.id.albumCardView)
        if (albumCardView == null) return

        albumCardView.radius = 16f
    }

    private fun setupRecyclerView() {
        commentAdapter = CommentAdapter()
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@SongCommentsActivity)
            adapter = commentAdapter
            setHasFixedSize(true) // 优化性能
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dy > 0) { // 只向下滚动时触发
                        checkLoadMore()
                    }
                }
            })
        }

        // 设置下拉刷新
        swipeRefreshLayout.apply {
            setColorSchemeResources(
                R.color.primary,
                R.color.accent_cyan,
                R.color.accent_purple
            )
            setOnRefreshListener {
                refreshComments()
            }
        }
    }

    private fun refreshComments() {
        lifecycleScope.launch {
            try {
                viewModel.refresh()
                Toast.makeText(this@SongCommentsActivity, "刷新成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SongCommentsActivity, "刷新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    /**
     * 检查是否需要加载更多
     */
    private fun checkLoadMore() {
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        val totalItemCount = layoutManager.itemCount

        // 距离底部5个item时触发加载
        if (lastVisibleItem >= totalItemCount - 5) {
            viewModel.loadMoreComments()
        }
    }

    private fun setupTabLayout() {
        btnHotComments.setOnClickListener {
            updateTabSelection(true)
            viewModel.switchSortType(CommentRepository.SortType.HOT)
        }

        btnLatestComments.setOnClickListener {
            updateTabSelection(false)
            viewModel.switchSortType(CommentRepository.SortType.LATEST)
        }
    }

    private fun updateTabSelection(isHotSelected: Boolean) {
        if (isHotSelected) {
            btnHotComments.setBackgroundResource(R.drawable.bg_tab_selected)
            btnHotComments.alpha = 1f
            btnLatestComments.setBackgroundResource(android.R.color.transparent)
            btnLatestComments.alpha = 0.6f
        } else {
            btnLatestComments.setBackgroundResource(R.drawable.bg_tab_selected)
            btnLatestComments.alpha = 1f
            btnHotComments.setBackgroundResource(android.R.color.transparent)
            btnHotComments.alpha = 0.6f
        }
    }

    private fun setupClickListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnRetry.setOnClickListener {
            viewModel.retry()
        }
    }

    /**
     * 观察ViewModel状态
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 观察UI状态
                launch {
                    viewModel.uiState.collect { state ->
                        when (state) {
                            is CommentViewModel.CommentUiState.Loading -> showLoading()
                            is CommentViewModel.CommentUiState.Empty -> showEmpty()
                            is CommentViewModel.CommentUiState.Error -> showError(state.message)
                            is CommentViewModel.CommentUiState.Content -> showContent(state.hasMore)
                        }
                    }
                }

                // 观察评论数据
                launch {
                    viewModel.comments.collect { comments ->
                        commentAdapter.submitComments(comments)
                    }
                }

                // 观察评论总数
                launch {
                    viewModel.totalCount.collect { count ->
                        tvCommentCount?.text = "共 $count 条评论"
                        tvCommentCountHeader?.text = "共 $count 条评论"
                    }
                }

                // 观察加载更多状态
                launch {
                    viewModel.loadingState.collect { state ->
                        when (state) {
                            is CommentRepository.CommentLoadingState.LoadingMore -> {
                                commentAdapter.setLoadingMore(true)
                            }
                            else -> {
                                commentAdapter.setLoadingMore(false)
                            }
                        }
                    }
                }

                // 观察事件
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is CommentViewModel.CommentEvent.ShowToast -> {
                                Toast.makeText(this@SongCommentsActivity, event.message, Toast.LENGTH_SHORT).show()
                            }
                            is CommentViewModel.CommentEvent.ScrollToTop -> {
                                recyclerView.scrollToPosition(0)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE
        errorState.visibility = View.GONE
    }

    private fun showContent(hasMore: Boolean) {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        errorState.visibility = View.GONE
    }

    private fun showEmpty() {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        errorState.visibility = View.GONE
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        recyclerView.visibility = View.GONE
        emptyState.visibility = View.GONE
        errorState.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        // 不再调用reset()，避免横竖屏切换时清空数据
        // CommentRepository.getInstance().reset()
    }
}
