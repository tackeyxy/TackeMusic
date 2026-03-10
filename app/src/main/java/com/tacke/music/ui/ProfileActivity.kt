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
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityProfileBinding
import com.tacke.music.download.DownloadManager
import com.tacke.music.ui.adapter.PlaylistListAdapter
import kotlinx.coroutines.launch
import kotlin.random.Random

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var favoriteRepository: FavoriteRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16: 适配 Edge-to-Edge 模式
        setupEdgeToEdge()

        downloadManager = DownloadManager.getInstance(this)
        playlistRepository = PlaylistRepository(this)
        favoriteRepository = FavoriteRepository(this)

        setupCardBackgrounds()
        setupClickListeners()
        setupBottomNavigation()
        setupPlaylistRecyclerView()
        observeDownloadCount()
        observePlaylistCount()
        observePlaylists()
        observeFavoriteCount()
    }

    override fun onResume() {
        super.onResume()
        refreshPlaylistList()
        // 每次进入页面时刷新卡片背景色
        setupCardBackgrounds()
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
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnLocalMusic.setOnClickListener {
            Toast.makeText(this, "本地音乐功能开发中", Toast.LENGTH_SHORT).show()
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
        binding.navHome.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.navDiscover.setOnClickListener {
            PlayerActivity.startEmpty(this)
        }

        binding.navProfile.setOnClickListener {
            // 当前页面
        }
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
                downloadManager.completedTasks.collect { tasks ->
                    binding.tvDownloadCount.text = "${tasks.size}首"
                }
            }
        }
    }

    private fun observePlaylistCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistRepository.getAllPlaylists().collect { playlists ->
                    binding.tvLocalMusicCount.text = "${playlists.size}个歌单"
                }
            }
        }
    }

    private fun observePlaylists() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistRepository.getAllPlaylists().collect { playlists ->
                    allPlaylists = playlists
                    val displayList = if (isShowingAllPlaylists) playlists else emptyList()
                    val newList = ArrayList(displayList)
                    playlistAdapter.submitList(newList)
                }
            }
        }
    }

    private fun observeFavoriteCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                favoriteRepository.getFavoriteCount().collect { count ->
                    binding.tvFavoriteCount.text = "${count}首"
                }
            }
        }
    }

    /**
     * Android 16: 设置 Edge-to-Edge 模式
     * 处理系统栏（状态栏和导航栏）的 insets
     * 注意：布局中已添加 fitsSystemWindows="true"，这里处理额外的 insets 需求
     */
    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 只为底部设置 padding，顶部由 fitsSystemWindows 处理
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }
}
