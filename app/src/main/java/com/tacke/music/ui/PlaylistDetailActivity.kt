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
import com.tacke.music.R
import com.tacke.music.data.model.Playlist
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityPlaylistDetailBinding
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.PlaylistSongAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playlistManager: PlaylistManager
    private lateinit var playbackManager: PlaybackManager
    private lateinit var songAdapter: PlaylistSongAdapter

    private var playlistId: String = ""
    private var playlistName: String = ""
    private var currentPlaylist: Playlist? = null
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
        favoriteRepository = FavoriteRepository(this)
        playlistManager = PlaylistManager.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        observeSongs()
    }

    private fun setupUI() {
        binding.tvTitle.text = playlistName
        loadPlaylistInfo()
    }

    private fun loadPlaylistInfo() {
        lifecycleScope.launch {
            try {
                currentPlaylist = playlistRepository.getPlaylistById(playlistId)
                currentPlaylist?.let { playlist ->
                    playlistName = playlist.name
                    binding.tvTitle.text = playlistName
                }
            } catch (e: Exception) {
                // 加载失败使用默认显示
            }
        }
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
            onLongClick = { song ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    toggleSelection(song.id)
                    true
                } else {
                    false
                }
            },
            lifecycleScope = lifecycleScope,
            onCoverLoaded = { songId, coverPath ->
                // 封面下载完成后更新数据库
                lifecycleScope.launch {
                    playlistRepository.updateSongCover(playlistId, songId, coverPath)
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
            playAllSongs()
        }

        setupBatchActionListeners()
    }

    private fun setupBatchActionListeners() {
        // 关闭按钮
        binding.batchActionBarContainer.btnCloseBatch?.setOnClickListener {
            exitMultiSelectMode()
        }

        // 全选按钮
        binding.batchActionBarContainer.btnSelectAll.setOnClickListener {
            if (selectedSongs.size == songAdapter.itemCount) {
                selectedSongs.clear()
            } else {
                selectedSongs.clear()
                songAdapter.getAllSongs().forEach { selectedSongs.add(it.id) }
            }
            songAdapter.setSelectedItems(selectedSongs)
            updateSelectedCount()
        }

        // 添加到喜欢按钮
        binding.batchActionBarContainer.btnAddToFavorite.setOnClickListener {
            val selectedSongList = songAdapter.getAllSongs().filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isNotEmpty()) {
                addSongsToFavorites(selectedSongList)
            }
            exitMultiSelectMode()
        }

        // 添加到歌单按钮
        binding.batchActionBarContainer.btnAddToPlaylist.setOnClickListener {
            val selectedSongList = songAdapter.getAllSongs().filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isEmpty()) {
                Toast.makeText(this, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchPlaylistSelectionDialog(selectedSongList)
        }

        // 添加到播放按钮
        binding.batchActionBarContainer.btnAddToNowPlaying.setOnClickListener {
            val selectedSongList = songAdapter.getAllSongs().filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isNotEmpty()) {
                addSongsToNowPlaying(selectedSongList)
            }
            exitMultiSelectMode()
        }

        // 移除所选按钮
        binding.batchActionBarContainer.btnRemoveSelected.setOnClickListener {
            showDeleteConfirm()
        }

        // 清空列表按钮
        binding.batchActionBarContainer.btnClearAll.setOnClickListener {
            showClearAllConfirmDialog()
        }
    }

    private fun showClearAllConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空歌单")
            .setMessage("确定要清空歌单中的所有歌曲吗？")
            .setPositiveButton("清空") { _, _ ->
                clearAllSongs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAllSongs() {
        lifecycleScope.launch {
            try {
                val allSongs = songAdapter.getAllSongs()
                if (allSongs.isEmpty()) {
                    Toast.makeText(this@PlaylistDetailActivity, "歌单中没有歌曲", Toast.LENGTH_SHORT).show()
                    exitMultiSelectMode()
                    return@launch
                }
                allSongs.forEach { song ->
                    playlistRepository.removeSongFromPlaylist(playlistId, song.id)
                }
                Toast.makeText(this@PlaylistDetailActivity, "已清空歌单中的所有歌曲", Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeSongs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistRepository.getSongsInPlaylist(playlistId).collect { songs ->
                    songAdapter.submitList(songs)
                    updateEmptyState(songs.isEmpty())
                    updateSongCount(songs.size)

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

    private fun updateSongCount(count: Int) {
        binding.tvSongListTitle.text = "歌曲列表（共${count}首）"
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        binding.batchActionBarContainer.root.visibility = View.VISIBLE
        binding.btnPlayAll.visibility = View.GONE
        songAdapter.setMultiSelectMode(true)
        selectedSongs.clear()
        updateSelectedCount()
        // 隐藏"歌单"按钮（已经在歌单中）
        binding.batchActionBarContainer.btnAddToPlaylist.visibility = View.GONE
        // 隐藏下载管理专用按钮
        binding.batchActionBarContainer.btnPauseSelected.visibility = View.GONE
        binding.batchActionBarContainer.btnResumeSelected.visibility = View.GONE
        setupBatchActionListeners()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        binding.batchActionBarContainer.root.visibility = View.GONE
        binding.btnPlayAll.visibility = View.VISIBLE
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
        binding.batchActionBarContainer.tvSelectedCount.text = selectedSongs.size.toString()
    }

    private fun toggleFavorite(song: PlaylistSong, isCurrentlyFavorite: Boolean) {
        lifecycleScope.launch {
            try {
                if (isCurrentlyFavorite) {
                    favoriteRepository.removeFromFavorites(song.id)
                    Toast.makeText(this@PlaylistDetailActivity, "已从我喜欢移除", Toast.LENGTH_SHORT).show()
                } else {
                    val songModel = com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl
                    )
                    favoriteRepository.addToFavorites(songModel, song.platform)
                    Toast.makeText(this@PlaylistDetailActivity, "已添加到我喜欢", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun showDeleteSingleConfirm(song: PlaylistSong) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除歌曲")
            .setMessage("确定要从歌单中删除\"${song.name}\"吗？")
            .setPositiveButton("删除") { _, _ ->
                removeSongFromPlaylist(song.id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun playSong(song: PlaylistSong) {
        lifecycleScope.launch {
            try {
                val platform = try {
                    MusicRepository.Platform.valueOf(song.platform.uppercase())
                } catch (e: Exception) {
                    MusicRepository.Platform.KUWO
                }

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

                    // 使用统一播放管理器播放歌曲
                    playbackManager.playFromPlaylist(this@PlaylistDetailActivity, song, playUrl, detail)
                } else {
                    Toast.makeText(this@PlaylistDetailActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAllSongs() {
        val songs = songAdapter.getAllSongs()
        if (songs.isEmpty()) {
            Toast.makeText(this, "歌单为空", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                // 清空当前播放列表并添加所有歌曲
                playlistManager.clearPlaylist()
                songs.forEach { song ->
                    playlistManager.addSong(song)
                }

                // 播放第一首
                val firstSong = songs.first()
                val platform = try {
                    MusicRepository.Platform.valueOf(firstSong.platform.uppercase())
                } catch (e: Exception) {
                    MusicRepository.Platform.KUWO
                }

                val downloadManager = com.tacke.music.download.DownloadManager.getInstance(this@PlaylistDetailActivity)
                val localPath = withContext(Dispatchers.IO) {
                    downloadManager.getLocalSongPath(firstSong.id)
                }

                val detail = withContext(Dispatchers.IO) {
                    MusicRepository().getSongDetail(platform, firstSong.id, "320k")
                }

                if (detail != null) {
                    val playUrl = localPath ?: detail.url
                    playbackManager.playFromPlaylist(this@PlaylistDetailActivity, firstSong, playUrl, detail)
                    Toast.makeText(this@PlaylistDetailActivity, "开始播放全部", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongsToFavorites(songs: List<PlaylistSong>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                songs.forEach { song ->
                    val songModel = com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl
                    )
                    val isAlreadyFavorite = favoriteRepository.isFavorite(song.id)
                    if (!isAlreadyFavorite) {
                        favoriteRepository.addToFavorites(songModel, song.platform)
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }
                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首到喜欢，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到我喜欢"
                }
                Toast.makeText(this@PlaylistDetailActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
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

    private fun showBatchPlaylistSelectionDialog(songs: List<PlaylistSong>) {
        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@PlaylistDetailActivity)
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

            MaterialAlertDialogBuilder(this@PlaylistDetailActivity)
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

    private fun showCreatePlaylistDialog(songs: List<PlaylistSong>) {
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

    private fun addSongsToPlaylist(targetPlaylistId: String, songs: List<PlaylistSong>) {
        lifecycleScope.launch {
            try {
                val songList = songs.map { song ->
                    com.tacke.music.data.model.Song(
                        index = 0,
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl
                    )
                }

                playlistRepository.addSongsToPlaylist(targetPlaylistId, songList, songs.firstOrNull()?.platform ?: "kuwo")

                Toast.makeText(
                    this@PlaylistDetailActivity,
                    "已添加 ${songs.size} 首歌曲到歌单",
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(
                    this@PlaylistDetailActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
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
