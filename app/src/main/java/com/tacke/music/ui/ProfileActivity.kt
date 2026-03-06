package com.tacke.music.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloadManager = DownloadManager.getInstance(this)
        playlistRepository = PlaylistRepository(this)
        favoriteRepository = FavoriteRepository(this)

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
        // 每次进入页面时设置随机渐变色
        setupRandomGradientColors()
    }

    /**
     * 为四个卡片设置随机渐变色背景
     */
    private fun setupRandomGradientColors() {
        // 预定义的渐变色组合（排除白色）
        val gradientColors = listOf(
            intArrayOf(Color.parseColor("#FF6B6B"), Color.parseColor("#4ECDC4")), // 红-青
            intArrayOf(Color.parseColor("#667EEA"), Color.parseColor("#764BA2")), // 紫-紫红
            intArrayOf(Color.parseColor("#F093FB"), Color.parseColor("#F5576C")), // 粉-红
            intArrayOf(Color.parseColor("#4FACFE"), Color.parseColor("#00F2FE")), // 蓝-青
            intArrayOf(Color.parseColor("#43E97B"), Color.parseColor("#38F9D7")), // 绿-青
            intArrayOf(Color.parseColor("#FA709A"), Color.parseColor("#FEE140")), // 粉-黄
            intArrayOf(Color.parseColor("#30CFD0"), Color.parseColor("#330867")), // 青-深紫
            intArrayOf(Color.parseColor("#A8EDEA"), Color.parseColor("#FED6E3")), // 浅青-浅粉
            intArrayOf(Color.parseColor("#FF9A9E"), Color.parseColor("#FECFEF")), // 粉-浅粉
            intArrayOf(Color.parseColor("#FFECD2"), Color.parseColor("#FCB69F")), // 浅黄-橙
            intArrayOf(Color.parseColor("#FF8A80"), Color.parseColor("#FFAB91")), // 红-橙
            intArrayOf(Color.parseColor("#B39DDB"), Color.parseColor("#9575CD")), // 浅紫-紫
            intArrayOf(Color.parseColor("#81D4FA"), Color.parseColor("#4FC3F7")), // 浅蓝-蓝
            intArrayOf(Color.parseColor("#A5D6A7"), Color.parseColor("#81C784")), // 浅绿-绿
            intArrayOf(Color.parseColor("#FFF59D"), Color.parseColor("#FFCA28")), // 黄-琥珀
            intArrayOf(Color.parseColor("#FFCC80"), Color.parseColor("#FFB74D")), // 橙-深橙
            intArrayOf(Color.parseColor("#F48FB1"), Color.parseColor("#F06292")), // 粉-深粉
            intArrayOf(Color.parseColor("#CE93D8"), Color.parseColor("#BA68C8")), // 浅紫-紫
            intArrayOf(Color.parseColor("#90CAF9"), Color.parseColor("#64B5F6")), // 浅蓝-蓝
            intArrayOf(Color.parseColor("#80CBC4"), Color.parseColor("#4DB6AC"))  // 青-深青
        )

        // 为每个卡片设置随机渐变色
        val cards = listOf(binding.btnLocalMusic, binding.btnDownloadManager, binding.btnFavorites, binding.btnRecent)

        cards.forEach { card ->
            val randomColors = gradientColors.random()
            val gradientDrawable = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                randomColors
            ).apply {
                cornerRadius = 16f * resources.displayMetrics.density
            }
            card.background = gradientDrawable
        }
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
}
