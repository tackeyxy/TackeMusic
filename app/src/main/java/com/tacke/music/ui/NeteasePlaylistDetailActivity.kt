package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import jp.wasabeef.glide.transformations.BlurTransformation
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.api.PlaylistTrack
import com.tacke.music.data.api.RetrofitClient
import com.tacke.music.data.api.SongDetailInfo
import com.tacke.music.data.api.TrackArtist
import com.tacke.music.data.api.TrackAlbum
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityNeteasePlaylistDetailBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.NeteasePlaylistTrackAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class NeteasePlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNeteasePlaylistDetailBinding
    private lateinit var trackAdapter: NeteasePlaylistTrackAdapter
    private lateinit var playlistManager: PlaylistManager
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playbackManager: PlaybackManager
    private val repository = MusicRepository()

    private var playlistId: Long = 0
    private var playlistName: String = ""
    private var playlistCover: String = ""
    private var playlistDescription: String = ""
    private var playlistCreatorName: String = ""
    private var playlistCreatorAvatar: String = ""
    private var playlistTrackCount: Int = 0
    private var playlistPlayCount: Long = 0
    private var isMultiSelectMode = false

    // 分页加载相关
    private var allTrackIds: List<Long> = emptyList()
    private var loadedTrackCount = 0
    private var isLoadingMore = false
    private val batchSize = 100

    companion object {
        fun start(context: Context, playlistId: Long, playlistName: String, playlistCover: String) {
            val intent = Intent(context, NeteasePlaylistDetailActivity::class.java).apply {
                putExtra("playlist_id", playlistId)
                putExtra("playlist_name", playlistName)
                putExtra("playlist_cover", playlistCover)
            }
            context.startActivity(intent)
        }

        /**
         * 格式化播放数量
         */
        fun formatPlayCount(count: Long): String {
            return when {
                count >= 100_000_000 -> "${count / 100_000_000}亿"
                count >= 10_000 -> "${count / 10_000}万"
                count >= 1_000 -> "${count / 1_000}千"
                else -> count.toString()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNeteasePlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16: 适配 Edge-to-Edge 模式
        setupEdgeToEdge()

        playlistId = intent.getLongExtra("playlist_id", 0)
        playlistName = intent.getStringExtra("playlist_name") ?: ""
        playlistCover = intent.getStringExtra("playlist_cover") ?: ""

        if (playlistId == 0L) {
            Toast.makeText(this, "歌单不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        playlistManager = PlaylistManager.getInstance(this)
        playlistRepository = PlaylistRepository(this)
        favoriteRepository = FavoriteRepository(this)
        playbackManager = PlaybackManager.getInstance(this)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        setupBatchActionListeners()
        setupScrollListener()
        setupAppBarBehavior()
        loadPlaylistDetail()
    }

    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕旋转时重新设置视图
        // 由于使用了不同的布局文件，需要重新初始化视图
        binding = ActivityNeteasePlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge()
        setupUI()
        // 重新设置RecyclerView，但使用现有的adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = trackAdapter
        setupClickListeners()
        setupBatchActionListeners()
        setupScrollListener()
        setupAppBarBehavior()
        // 恢复多选模式状态
        if (isMultiSelectMode) {
            showBatchActionBar()
        }
    }

    private fun setupUI() {
        binding.tvTitle?.text = playlistName
        binding.tvPlaylistName.text = playlistName

        // 加载封面
        Glide.with(this)
            .load(playlistCover)
            .placeholder(com.tacke.music.R.drawable.ic_album_default)
            .error(com.tacke.music.R.drawable.ic_album_default)
            .into(binding.ivPlaylistCover)

        // 加载模糊背景
        binding.ivBlurBackground?.let {
            Glide.with(this)
                .load(playlistCover)
                .placeholder(com.tacke.music.R.drawable.ic_album_default)
                .error(com.tacke.music.R.drawable.ic_album_default)
                .transform(BlurTransformation(25, 3))
                .into(it)
        }

        // 更新其他歌单信息
        updatePlaylistUI()
    }

    private fun updatePlaylistUI() {
        // 更新歌曲数量
        binding.tvSongCount?.text = "$playlistTrackCount 首歌曲"
        binding.tvTrackCount?.text = "$playlistTrackCount 首"

        // 更新播放量
        binding.tvPlayCount?.text = formatPlayCount(playlistPlayCount)

        // 更新简介
        binding.tvDescription?.text = playlistDescription

        // 更新创建者信息
        binding.tvCreatorName?.text = playlistCreatorName
        binding.ivCreatorAvatar?.let {
            Glide.with(this)
                .load(playlistCreatorAvatar)
                .placeholder(com.tacke.music.R.drawable.ic_album_default)
                .error(com.tacke.music.R.drawable.ic_album_default)
                .circleCrop()
                .into(it)
        }
    }

    private fun setupRecyclerView() {
        trackAdapter = NeteasePlaylistTrackAdapter(
            onItemClick = { track ->
                if (isMultiSelectMode) {
                    updateBatchActionBar()
                } else {
                    // 推荐歌单列表点击歌曲：添加到播放列表并播放
                    addToNowPlayingAndPlay(track)
                }
            },
            onLongClick = { track ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    true
                } else {
                    false
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = trackAdapter
    }

    private fun setupClickListeners() {
        // Toolbar 导航按钮点击事件 - 竖屏布局
        (binding.toolbar as? androidx.appcompat.widget.Toolbar)?.setNavigationOnClickListener {
            if (isMultiSelectMode) {
                exitMultiSelectMode()
            } else {
                finish()
            }
        }
        // 横屏布局返回按钮
        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            if (isMultiSelectMode) {
                exitMultiSelectMode()
            } else {
                finish()
            }
        }

        binding.btnPlayAll.setOnClickListener {
            if (!isMultiSelectMode) {
                playAllTracks()
            }
        }

        // 收藏按钮
        binding.btnCollect.setOnClickListener {
            Toast.makeText(this, "收藏功能开发中", Toast.LENGTH_SHORT).show()
        }

        // 分享按钮
        binding.btnShare.setOnClickListener {
            sharePlaylist()
        }
    }

    private fun sharePlaylist() {
        val shareText = "分享歌单：$playlistName\nhttps://music.163.com/playlist?id=$playlistId"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(intent, "分享歌单"))
    }

    private fun setupBatchActionListeners() {
        // 关闭按钮 - 竖屏
        binding.batchActionBarContainer?.btnCloseBatch?.setOnClickListener {
            exitMultiSelectMode()
        }
        // 关闭按钮 - 横屏
        findViewById<View>(R.id.btnCloseBatch)?.setOnClickListener {
            exitMultiSelectMode()
        }

        // 全选按钮 - 竖屏
        binding.batchActionBarContainer?.btnSelectAll?.setOnClickListener {
            trackAdapter.selectAll()
            updateBatchActionBar()
        }
        // 全选按钮 - 横屏
        findViewById<View>(R.id.btnSelectAll)?.setOnClickListener {
            trackAdapter.selectAll()
            updateBatchActionBar()
        }

        // 下载按钮 - 竖屏
        binding.batchActionBarContainer?.btnBatchDownload?.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchDownloadQualityDialog(selectedTracks)
        }
        // 下载按钮 - 横屏
        findViewById<View>(R.id.btnBatchDownload)?.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchDownloadQualityDialog(selectedTracks)
        }

        // 添加到喜欢按钮 - 竖屏
        binding.batchActionBarContainer?.btnAddToFavorite?.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addTracksToFavorites(selectedTracks)
        }
        // 添加到喜欢按钮 - 横屏
        findViewById<View>(R.id.btnAddToFavorite)?.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addTracksToFavorites(selectedTracks)
        }

        // 添加到歌单按钮 - 竖屏
        binding.batchActionBarContainer?.btnAddToPlaylist?.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchPlaylistSelectionDialog(selectedTracks)
        }
        // 添加到歌单按钮 - 横屏
        findViewById<View>(R.id.btnAddToPlaylist)?.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchPlaylistSelectionDialog(selectedTracks)
        }

        // 加入播放按钮 - 竖屏
        binding.batchActionBarContainer?.btnAddToNowPlaying?.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addTracksToNowPlaying(selectedTracks)
        }
        // 加入播放按钮 - 横屏
        findViewById<View>(R.id.btnAddToNowPlaying)?.setOnClickListener {
            val selectedTracks = trackAdapter.getSelectedTracks()
            if (selectedTracks.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addTracksToNowPlaying(selectedTracks)
        }
    }

    private fun setupAppBarBehavior() {
        // 监听AppBar折叠状态，动态改变标题栏颜色（仅竖屏布局有AppBarLayout）
        (binding.appBarLayout as? AppBarLayout)?.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
            val totalScrollRange = appBarLayout.totalScrollRange
            val progress = abs(verticalOffset).toFloat() / totalScrollRange

            // 根据滚动进度改变标题颜色
            when {
                progress < 0.5f -> {
                    // 展开状态 - 白色文字
                    binding.tvTitle?.setTextColor(Color.WHITE)
                    (binding.toolbar as? androidx.appcompat.widget.Toolbar)?.navigationIcon?.setTint(Color.WHITE)
                }
                else -> {
                    // 折叠状态 - 深色文字
                    binding.tvTitle?.setTextColor(getColor(R.color.text_primary))
                    (binding.toolbar as? androidx.appcompat.widget.Toolbar)?.navigationIcon?.setTint(getColor(R.color.text_primary))
                }
            }
        })
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        trackAdapter.setMultiSelectMode(true)
        showBatchActionBar()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        trackAdapter.setMultiSelectMode(false)
        hideBatchActionBar()
    }

    private fun showBatchActionBar() {
        // 竖屏布局中的批量操作栏
        binding.batchActionBarContainer?.root?.visibility = View.VISIBLE
        // 横屏布局中的批量操作栏
        val batchActionBarContainerLand = findViewById<View>(R.id.batchActionBarContainerLand)
        batchActionBarContainerLand?.visibility = View.VISIBLE
        // 隐藏清空按钮（歌单列表不需要清空功能）
        binding.batchActionBarContainer?.btnClearAll?.visibility = View.GONE
        findViewById<View>(R.id.btnClearAll)?.visibility = View.GONE
        updateBatchActionBar()
        setupBatchActionListeners()
    }

    private fun hideBatchActionBar() {
        // 竖屏布局中的批量操作栏
        binding.batchActionBarContainer?.root?.visibility = View.GONE
        // 横屏布局中的批量操作栏
        val batchActionBarContainerLand = findViewById<View>(R.id.batchActionBarContainerLand)
        batchActionBarContainerLand?.visibility = View.GONE
    }

    private fun updateBatchActionBar() {
        val selectedCount = trackAdapter.getSelectedTracks().size
        binding.batchActionBarContainer?.tvSelectedCount?.text = selectedCount.toString()
        findViewById<android.widget.TextView>(R.id.tvSelectedCount)?.text = selectedCount.toString()
    }

    private fun setupScrollListener() {
        // 竖屏布局使用 NestedScrollView
        binding.nestedScrollView?.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
                // 检查是否滚动到底部
                val child = v.getChildAt(0)
                if (child != null) {
                    val diff = child.bottom - (v.height + scrollY)
                    // 当距离底部小于100px时触发加载更多
                    if (diff < 100 && !isLoadingMore && loadedTrackCount < allTrackIds.size) {
                        loadMoreTracks()
                    }
                }
            }
        )

        // 横屏布局使用 RecyclerView 的滚动监听
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                if (layoutManager != null) {
                    val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                    val totalItemCount = layoutManager.itemCount
                    
                    // 当最后一个可见项是倒数第5个或更后时触发加载更多
                    if (lastVisibleItem >= totalItemCount - 5 && !isLoadingMore && loadedTrackCount < allTrackIds.size) {
                        loadMoreTracks()
                    }
                }
            }
        })
    }

    private fun loadPlaylistDetail() {
        lifecycleScope.launch {
            binding.progressBar?.visibility = View.VISIBLE
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.playlistApi.getPlaylistDetail(playlistId)
                }

                if (response.code == 200 && response.playlist != null) {
                    val playlist = response.playlist
                    playlistName = playlist.name
                    playlistCover = playlist.coverImgUrl ?: ""
                    playlistDescription = playlist.description ?: "暂无简介"
                    playlistCreatorName = playlist.creator?.nickname ?: "未知用户"
                    playlistCreatorAvatar = playlist.creator?.avatarUrl ?: ""
                    playlistTrackCount = playlist.trackCount
                    playlistPlayCount = playlist.playCount

                    // 更新UI
                    updatePlaylistUI()

                    // 重新加载封面
                    Glide.with(this@NeteasePlaylistDetailActivity)
                        .load(playlistCover)
                        .placeholder(com.tacke.music.R.drawable.ic_album_default)
                        .error(com.tacke.music.R.drawable.ic_album_default)
                        .into(binding.ivPlaylistCover)

                    // 重新加载模糊背景
                    binding.ivBlurBackground?.let {
                        Glide.with(this@NeteasePlaylistDetailActivity)
                            .load(playlistCover)
                            .placeholder(com.tacke.music.R.drawable.ic_album_default)
                            .error(com.tacke.music.R.drawable.ic_album_default)
                            .transform(BlurTransformation(25, 3))
                            .into(it)
                    }

                    // 获取所有trackIds
                    allTrackIds = playlist.trackIds?.map { it.id } ?: emptyList()

                    // 如果tracks已经有数据，先显示
                    val initialTracks = playlist.tracks ?: emptyList()
                    if (initialTracks.isNotEmpty()) {
                        trackAdapter.submitList(initialTracks)
                        loadedTrackCount = initialTracks.size
                        updateEmptyState(false)
                    } else {
                        updateEmptyState(true)
                    }

                    // 如果还有更多歌曲需要加载
                    if (loadedTrackCount < allTrackIds.size) {
                        loadMoreTracks()
                    }
                } else {
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "加载歌单失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar?.visibility = View.GONE
            }
        }
    }

    private fun loadMoreTracks() {
        if (isLoadingMore || loadedTrackCount >= allTrackIds.size) return

        isLoadingMore = true
        binding.progressBarLoadMore?.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // 计算本次要加载的id范围
                val endIndex = minOf(loadedTrackCount + batchSize, allTrackIds.size)
                val batchIds = allTrackIds.subList(loadedTrackCount, endIndex)

                // 构建ids参数 [id1,id2,id3,...]
                val idsString = "[${batchIds.joinToString(",")}]"

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.playlistApi.getSongDetails(idsString)
                }

                if (response.code == 200 && response.songs != null) {
                    val newTracks = response.songs.map { it.toPlaylistTrack() }
                    trackAdapter.addTracks(newTracks)
                    loadedTrackCount += batchIds.size
                    updateEmptyState(trackAdapter.getAllTracks().isEmpty())
                }
            } catch (e: Exception) {
                // 加载失败，不中断流程
            } finally {
                isLoadingMore = false
                binding.progressBarLoadMore?.visibility = View.GONE
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyView?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    /**
     * 添加歌曲到播放列表并播放（推荐歌单列表使用）
     * 非下载管理页面，需要重新请求URL进行播放
     */
    private fun addToNowPlayingAndPlay(track: PlaylistTrack) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val platform = MusicRepository.Platform.NETEASE
                val artistName = track.ar?.joinToString(",") { it.name } ?: "未知艺人"
                val coverUrl = track.al?.picUrl ?: ""

                // 非下载管理页面，强制重新获取最新URL，但封面和歌词使用缓存
                val cachedRepository = CachedMusicRepository(this@NeteasePlaylistDetailActivity)
                // 获取用户设置的试听音质
                val playbackQuality = SettingsActivity.getPlaybackQuality(this@NeteasePlaylistDetailActivity)
                Log.d("NeteasePlaylistDetail", "使用试听音质: $playbackQuality")
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongUrlWithCache(
                        platform = platform,
                        songId = track.id.toString(),
                        quality = playbackQuality,
                        songName = track.name,
                        artists = artistName,
                        useCache = true,
                        coverUrlFromSearch = coverUrl
                    )
                }

                if (detail != null && detail.url.isNotEmpty()) {
                    val song = Song(
                        index = 0,
                        id = track.id.toString(),
                        name = track.name,
                        artists = artistName,
                        coverUrl = detail.cover ?: coverUrl
                    )
                    playlistManager.addSong(playlistManager.convertToPlaylistSong(song, platform))

                    playbackManager.playFromSearch(this@NeteasePlaylistDetailActivity, song, platform, detail)
                } else {
                    // 检查是否是API服务问题
                    Toast.makeText(
                        this@NeteasePlaylistDetailActivity,
                        "无法获取歌曲播放链接，音乐服务可能暂时不可用，请稍后重试",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("403") == true -> "音乐服务暂时不可用(403)，请稍后重试"
                    e.message?.contains("HTTP") == true -> "网络服务异常，请稍后重试"
                    else -> "播放失败: ${e.message}"
                }
                Toast.makeText(this@NeteasePlaylistDetailActivity, errorMessage, Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun playAllTracks() {
        val tracks = trackAdapter.getAllTracks()
        if (tracks.isEmpty()) {
            Toast.makeText(this, "歌单为空", Toast.LENGTH_SHORT).show()
            return
        }

        // 先将所有歌曲添加到播放列表
        lifecycleScope.launch {
            tracks.forEach { track ->
                val playlistSong = com.tacke.music.data.model.PlaylistSong(
                    id = track.id.toString(),
                    name = track.name,
                    artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                    coverUrl = track.al?.picUrl ?: "",
                    platform = "netease"
                )
                playlistManager.addSong(playlistSong)
            }

            // 播放第一首
            addToNowPlayingAndPlay(tracks[0])
        }
    }

    private fun toggleFavorite(track: PlaylistTrack, isCurrentlyFavorite: Boolean) {
        lifecycleScope.launch {
            try {
                if (isCurrentlyFavorite) {
                    favoriteRepository.removeFromFavorites(track.id.toString())
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "已从我喜欢移除", Toast.LENGTH_SHORT).show()
                } else {
                    val song = Song(
                        index = 0,
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: ""
                    )
                    favoriteRepository.addToFavorites(song, "netease")
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "已添加到我喜欢", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addToNowPlaying(track: PlaylistTrack) {
        lifecycleScope.launch {
            try {
                val playlistSong = com.tacke.music.data.model.PlaylistSong(
                    id = track.id.toString(),
                    name = track.name,
                    artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                    coverUrl = track.al?.picUrl ?: "",
                    platform = "netease"
                )
                playlistManager.addSong(playlistSong)

                // 关键修复：预获取刚添加歌曲的URL并缓存
                // 这样当用户进入播放页时，歌曲的URL已经准备好了
                val cachedRepository = CachedMusicRepository(this@NeteasePlaylistDetailActivity)
                // 获取用户设置的试听音质
                val playbackQuality = SettingsActivity.getPlaybackQuality(this@NeteasePlaylistDetailActivity)
                withContext(Dispatchers.IO) {
                    try {
                        Log.d("NeteasePlaylistDetail", "预获取单曲URL: ${playlistSong.name}")
                        cachedRepository.getSongUrlWithCache(
                            platform = MusicRepository.Platform.NETEASE,
                            songId = playlistSong.id,
                            quality = playbackQuality,
                            songName = playlistSong.name,
                            artists = playlistSong.artists,
                            useCache = true,
                            coverUrlFromSearch = playlistSong.coverUrl
                        )
                        Log.d("NeteasePlaylistDetail", "预获取URL完成: ${playlistSong.name}")
                    } catch (e: Exception) {
                        Log.e("NeteasePlaylistDetail", "预获取URL失败: ${playlistSong.name}, ${e.message}")
                    }
                }

                Toast.makeText(this@NeteasePlaylistDetailActivity, "已添加到播放列表", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addTracksToFavorites(tracks: List<PlaylistTrack>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                tracks.forEach { track ->
                    val song = Song(
                        index = 0,
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: ""
                    )
                    val isAlreadyFavorite = favoriteRepository.isFavorite(track.id.toString())
                    if (!isAlreadyFavorite) {
                        favoriteRepository.addToFavorites(song, "netease")
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }
                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首到喜欢，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到我喜欢"
                }
                Toast.makeText(this@NeteasePlaylistDetailActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addTracksToNowPlaying(tracks: List<PlaylistTrack>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                val addedSongs = mutableListOf<PlaylistSong>()
                tracks.forEach { track ->
                    val playlistSong = com.tacke.music.data.model.PlaylistSong(
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: "",
                        platform = "netease"
                    )
                    val currentList = playlistManager.currentPlaylist.value
                    if (currentList.none { it.id == playlistSong.id }) {
                        playlistManager.addSong(playlistSong)
                        addedSongs.add(playlistSong)
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }

                // 关键修复：预获取第一首新增歌曲的URL并缓存
                // 这样当用户进入播放页时，第一首歌的URL已经准备好了
                if (addedSongs.isNotEmpty()) {
                    val firstSong = addedSongs.first()
                    val cachedRepository = CachedMusicRepository(this@NeteasePlaylistDetailActivity)
                    // 获取用户设置的试听音质
                    val playbackQuality = SettingsActivity.getPlaybackQuality(this@NeteasePlaylistDetailActivity)
                    withContext(Dispatchers.IO) {
                        try {
                            Log.d("NeteasePlaylistDetail", "预获取第一首歌曲URL: ${firstSong.name}")
                            cachedRepository.getSongUrlWithCache(
                                platform = MusicRepository.Platform.NETEASE,
                                songId = firstSong.id,
                                quality = playbackQuality,
                                songName = firstSong.name,
                                artists = firstSong.artists,
                                useCache = true,
                                coverUrlFromSearch = firstSong.coverUrl
                            )
                            Log.d("NeteasePlaylistDetail", "预获取URL完成: ${firstSong.name}")
                        } catch (e: Exception) {
                            Log.e("NeteasePlaylistDetail", "预获取URL失败: ${firstSong.name}, ${e.message}")
                        }
                    }
                }

                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到正在播放列表"
                }
                Toast.makeText(this@NeteasePlaylistDetailActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPlaylistSelectionDialog(track: PlaylistTrack) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@NeteasePlaylistDetailActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(listOf(track))
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@NeteasePlaylistDetailActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addTracksToPlaylist(playlists[which].id, listOf(track))
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(listOf(track))
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showBatchPlaylistSelectionDialog(tracks: List<PlaylistTrack>) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@NeteasePlaylistDetailActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(tracks)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@NeteasePlaylistDetailActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addTracksToPlaylist(playlists[which].id, tracks)
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(tracks)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(tracks: List<PlaylistTrack>) {
        val editText = android.widget.EditText(this).apply {
            hint = "歌单名称"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("新建歌单")
            .setView(editText)
            .setPositiveButton("创建") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val playlist = playlistRepository.createPlaylist(name)
                        addTracksToPlaylist(playlist.id, tracks)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addTracksToPlaylist(playlistId: String, tracks: List<PlaylistTrack>) {
        lifecycleScope.launch {
            try {
                val songList = tracks.map { track ->
                    Song(
                        index = 0,
                        id = track.id.toString(),
                        name = track.name,
                        artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        coverUrl = track.al?.picUrl ?: ""
                    )
                }

                playlistRepository.addSongsToPlaylist(playlistId, songList, "netease")

                Toast.makeText(
                    this@NeteasePlaylistDetailActivity,
                    "已添加 ${tracks.size} 首歌曲到歌单",
                    Toast.LENGTH_SHORT
                ).show()

                if (isMultiSelectMode) {
                    exitMultiSelectMode()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@NeteasePlaylistDetailActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showDownloadQualityDialog(track: PlaylistTrack) {
        val qualities = arrayOf("HR (24bit/96kHz)", "CDQ (16bit/44.1kHz)", "HQ (320kbps)", "LQ (128kbps)")
        val qualityValues = arrayOf("flac24bit", "flac", "320k", "128k")

        MaterialAlertDialogBuilder(this)
            .setTitle("选择音质")
            .setItems(qualities) { _, which ->
                downloadTrack(track, qualityValues[which])
            }
            .show()
    }

    private fun showBatchDownloadQualityDialog(tracks: List<PlaylistTrack>) {
        val qualities = arrayOf("HR (24bit/96kHz)", "CDQ (16bit/44.1kHz)", "HQ (320kbps)", "LQ (128kbps)")
        val qualityValues = arrayOf("flac24bit", "flac", "320k", "128k")

        MaterialAlertDialogBuilder(this)
            .setTitle("批量下载")
            .setItems(qualities) { _, which ->
                batchDownloadTracks(tracks, qualityValues[which])
            }
            .show()
    }

    private fun downloadTrack(track: PlaylistTrack, quality: String) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val platform = MusicRepository.Platform.NETEASE
                val artistName = track.ar?.joinToString(",") { it.name } ?: "未知艺人"
                val coverUrl = track.al?.picUrl ?: ""
                val trackId = track.id.toString()

                // 关键修复：先检查是否已存在更高或相同音质的文件
                val (hasHigherOrEqualQuality, existingFilePath) = withContext(Dispatchers.IO) {
                    com.tacke.music.utils.DownloadQualityChecker.checkExistingDownloadQuality(
                        this@NeteasePlaylistDetailActivity,
                        trackId,
                        quality
                    )
                }

                if (hasHigherOrEqualQuality) {
                    // 已存在更高或相同音质的文件，提示用户
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@NeteasePlaylistDetailActivity,
                        "歌曲《${track.name}》的更高音质或相同音质的文件已存在，请在本地歌曲列表扫描添加！",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // 如果存在低音质文件，删除它
                if (existingFilePath != null) {
                    withContext(Dispatchers.IO) {
                        com.tacke.music.utils.DownloadQualityChecker.deleteExistingFile(existingFilePath)
                        // 同时从下载历史中删除记录
                        com.tacke.music.utils.DownloadQualityChecker.deleteDownloadRecord(this@NeteasePlaylistDetailActivity, trackId)
                    }
                }

                // 非下载管理页面，强制重新获取最新URL，但封面和歌词使用缓存
                val cachedRepository = CachedMusicRepository(this@NeteasePlaylistDetailActivity)
                val detail = withContext(Dispatchers.IO) {
                    cachedRepository.getSongUrlWithCache(
                        platform = platform,
                        songId = trackId,
                        quality = quality,
                        songName = track.name,
                        artists = artistName,
                        useCache = true,
                        coverUrlFromSearch = coverUrl
                    )
                }
                if (detail != null) {
                    val song = Song(
                        index = 0,
                        id = trackId,
                        name = track.name,
                        artists = artistName,
                        coverUrl = detail.cover ?: coverUrl
                    )
                    val downloadManager = DownloadManager.getInstance(this@NeteasePlaylistDetailActivity)
                    val task = downloadManager.createDownloadTask(song, detail, quality, MusicRepository.Platform.NETEASE.name)
                    downloadManager.startDownload(task)
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "开始下载: ${task.fileName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun batchDownloadTracks(tracks: List<PlaylistTrack>, quality: String) {
        val context = applicationContext
        val cachedRepository = CachedMusicRepository(context)
        val dm = DownloadManager.getInstance(context)

        // 关键修复：使用 GlobalScope 确保 Activity 销毁后任务继续执行
        GlobalScope.launch(Dispatchers.IO) {
            var skippedCount = 0
            val tracksToDownload = mutableListOf<PlaylistTrack>()

            // 第一步：检查所有歌曲的音质
            tracks.forEach { track ->
                try {
                    val (hasHigherOrEqualQuality, existingFilePath) = com.tacke.music.utils.DownloadQualityChecker.checkExistingDownloadQuality(
                        context,
                        track.id.toString(),
                        quality
                    )

                    if (hasHigherOrEqualQuality) {
                        // 已存在更高或相同音质的文件，跳过
                        skippedCount++
                    } else {
                        // 可以下载，如果需要则删除旧文件
                        if (existingFilePath != null) {
                            com.tacke.music.utils.DownloadQualityChecker.deleteExistingFile(existingFilePath)
                            // 同时从下载历史中删除记录
                            com.tacke.music.utils.DownloadQualityChecker.deleteDownloadRecord(context, track.id.toString())
                        }
                        tracksToDownload.add(track)
                    }
                } catch (e: Exception) {
                    // 检查失败，默认允许下载
                    tracksToDownload.add(track)
                }
            }

            // 在主线程显示提示
            withContext(Dispatchers.Main) {
                when {
                    skippedCount > 0 && tracksToDownload.isEmpty() -> {
                        // 所有歌曲都跳过
                        Toast.makeText(
                            this@NeteasePlaylistDetailActivity,
                            "${skippedCount} 首歌曲已存在更高音质或相同音质的文件，请在本地歌曲列表扫描添加！",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    skippedCount > 0 -> {
                        // 部分跳过，部分下载
                        Toast.makeText(
                            this@NeteasePlaylistDetailActivity,
                            "已添加 ${tracksToDownload.size} 首歌曲到下载队列，${skippedCount} 首已存在更高音质跳过",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    else -> {
                        // 全部下载
                        Toast.makeText(
                            this@NeteasePlaylistDetailActivity,
                            "已添加 ${tracksToDownload.size} 首歌曲到下载队列",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            // 第二步：下载需要下载的歌曲
            tracksToDownload.forEach { track ->
                launch {
                    try {
                        val platform = MusicRepository.Platform.NETEASE
                        val artistName = track.ar?.joinToString(",") { it.name } ?: "未知艺人"
                        val coverUrl = track.al?.picUrl ?: ""

                        val detail = cachedRepository.getSongUrlWithCache(
                            platform = platform,
                            songId = track.id.toString(),
                            quality = quality,
                            songName = track.name,
                            artists = artistName,
                            useCache = true,
                            coverUrlFromSearch = coverUrl
                        )
                        if (detail != null) {
                            val song = Song(
                                index = 0,
                                id = track.id.toString(),
                                name = track.name,
                                artists = artistName,
                                coverUrl = detail.cover ?: coverUrl
                            )
                            val task = dm.createDownloadTask(song, detail, quality, MusicRepository.Platform.NETEASE.name)
                            dm.startDownload(task)
                        }
                    } catch (e: Exception) {
                        // 忽略异常，继续处理下一首
                    }
                }
            }
        }

        exitMultiSelectMode()
    }

    // 扩展函数：将SongDetailInfo转换为PlaylistTrack
    private fun SongDetailInfo.toPlaylistTrack(): PlaylistTrack {
        val trackArtists = this.artists?.map { artist ->
            TrackArtist(id = artist.id, name = artist.name)
        }
        val trackAlbum = this.album?.let { alb ->
            TrackAlbum(id = alb.id, name = alb.name, picUrl = alb.picUrl)
        }
        return PlaylistTrack(
            id = this.id,
            name = this.name,
            ar = trackArtists,
            al = trackAlbum,
            dt = this.duration
        )
    }

    /**
     * Android 16: 设置 Edge-to-Edge 模式
     * 处理系统栏（状态栏和导航栏）的 insets
     * 为状态栏占位视图设置高度，防止内容被状态栏遮挡
     */
    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // 为状态栏占位视图设置高度
            binding.statusBarPlaceholder?.layoutParams?.height = insets.top
            binding.statusBarPlaceholder?.requestLayout()

            // 为AppBar设置顶部padding，使其延伸到状态栏下方（竖屏布局）
            binding.appBarLayout?.setPadding(0, insets.top, 0, 0)

            // 为底部设置 padding
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }
}
