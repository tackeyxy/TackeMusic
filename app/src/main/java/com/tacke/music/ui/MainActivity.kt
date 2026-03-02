package com.tacke.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.api.ChartType
import com.tacke.music.data.api.HighQualityPlaylist
import com.tacke.music.data.api.PlaylistTag
import com.tacke.music.data.api.RetrofitClient
import com.tacke.music.data.model.ChartSong
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityMainBinding
import com.tacke.music.ui.adapter.PlaylistTagAdapter
import com.tacke.music.ui.adapter.RecommendPlaylistAdapter
import com.tacke.music.ui.adapter.SongAdapter
import com.tacke.music.download.DownloadManager
import com.tacke.music.playlist.PlaylistManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SongAdapter
    private val repository = MusicRepository()
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var playlistManager: PlaylistManager
    private lateinit var currentPlatform: MusicRepository.Platform
    private var currentSongList: MutableList<Song> = mutableListOf()
    private var isMultiSelectMode = false

    // 分页相关
    private var currentPage = 0
    private var currentKeyword = ""
    private var isLoadingMore = false
    private var hasMoreData = true

    // 音乐源映射
    private val platformNames = mapOf(
        MusicRepository.Platform.KUWO to "酷我",
        MusicRepository.Platform.NETEASE to "网易"
    )

    // 榜单数据缓存
    private var chartSongsMap = mutableMapOf<ChartType, List<ChartSong>>()
    private val chartTitles = mapOf(
        ChartType.SOARING to "飙升榜",
        ChartType.NEW to "新歌榜",
        ChartType.ORIGINAL to "原创榜",
        ChartType.HOT to "热歌榜"
    )
    private val chartSubtitles = mapOf(
        ChartType.SOARING to "热度飙升",
        ChartType.NEW to "最新发布",
        ChartType.ORIGINAL to "原创作品",
        ChartType.HOT to "全网热门"
    )

    // 推荐歌单相关
    private lateinit var playlistTagAdapter: PlaylistTagAdapter
    private lateinit var recommendPlaylistAdapter: RecommendPlaylistAdapter
    private var playlistTags: List<PlaylistTag> = emptyList()
    private var currentPlaylistCategory = "全部"
    private var playlistLastTime: Long = 0
    private var isLoadingPlaylists = false
    private var hasMorePlaylists = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentPlatform = SettingsActivity.getDefaultSource(this)
        playlistRepository = PlaylistRepository(this)
        playlistManager = PlaylistManager.getInstance(this)
        setupRecyclerView()
        setupPlaylistRecyclerView()
        setupClickListeners()
        setupBatchActionListeners()
        setupBottomNavigation()
        setupChartCards()
        updateSourceSelectorText()
        loadAllChartData()
        loadPlaylistTags()
    }

    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        val savedSource = SettingsActivity.getDefaultSource(this)
        if (savedSource != currentPlatform) {
            currentPlatform = savedSource
            updateSourceSelectorText()
        }
    }

    private fun updateSourceSelectorText() {
        binding.tvSourceSelector.text = platformNames[currentPlatform] ?: "酷我"
    }

    private fun setupRecyclerView() {
        adapter = SongAdapter(
            onItemClick = { song ->
                if (isMultiSelectMode) {
                    updateBatchActionBar()
                } else {
                    playSong(song)
                }
            },
            onMoreClick = { song ->
                if (!isMultiSelectMode) {
                    showOptionsDialog(song)
                }
            },
            onLongClick = { song ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    true
                } else {
                    false
                }
            }
        )
        binding.rvSongs.layoutManager = LinearLayoutManager(this)
        binding.rvSongs.adapter = adapter

        // 添加滚动监听，实现下拉加载更多
        binding.rvSongs.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (dy > 0 && !isLoadingMore && hasMoreData && currentKeyword.isNotEmpty()) {
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    // 当滚动到最后几个项目时触发加载更多
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 5) {
                        loadMoreSongs()
                    }
                }
            }
        })
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        adapter.setMultiSelectMode(true)
        showBatchActionBar()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        adapter.setMultiSelectMode(false)
        hideBatchActionBar()
    }

    private fun showBatchActionBar() {
        binding.batchActionBar.visibility = View.VISIBLE
        updateBatchActionBar()
    }

    private fun hideBatchActionBar() {
        binding.batchActionBar.visibility = View.GONE
    }

    private fun updateBatchActionBar() {
        val selectedCount = adapter.getSelectedSongs().size
        binding.tvSelectedCount.text = "已选择 $selectedCount 项"
    }

    private fun setupBatchActionListeners() {
        binding.btnSelectAll.setOnClickListener {
            adapter.selectAll()
            updateBatchActionBar()
        }

        binding.btnBatchDownload.setOnClickListener {
            val selectedSongs = adapter.getSelectedSongs()
            if (selectedSongs.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchDownloadQualityDialog(selectedSongs)
        }

        binding.btnAddToPlaylist.setOnClickListener {
            val selectedSongs = adapter.getSelectedSongs()
            if (selectedSongs.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showPlaylistSelectionDialog(selectedSongs)
        }

        binding.btnAddToNowPlaying.setOnClickListener {
            val selectedSongs = adapter.getSelectedSongs()
            if (selectedSongs.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addSongsToNowPlaying(selectedSongs)
        }

        binding.btnCancelBatch.setOnClickListener {
            exitMultiSelectMode()
        }
    }

    private fun setupClickListeners() {
        // 搜索功能
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        // 音乐源选择器点击
        binding.tvSourceSelector.setOnClickListener {
            showSourceSelectorDialog()
        }
    }

    private fun setupChartCards() {
        // 飙升榜 - 点击进入详情
        binding.cardSoaring.setOnClickListener {
            openChartDetail(ChartType.SOARING)
        }

        // 飙升榜前三首歌曲点击播放
        binding.songSoaring1.setOnClickListener {
            playChartSong(ChartType.SOARING, 0)
        }
        binding.songSoaring2.setOnClickListener {
            playChartSong(ChartType.SOARING, 1)
        }
        binding.songSoaring3.setOnClickListener {
            playChartSong(ChartType.SOARING, 2)
        }

        // 新歌榜 - 点击进入详情
        binding.cardNew.setOnClickListener {
            openChartDetail(ChartType.NEW)
        }

        // 新歌榜前三首歌曲点击播放
        binding.songNew1.setOnClickListener {
            playChartSong(ChartType.NEW, 0)
        }
        binding.songNew2.setOnClickListener {
            playChartSong(ChartType.NEW, 1)
        }
        binding.songNew3.setOnClickListener {
            playChartSong(ChartType.NEW, 2)
        }

        // 原创榜 - 点击进入详情
        binding.cardOriginal.setOnClickListener {
            openChartDetail(ChartType.ORIGINAL)
        }

        // 原创榜前三首歌曲点击播放
        binding.songOriginal1.setOnClickListener {
            playChartSong(ChartType.ORIGINAL, 0)
        }
        binding.songOriginal2.setOnClickListener {
            playChartSong(ChartType.ORIGINAL, 1)
        }
        binding.songOriginal3.setOnClickListener {
            playChartSong(ChartType.ORIGINAL, 2)
        }

        // 热歌榜 - 点击进入详情
        binding.cardHot.setOnClickListener {
            openChartDetail(ChartType.HOT)
        }

        // 热歌榜前三首歌曲点击播放
        binding.songHot1.setOnClickListener {
            playChartSong(ChartType.HOT, 0)
        }
        binding.songHot2.setOnClickListener {
            playChartSong(ChartType.HOT, 1)
        }
        binding.songHot3.setOnClickListener {
            playChartSong(ChartType.HOT, 2)
        }
    }

    private fun playChartSong(chartType: ChartType, index: Int) {
        val songs = chartSongsMap[chartType]
        if (songs.isNullOrEmpty() || index >= songs.size) {
            Toast.makeText(this, "歌曲数据加载中，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }

        val song = songs[index]
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val platform = when (song.source.lowercase()) {
                    "kuwo" -> MusicRepository.Platform.KUWO
                    "netease" -> MusicRepository.Platform.NETEASE
                    else -> MusicRepository.Platform.KUWO
                }

                val detail = withContext(Dispatchers.IO) {
                    repository.getSongDetail(platform, song.id, "320k")
                }

                if (detail != null) {
                    PlayerActivity.start(
                        context = this@MainActivity,
                        songId = song.id,
                        songName = song.name,
                        songArtists = song.artist,
                        platform = platform,
                        songUrl = detail.url,
                        songCover = detail.cover,
                        songLyrics = detail.lyrics
                    )
                } else {
                    Toast.makeText(this@MainActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun openChartDetail(chartType: ChartType) {
        ChartDetailActivity.start(
            this,
            chartType,
            chartTitles[chartType] ?: "",
            chartSubtitles[chartType] ?: ""
        )
    }

    private fun loadAllChartData() {
        lifecycleScope.launch {
            ChartType.values().forEach { chartType ->
                launch {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            RetrofitClient.chartApi.getChartList(chartType.value)
                        }
                        if (response.code == 200 && response.data != null) {
                            chartSongsMap[chartType] = response.data
                            updateChartCardPreview(chartType, response.data)
                        }
                    } catch (e: Exception) {
                        // 加载失败，使用默认文本
                    }
                }
            }
        }
    }

    private fun updateChartCardPreview(chartType: ChartType, songs: List<ChartSong>) {
        val top3 = songs.take(3)
        if (top3.isEmpty()) return

        runOnUiThread {
            when (chartType) {
                ChartType.SOARING -> {
                    updateChartCardTexts(
                        binding.cardSoaring,
                        top3
                    )
                }
                ChartType.NEW -> {
                    updateChartCardTexts(
                        binding.cardNew,
                        top3
                    )
                }
                ChartType.ORIGINAL -> {
                    updateChartCardTexts(
                        binding.cardOriginal,
                        top3
                    )
                }
                ChartType.HOT -> {
                    updateChartCardTexts(
                        binding.cardHot,
                        top3
                    )
                }
            }
        }
    }

    private fun updateChartCardTexts(cardView: android.view.View, songs: List<ChartSong>) {
        // 获取卡片内的TextView并更新文本
        val container = cardView as? android.view.ViewGroup ?: return
        val linearLayout = container.getChildAt(0) as? android.view.ViewGroup ?: return

        // 找到包含歌曲列表的LinearLayout（最后一个子View）
        val songListLayout = linearLayout.getChildAt(linearLayout.childCount - 1) as? android.view.ViewGroup
            ?: return

        // 更新前三首歌曲的显示
        for (i in 0 until minOf(3, songs.size)) {
            val songLayout = songListLayout.getChildAt(i) as? android.view.ViewGroup ?: continue
            val textView = songLayout.getChildAt(1) as? android.widget.TextView ?: continue
            textView.text = "${i + 1}. ${songs[i].name}"
        }
    }

    // ==================== 推荐歌单相关方法 ====================

    private fun setupPlaylistRecyclerView() {
        // 设置风格标签RecyclerView
        playlistTagAdapter = PlaylistTagAdapter { tag ->
            currentPlaylistCategory = tag.name
            playlistLastTime = 0
            hasMorePlaylists = true
            recommendPlaylistAdapter.submitList(emptyList())
            loadRecommendPlaylists(tag.name, isLoadMore = false)
        }

        binding.rvPlaylistTags.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvPlaylistTags.adapter = playlistTagAdapter

        // 设置推荐歌单RecyclerView - 使用GridLayoutManager，每行2列
        recommendPlaylistAdapter = RecommendPlaylistAdapter { playlist ->
            openPlaylistDetail(playlist)
        }

        val gridLayoutManager = GridLayoutManager(this, 2)
        binding.rvRecommendPlaylists.layoutManager = gridLayoutManager
        binding.rvRecommendPlaylists.adapter = recommendPlaylistAdapter

        // 添加滚动监听，实现加载更多
        binding.rvRecommendPlaylists.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (dy > 0 && !isLoadingPlaylists && hasMorePlaylists) {
                    val layoutManager = recyclerView.layoutManager as GridLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    // 当滚动到最后几个项目时触发加载更多
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 4) {
                        loadMorePlaylists()
                    }
                }
            }
        })
    }

    private fun loadPlaylistTags() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.playlistApi.getPlaylistTags()
                }
                if (response.code == 200 && response.tags != null) {
                    // 添加"全部"标签到列表开头，显示所有标签
                    val allTag = PlaylistTag(0, "全部", 0, 0)
                    playlistTags = listOf(allTag) + response.tags
                    playlistTagAdapter.submitList(playlistTags)

                    // 默认加载全部歌单
                    loadRecommendPlaylists("全部", isLoadMore = false)
                }
            } catch (e: Exception) {
                // 加载失败时使用默认标签
                val defaultTags = listOf(
                    PlaylistTag(0, "全部", 0, 0),
                    PlaylistTag(1, "华语", 1, 0),
                    PlaylistTag(2, "欧美", 1, 0),
                    PlaylistTag(3, "电子", 1, 0),
                    PlaylistTag(4, "轻音乐", 1, 0),
                    PlaylistTag(5, "古风", 1, 0)
                )
                playlistTags = defaultTags
                playlistTagAdapter.submitList(defaultTags)
                loadRecommendPlaylists("全部", isLoadMore = false)
            }
        }
    }

    private fun loadRecommendPlaylists(category: String, isLoadMore: Boolean = false) {
        if (isLoadingPlaylists) return

        isLoadingPlaylists = true
        if (isLoadMore) {
            binding.progressBarPlaylistLoadMore.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.playlistApi.getHighQualityPlaylists(
                        category = category,
                        limit = 10,
                        before = if (isLoadMore) playlistLastTime else 0
                    )
                }

                if (response.code == 200 && response.playlists != null) {
                    if (isLoadMore) {
                        recommendPlaylistAdapter.addPlaylists(response.playlists)
                    } else {
                        recommendPlaylistAdapter.submitList(response.playlists)
                    }

                    // 更新最后时间戳，用于分页
                    playlistLastTime = response.lasttime
                    hasMorePlaylists = response.more
                } else {
                    hasMorePlaylists = false
                }
            } catch (e: Exception) {
                hasMorePlaylists = false
                if (!isLoadMore) {
                    Toast.makeText(this@MainActivity, "加载歌单失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoadingPlaylists = false
                binding.progressBarPlaylistLoadMore.visibility = View.GONE
            }
        }
    }

    private fun loadMorePlaylists() {
        if (isLoadingPlaylists || !hasMorePlaylists) return
        loadRecommendPlaylists(currentPlaylistCategory, isLoadMore = true)
    }

    private fun openPlaylistDetail(playlist: HighQualityPlaylist) {
        // 跳转到网易云歌单详情页
        NeteasePlaylistDetailActivity.start(
            this,
            playlist.id,
            playlist.name,
            playlist.coverImgUrl ?: ""
        )
    }

    private fun showSourceSelectorDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_source_selector, null)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Translucent_NoTitleBar)
            .setView(dialogView)
            .create()

        // 设置对话框背景透明
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 设置对话框宽度为屏幕宽度的80%
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.8).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        // 获取视图引用
        val cardKuwo = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardKuwo)
        val cardNetease = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardNetease)
        val ivCheckKuwo = dialogView.findViewById<android.widget.ImageView>(R.id.ivCheckKuwo)
        val ivCheckNetease = dialogView.findViewById<android.widget.ImageView>(R.id.ivCheckNetease)

        // 根据当前选择显示选中状态
        when (currentPlatform) {
            MusicRepository.Platform.KUWO -> {
                cardKuwo.setCardBackgroundColor(getColor(R.color.primary_light))
                ivCheckKuwo.visibility = View.VISIBLE
                cardNetease.setCardBackgroundColor(getColor(R.color.surface_light))
                ivCheckNetease.visibility = View.GONE
            }
            MusicRepository.Platform.NETEASE -> {
                cardKuwo.setCardBackgroundColor(getColor(R.color.surface_light))
                ivCheckKuwo.visibility = View.GONE
                cardNetease.setCardBackgroundColor(getColor(R.color.primary_light))
                ivCheckNetease.visibility = View.VISIBLE
            }
        }

        // 设置点击事件
        cardKuwo.setOnClickListener {
            currentPlatform = MusicRepository.Platform.KUWO
            updateSourceSelectorText()
            dialog.dismiss()
        }

        cardNetease.setOnClickListener {
            currentPlatform = MusicRepository.Platform.NETEASE
            updateSourceSelectorText()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupBottomNavigation() {
        binding.navHome.setOnClickListener {
            updateNavSelection(0)
            showHomeContent()
        }

        binding.navDiscover.setOnClickListener {
            updateNavSelection(1)
            // 跳转到正在播放页面 - 支持空状态进入
            PlayerActivity.startEmpty(this)
        }

        binding.navProfile.setOnClickListener {
            updateNavSelection(2)
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    private fun updateNavSelection(index: Int) {
        // 更新导航栏选中状态
        val selectedColor = getColor(R.color.primary)
        val unselectedColor = getColor(R.color.text_secondary)

        binding.tvNavHome.setTextColor(if (index == 0) selectedColor else unselectedColor)
        binding.tvNavDiscover.setTextColor(if (index == 1) selectedColor else unselectedColor)
        binding.tvNavProfile.setTextColor(if (index == 2) selectedColor else unselectedColor)

        binding.ivNavHome.isSelected = index == 0
        binding.ivNavDiscover.isSelected = index == 1
        binding.ivNavProfile.isSelected = index == 2
    }

    private fun showHomeContent() {
        // 显示首页内容
        binding.scrollView.visibility = View.VISIBLE
        binding.rvSongs.visibility = View.GONE
    }

    private fun showSearchResults() {
        // 显示搜索结果
        binding.scrollView.visibility = View.GONE
        binding.rvSongs.visibility = View.VISIBLE
        // 确保 RecyclerView 可以正确显示
        binding.rvSongs.post {
            adapter.notifyDataSetChanged()
        }
    }

    private fun performSearch() {
        val keyword = binding.etSearch.text.toString().trim()
        if (keyword.isNotEmpty()) {
            // 重置分页状态
            currentPage = 0
            currentKeyword = keyword
            hasMoreData = true
            currentSongList.clear()

            // 隐藏软键盘
            hideKeyboard()
            searchMusic(keyword, isLoadMore = false)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun searchMusic(keyword: String, isLoadMore: Boolean = false) {
        if (isLoadMore) {
            isLoadingMore = true
            binding.progressBarLoadMore.visibility = View.VISIBLE
        } else {
            binding.progressBar.visibility = View.VISIBLE
            showSearchResults()
        }

        lifecycleScope.launch {
            try {
                val songs = withContext(Dispatchers.IO) {
                    repository.searchMusic(currentPlatform, keyword, currentPage)
                }

                if (songs.isNotEmpty()) {
                    if (isLoadMore) {
                        // 加载更多，追加到列表
                        currentSongList.addAll(songs)
                        adapter.addSongs(songs)
                    } else {
                        // 新搜索，替换列表
                        currentSongList.addAll(songs)
                        adapter.submitList(currentSongList.toList())
                    }

                    // 如果返回的歌曲数量少于页面大小，说明没有更多数据了
                    hasMoreData = songs.size >= MusicRepository.PAGE_SIZE
                } else {
                    hasMoreData = false
                    if (!isLoadMore) {
                        Toast.makeText(this@MainActivity, R.string.no_songs_found, Toast.LENGTH_SHORT).show()
                        showHomeContent()
                    }
                }
            } catch (e: Exception) {
                hasMoreData = false
                if (!isLoadMore) {
                    Toast.makeText(this@MainActivity, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    showHomeContent()
                }
            } finally {
                if (isLoadMore) {
                    isLoadingMore = false
                    binding.progressBarLoadMore.visibility = View.GONE
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun loadMoreSongs() {
        if (isLoadingMore || !hasMoreData || currentKeyword.isEmpty()) return

        currentPage++
        searchMusic(currentKeyword, isLoadMore = true)
    }

    private fun playSong(song: Song) {
        // 先获取歌曲详情，成功后再跳转
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    repository.getSongDetail(currentPlatform, song.id, "320k")
                }
                if (detail != null) {
                    // 获取详情成功，跳转到播放页
                    PlayerActivity.start(
                        context = this@MainActivity,
                        songId = song.id,
                        songName = song.name,
                        songArtists = song.artists,
                        platform = currentPlatform,
                        songUrl = detail.url,
                        songCover = detail.cover,
                        songLyrics = detail.lyrics
                    )
                } else {
                    Toast.makeText(this@MainActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun showOptionsDialog(song: Song) {
        val options = arrayOf("播放", "下载", "添加到歌单", "添加到正在播放")
        AlertDialog.Builder(this)
            .setTitle("${song.name} - ${song.artists}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playSong(song)
                    1 -> showQualityDialog(song)
                    2 -> showPlaylistSelectionDialog(listOf(song))
                    3 -> addToNowPlaying(song)
                }
            }
            .show()
    }

    private fun addToNowPlaying(song: Song) {
        lifecycleScope.launch {
            try {
                val playlistSong = playlistManager.convertToPlaylistSong(song, currentPlatform)
                playlistManager.addSong(playlistSong)
                Toast.makeText(
                    this@MainActivity,
                    "已添加到正在播放列表",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showQualityDialog(song: Song) {
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

        val ivCheckHR = dialogView.findViewById<android.widget.ImageView>(R.id.ivCheckHR)
        val ivCheckCDQ = dialogView.findViewById<android.widget.ImageView>(R.id.ivCheckCDQ)
        val ivCheckHQ = dialogView.findViewById<android.widget.ImageView>(R.id.ivCheckHQ)
        val ivCheckLQ = dialogView.findViewById<android.widget.ImageView>(R.id.ivCheckLQ)

        // 默认选中 HQ (320k)
        cardHQ.setCardBackgroundColor(getColor(R.color.primary_light))
        ivCheckHQ.visibility = android.view.View.VISIBLE

        // 设置点击事件 - 点击后直接下载
        cardHR.setOnClickListener {
            downloadSong(song, "flac24bit")
            dialog.dismiss()
        }

        cardCDQ.setOnClickListener {
            downloadSong(song, "flac")
            dialog.dismiss()
        }

        cardHQ.setOnClickListener {
            downloadSong(song, "320k")
            dialog.dismiss()
        }

        cardLQ.setOnClickListener {
            downloadSong(song, "128k")
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun downloadSong(song: Song, quality: String) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    repository.getSongDetail(currentPlatform, song.id, quality)
                }
                if (detail != null) {
                    val downloadManager = DownloadManager.getInstance(this@MainActivity)
                    val task = downloadManager.createDownloadTask(song, detail, quality)
                    downloadManager.startDownload(task)
                    Toast.makeText(this@MainActivity, "开始下载: ${task.fileName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, R.string.download_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun showBatchDownloadQualityDialog(songs: List<Song>) {
        val qualities = arrayOf("HR (24bit/96kHz)", "CDQ (16bit/44.1kHz)", "HQ (320kbps)", "LQ (128kbps)")
        val qualityValues = arrayOf("flac24bit", "flac", "320k", "128k")

        MaterialAlertDialogBuilder(this)
            .setTitle("批量下载")
            .setItems(qualities) { _, which ->
                batchDownloadSongs(songs, qualityValues[which])
            }
            .show()
    }

    private fun batchDownloadSongs(songs: List<Song>, quality: String) {
        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0

            songs.forEach { song ->
                try {
                    val detail = withContext(Dispatchers.IO) {
                        repository.getSongDetail(currentPlatform, song.id, quality)
                    }
                    if (detail != null) {
                        val downloadManager = DownloadManager.getInstance(this@MainActivity)
                        val task = downloadManager.createDownloadTask(song, detail, quality)
                        downloadManager.startDownload(task)
                        successCount++
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    failCount++
                }
            }

            Toast.makeText(
                this@MainActivity,
                "批量下载开始: 成功 $successCount 首, 失败 $failCount 首",
                Toast.LENGTH_LONG
            ).show()

            exitMultiSelectMode()
        }
    }

    private fun showPlaylistSelectionDialog(songs: List<Song>) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(songs)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addSongsToPlaylist(playlists[which].id, songs)
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(songs)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(songs: List<Song>) {
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
                        addSongsToPlaylist(playlist.id, songs)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addSongsToPlaylist(playlistId: String, songs: List<Song>) {
        lifecycleScope.launch {
            try {
                val platform = when (currentPlatform) {
                    MusicRepository.Platform.KUWO -> "kuwo"
                    MusicRepository.Platform.NETEASE -> "netease"
                }
                playlistRepository.addSongsToPlaylist(playlistId, songs, platform)
                Toast.makeText(
                    this@MainActivity,
                    "已添加 ${songs.size} 首歌曲到歌单",
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun addSongsToNowPlaying(songs: List<Song>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                songs.forEach { song ->
                    val playlistSong = playlistManager.convertToPlaylistSong(song, currentPlatform)
                    val currentList = playlistManager.currentPlaylist.value
                    if (currentList.none { it.id == playlistSong.id }) {
                        playlistManager.addSong(playlistSong)
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }
                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到正在播放列表"
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
