package com.tacke.music.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.model.Playlist
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.ListCoverRepairManager
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityProfileBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.ui.adapter.PlaylistListAdapter
import com.tacke.music.util.NavigationHelper
import kotlinx.coroutines.launch
import kotlin.random.Random

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var listCoverRepairManager: ListCoverRepairManager
    private lateinit var playlistAdapter: PlaylistListAdapter
    private var isShowingAllPlaylists = true
    private var allPlaylists: List<com.tacke.music.data.model.Playlist> = emptyList()

    // 预定义的渐变色组合（起始色，结束色）
    private val gradientColors = listOf(
        Pair(0xFF5B6B8C.toInt(), 0xFF4A5568.toInt()), // 蓝灰
        Pair(0xFF38A169.toInt(), 0xFF2F855A.toInt()), // 绿色
        Pair(0xFFE53E3E.toInt(), 0xFFC53030.toInt()), // 红色
        Pair(0xFF6B5B95.toInt(), 0xFF5A4A7A.toInt()), // 紫色
        Pair(0xFF3182CE.toInt(), 0xFF2B6CB0.toInt()), // 蓝色
        Pair(0xFFD69E2E.toInt(), 0xFFB7791F.toInt()), // 黄色
        Pair(0xFFDD6B20.toInt(), 0xFFC05621.toInt()), // 橙色
        Pair(0xFF38B2AC.toInt(), 0xFF319795.toInt()), // 青色
        Pair(0xFF805AD5.toInt(), 0xFF6B46C1.toInt()), // 紫罗兰
        Pair(0xFFE53E8C.toInt(), 0xFFD53F8C.toInt()), // 粉色
        Pair(0xFF00A3C4.toInt(), 0xFF0987A0.toInt()), // 天蓝
        Pair(0xFF9F7AEA.toInt(), 0xFF805AD5.toInt()), // 浅紫
        Pair(0xFFF56565.toInt(), 0xFFE53E3E.toInt()), // 浅红
        Pair(0xFF48BB78.toInt(), 0xFF38A169.toInt()), // 浅绿
        Pair(0xFF4299E1.toInt(), 0xFF3182CE.toInt()), // 浅蓝
        Pair(0xFFED8936.toInt(), 0xFFDD6B20.toInt()), // 浅橙
        Pair(0xFF0BC5EA.toInt(), 0xFF00A3C4.toInt()), // 青蓝
        Pair(0xFF9F7AEA.toInt(), 0xFF6B46C1.toInt()), // 深紫
        Pair(0xFFF687B3.toInt(), 0xFFD53F8C.toInt()), // 桃粉
        Pair(0xFF4FD1C7.toInt(), 0xFF38B2AC.toInt())  // 薄荷绿
    )

    private var lastNavClickTime = 0L
    private val NAV_CLICK_DEBOUNCE = 500L // 500ms 防抖

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityProfileBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Android 16: 适配 Edge-to-Edge 模式
            setupEdgeToEdge()

            downloadManager = DownloadManager.getInstance(this)
            playlistRepository = PlaylistRepository(this)
            favoriteRepository = FavoriteRepository(this)
            listCoverRepairManager = ListCoverRepairManager.getInstance(this)

            setupCardBackgrounds()
            setupClickListeners()
            setupBottomNavigation()
            setupPlaylistRecyclerView()
            observeDownloadCount()
            observePlaylistCount()
            observePlaylists()
            observeFavoriteCount()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "页面加载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 确保 binding 已初始化
        if (::binding.isInitialized) {
            refreshPlaylistList()
            // 每次进入页面时刷新卡片背景色
            setupCardBackgrounds()
            listCoverRepairManager.repairPlaylistsAsync()
        }
    }

    private fun setupCardBackgrounds() {
        // 随机选择4个不同的渐变色
        val selectedColors = gradientColors.shuffled().take(4)

        // 本地音乐卡片
        val localMusicGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(selectedColors[0].first, selectedColors[0].second)
        )
        localMusicGradient.cornerRadius = 16f * resources.displayMetrics.density
        binding.btnLocalMusic.background = localMusicGradient

        // 下载管理卡片
        val downloadGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(selectedColors[1].first, selectedColors[1].second)
        )
        downloadGradient.cornerRadius = 16f * resources.displayMetrics.density
        binding.btnDownloadManager.background = downloadGradient

        // 我喜欢的卡片
        val favoriteGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(selectedColors[2].first, selectedColors[2].second)
        )
        favoriteGradient.cornerRadius = 16f * resources.displayMetrics.density
        binding.btnFavorites.background = favoriteGradient

        // 播放历史卡片
        val historyGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(selectedColors[3].first, selectedColors[3].second)
        )
        historyGradient.cornerRadius = 16f * resources.displayMetrics.density
        binding.btnRecent.background = historyGradient
    }

    private fun setupClickListeners() {
        // 设置按钮只在竖屏布局中存在
        binding.btnSettings?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnLocalMusic.setOnClickListener {
            LocalMusicActivity.start(this)
        }

        binding.btnDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadActivity::class.java))
        }

        binding.btnFavorites.setOnClickListener {
            FavoriteSongsActivity.start(this)
        }

        binding.btnRecent.setOnClickListener {
            startActivity(Intent(this, RecentPlayActivity::class.java))
        }

        binding.btnAddPlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }

        binding.btnExpandAllPlaylists.setOnClickListener {
            togglePlaylistDisplay()
        }
    }

    private fun showCreatePlaylistDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_playlist, null)
        val etName = dialogView.findViewById<EditText>(R.id.etPlaylistName)

        MaterialAlertDialogBuilder(this)
            .setTitle("新建歌单")
            .setView(dialogView)
            .setPositiveButton("创建") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isNotEmpty()) {
                    createPlaylist(name)
                } else {
                    Toast.makeText(this, "歌单名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createPlaylist(name: String) {
        lifecycleScope.launch {
            try {
                playlistRepository.createPlaylist(name)
                Toast.makeText(this@ProfileActivity, "歌单创建成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ProfileActivity, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePlaylistDisplay() {
        isShowingAllPlaylists = !isShowingAllPlaylists

        val rotation = if (isShowingAllPlaylists) 0f else 180f
        binding.btnExpandAllPlaylists.animate()
            .rotation(rotation)
            .setDuration(200)
            .start()

        val displayList = if (isShowingAllPlaylists) {
            allPlaylists
        } else {
            emptyList()
        }
        playlistAdapter.submitList(displayList)

        val message = if (isShowingAllPlaylists) "已展开全部歌单" else "已隐藏全部歌单"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupBottomNavigation() {
        // 竖屏底部导航栏（横屏模式下可能不存在）
        binding.navHome?.setOnClickListener {
            if (isNavClickValid()) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
        }

        binding.navDiscover?.setOnClickListener {
            if (isNavClickValid()) {
                PlayerActivity.startEmpty(this)
            }
        }

        binding.navProfile?.setOnClickListener {
            // 当前页面，无需处理
        }

        // 横屏左侧导航栏（如果存在）
        setupSideNavigation()
    }

    private fun setupSideNavigation() {
        // 检查是否存在左侧导航栏（横屏模式）
        val sideNavContainer = binding.root.findViewById<android.view.View>(R.id.sideNavContainer)
        if (sideNavContainer != null) {
            val navHelper = NavigationHelper(this)
            navHelper.setupSideNavigation(
                navHome = sideNavContainer.findViewById(R.id.navHome),
                navDiscover = sideNavContainer.findViewById(R.id.navDiscover),
                navProfile = sideNavContainer.findViewById(R.id.navProfile),
                navSettings = sideNavContainer.findViewById(R.id.navSettings),
                ivNavHome = sideNavContainer.findViewById(R.id.ivNavHome),
                ivNavDiscover = sideNavContainer.findViewById(R.id.ivNavDiscover),
                ivNavProfile = sideNavContainer.findViewById(R.id.ivNavProfile),
                tvNavHome = sideNavContainer.findViewById(R.id.tvNavHome),
                tvNavDiscover = sideNavContainer.findViewById(R.id.tvNavDiscover),
                tvNavProfile = sideNavContainer.findViewById(R.id.tvNavProfile),
                currentNavIndex = 2 // 我的页面
            )
        }
    }

    private fun isNavClickValid(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNavClickTime > NAV_CLICK_DEBOUNCE) {
            lastNavClickTime = currentTime
            return true
        }
        return false
    }

    private fun setupPlaylistRecyclerView() {
        playlistAdapter = PlaylistListAdapter(
            onItemClick = { playlist ->
                val intent = Intent(this, PlaylistDetailActivity::class.java).apply {
                    putExtra("playlist_id", playlist.id)
                    putExtra("playlist_name", playlist.name)
                }
                startActivity(intent)
            },
            onItemLongClick = { playlist ->
                showPlaylistOptionsDialog(playlist)
            },
            onMoreClick = { playlist ->
                showPlaylistOptionsDialog(playlist)
            }
        )

        binding.rvPlaylists.apply {
            layoutManager = LinearLayoutManager(this@ProfileActivity)
            adapter = playlistAdapter
        }
    }

    private fun showPlaylistOptionsDialog(playlist: Playlist) {
        val options = arrayOf("编辑歌单", "删除歌单")

        MaterialAlertDialogBuilder(this)
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditPlaylistDialog(playlist)
                    1 -> showDeletePlaylistConfirm(playlist)
                }
            }
            .show()
    }

    private fun showEditPlaylistDialog(playlist: Playlist) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_playlist, null)
        val etName = dialogView.findViewById<EditText>(R.id.etPlaylistName)

        etName.setText(playlist.name)
        etName.setSelection(playlist.name.length)

        MaterialAlertDialogBuilder(this)
            .setTitle("编辑歌单")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isNotEmpty()) {
                    updatePlaylistName(playlist, newName)
                } else {
                    Toast.makeText(this, "歌单名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updatePlaylistName(playlist: Playlist, newName: String) {
        lifecycleScope.launch {
            try {
                playlistRepository.updatePlaylist(playlist.copy(name = newName))
                Toast.makeText(this@ProfileActivity, "歌单更新成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ProfileActivity, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshPlaylistList() {
        // 检查 adapter 是否已初始化
        if (!::playlistAdapter.isInitialized) {
            return
        }
        val displayList = if (isShowingAllPlaylists) allPlaylists else emptyList()
        val newList = ArrayList(displayList)
        playlistAdapter.submitList(newList)
    }

    private fun showDeletePlaylistConfirm(playlist: Playlist) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除歌单")
            .setMessage("确定要删除歌单\"${playlist.name}\"吗？歌单内的歌曲也会被删除。")
            .setPositiveButton("删除") { _, _ ->
                deletePlaylist(playlist)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deletePlaylist(playlist: Playlist) {
        lifecycleScope.launch {
            try {
                playlistRepository.deletePlaylist(playlist.id)
                Toast.makeText(this@ProfileActivity, "歌单已删除", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ProfileActivity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeDownloadCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    downloadManager.completedTasks.collect { tasks ->
                        binding.tvDownloadCount?.text = "${tasks.size}首"
                    }
                } catch (e: Exception) {
                    binding.tvDownloadCount?.text = "0首"
                }
            }
        }
    }

    private fun observePlaylistCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    playlistRepository.getAllPlaylists().collect { playlists ->
                        binding.tvLocalMusicCount?.text = "${playlists.size}个歌单"
                    }
                } catch (e: Exception) {
                    binding.tvLocalMusicCount?.text = "0个歌单"
                }
            }
        }
    }

    private fun observePlaylists() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    playlistRepository.getAllPlaylists().collect { playlists ->
                        allPlaylists = playlists
                        val displayList = if (isShowingAllPlaylists) playlists else emptyList()
                        val newList = ArrayList(displayList)
                        playlistAdapter.submitList(newList)
                    }
                } catch (e: Exception) {
                    allPlaylists = emptyList()
                    playlistAdapter.submitList(emptyList())
                }
            }
        }
    }

    private fun observeFavoriteCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    favoriteRepository.getFavoriteCount().collect { count ->
                        binding.tvFavoriteCount?.text = "${count}首"
                    }
                } catch (e: Exception) {
                    binding.tvFavoriteCount?.text = "0首"
                }
            }
        }
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

            // 为底部设置 padding
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }
}
