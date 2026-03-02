package com.tacke.music.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityPlaylistDetailBinding
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.PlaylistSongAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var playlistManager: PlaylistManager
    private lateinit var songAdapter: PlaylistSongAdapter

    private var playlistId: String = ""
    private var playlistName: String = ""
    private var isMultiSelectMode = false
    private val selectedSongs = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistId = intent.getStringExtra("playlist_id") ?: ""
        playlistName = intent.getStringExtra("playlist_name") ?: ""

        if (playlistId.isEmpty()) {
            Toast.makeText(this, "歌单不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        playlistRepository = PlaylistRepository(this)
        playlistManager = PlaylistManager.getInstance(this)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        observeSongs()
    }

    private fun setupUI() {
        binding.tvTitle.text = playlistName
        binding.tvPlaylistName.text = playlistName
    }

    private fun setupRecyclerView() {
        songAdapter = PlaylistSongAdapter(
            onItemClick = { song ->
                if (isMultiSelectMode) {
                    toggleSelection(song.id)
                } else {
                    playSong(song)
                }
            },
            onMoreClick = { song ->
                if (!isMultiSelectMode) {
                    showSongOptions(song)
                }
            },
            onLongClick = { song ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    toggleSelection(song.id)
                    true
                } else {
                    false
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = songAdapter
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            if (isMultiSelectMode) {
                exitMultiSelectMode()
            } else {
                finish()
            }
        }

        binding.btnMore.setOnClickListener {
            showPlaylistOptions()
        }

        binding.btnPlayAll.setOnClickListener {
            Toast.makeText(this, "播放全部", Toast.LENGTH_SHORT).show()
        }

        binding.btnBatchManage.setOnClickListener {
            enterMultiSelectMode()
        }

        binding.btnSelectAll.setOnClickListener {
            if (selectedSongs.size == songAdapter.itemCount) {
                selectedSongs.clear()
            } else {
                selectedSongs.clear()
                songAdapter.getAllSongs().forEach { selectedSongs.add(it.id) }
            }
            songAdapter.setSelectedItems(selectedSongs)
            updateSelectedCount()
        }

        binding.btnAddToNowPlaying.setOnClickListener {
            val selectedSongList = songAdapter.getAllSongs().filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isNotEmpty()) {
                addSongsToNowPlaying(selectedSongList)
            }
            exitMultiSelectMode()
        }

        binding.btnDelete.setOnClickListener {
            showDeleteConfirm()
        }

        binding.btnCancel.setOnClickListener {
            exitMultiSelectMode()
        }
    }

    private fun observeSongs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistRepository.getSongsInPlaylist(playlistId).collect { songs ->
                    songAdapter.submitList(songs)
                    binding.tvSongCount.text = "歌曲列表"
                    updateEmptyState(songs.isEmpty())

                    if (isMultiSelectMode) {
                        val validSelections = selectedSongs.intersect(songs.map { it.id }.toSet())
                        if (validSelections.size != selectedSongs.size) {
                            selectedSongs.clear()
                            selectedSongs.addAll(validSelections)
                            songAdapter.setSelectedItems(selectedSongs)
                            updateSelectedCount()
                        }
                    }
                }
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        binding.batchActionBar.visibility = View.VISIBLE
        binding.btnBatchManage.visibility = View.GONE
        songAdapter.setMultiSelectMode(true)
        selectedSongs.clear()
        updateSelectedCount()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        binding.batchActionBar.visibility = View.GONE
        binding.btnBatchManage.visibility = View.VISIBLE
        songAdapter.setMultiSelectMode(false)
        selectedSongs.clear()
    }

    private fun toggleSelection(songId: String) {
        if (selectedSongs.contains(songId)) {
            selectedSongs.remove(songId)
        } else {
            selectedSongs.add(songId)
        }
        songAdapter.setSelectedItems(selectedSongs)
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        binding.tvSelectedCount.text = "已选择 ${selectedSongs.size} 项"
    }

    private fun showSongOptions(song: PlaylistSong) {
        val options = arrayOf("播放", "从歌单中删除")

        MaterialAlertDialogBuilder(this)
            .setTitle(song.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playSong(song)
                    1 -> removeSongFromPlaylist(song.id)
                }
            }
            .show()
    }

    private fun showPlaylistOptions() {
        val options = arrayOf("重命名", "清空歌单")

        MaterialAlertDialogBuilder(this)
            .setTitle(playlistName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog()
                    1 -> showClearConfirm()
                }
            }
            .show()
    }

    private fun showRenameDialog() {
        val editText = android.widget.EditText(this).apply {
            setText(playlistName)
            setSelection(playlistName.length)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("重命名歌单")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    renamePlaylist(newName)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renamePlaylist(newName: String) {
        lifecycleScope.launch {
            try {
                val playlist = playlistRepository.getPlaylistById(playlistId)
                playlist?.let {
                    playlistRepository.updatePlaylist(it.copy(name = newName))
                    playlistName = newName
                    binding.tvTitle.text = newName
                    binding.tvPlaylistName.text = newName
                    Toast.makeText(this@PlaylistDetailActivity, "重命名成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "重命名失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showClearConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空歌单")
            .setMessage("确定要清空歌单中的所有歌曲吗？")
            .setPositiveButton("清空") { _, _ ->
                clearPlaylist()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearPlaylist() {
        lifecycleScope.launch {
            try {
                val songs = playlistRepository.getSongsInPlaylist(playlistId)
                songs.collect { songList ->
                    songList.forEach { song ->
                        playlistRepository.removeSongFromPlaylist(playlistId, song.id)
                    }
                }
                Toast.makeText(this@PlaylistDetailActivity, "歌单已清空", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "清空失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除歌曲")
            .setMessage("确定要从歌单中删除选中的 ${selectedSongs.size} 首歌曲吗？")
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedSongs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedSongs() {
        lifecycleScope.launch {
            try {
                selectedSongs.forEach { songId ->
                    playlistRepository.removeSongFromPlaylist(playlistId, songId)
                }
                exitMultiSelectMode()
                Toast.makeText(this@PlaylistDetailActivity, "已删除 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeSongFromPlaylist(songId: String) {
        lifecycleScope.launch {
            try {
                playlistRepository.removeSongFromPlaylist(playlistId, songId)
                Toast.makeText(this@PlaylistDetailActivity, "已从歌单中删除", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playSong(song: PlaylistSong) {
        lifecycleScope.launch {
            try {
                val platform = try {
                    MusicRepository.Platform.valueOf(song.platform)
                } catch (e: Exception) {
                    MusicRepository.Platform.KUWO
                }

                // 添加到播放列表
                playlistManager.addSong(song)

                // 检查本地文件是否存在
                val downloadManager = com.tacke.music.download.DownloadManager.getInstance(this@PlaylistDetailActivity)
                val localPath = withContext(Dispatchers.IO) {
                    downloadManager.getLocalSongPath(song.id)
                }

                // 获取歌曲详情（用于歌词和封面）
                val detail = withContext(Dispatchers.IO) {
                    MusicRepository().getSongDetail(platform, song.id, "320k")
                }

                if (detail != null) {
                    // 优先使用本地文件路径，如果没有则使用在线URL
                    val playUrl = localPath ?: detail.url

                    // 跳转到播放页面
                    PlayerActivity.start(
                        context = this@PlaylistDetailActivity,
                        songId = song.id,
                        songName = song.name,
                        songArtists = song.artists,
                        platform = platform,
                        songUrl = playUrl,
                        songCover = detail.cover ?: song.coverUrl,
                        songLyrics = detail.lyrics
                    )
                } else {
                    Toast.makeText(this@PlaylistDetailActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongsToNowPlaying(songs: List<PlaylistSong>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                songs.forEach { song ->
                    val currentList = playlistManager.currentPlaylist.value
                    if (currentList.none { it.id == song.id }) {
                        playlistManager.addSong(song)
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }
                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到正在播放列表"
                }
                Toast.makeText(this@PlaylistDetailActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
        }
    }
}
