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
import com.tacke.music.data.model.RecentPlay
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.RecentPlayRepository
import com.tacke.music.databinding.ActivityRecentPlayBinding
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.RecentPlayAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecentPlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecentPlayBinding
    private lateinit var recentPlayRepository: RecentPlayRepository
    private lateinit var playbackManager: PlaybackManager
    private lateinit var playlistManager: PlaylistManager
    private lateinit var adapter: RecentPlayAdapter

    private var isMultiSelectMode = false
    private val selectedItems = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecentPlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recentPlayRepository = RecentPlayRepository(this)
        playbackManager = PlaybackManager.getInstance(this)
        playlistManager = PlaylistManager.getInstance(this)

        setupRecyclerView()
        setupClickListeners()
        observeRecentPlays()
    }

    private fun setupRecyclerView() {
        adapter = RecentPlayAdapter(
            onItemClick = { recentPlay ->
                if (isMultiSelectMode) {
                    toggleSelection(recentPlay.id)
                } else {
                    playSong(recentPlay)
                }
            },
            onMoreClick = { recentPlay ->
                if (!isMultiSelectMode) {
                    showSongOptions(recentPlay)
                }
            },
            onLongClick = { recentPlay ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    toggleSelection(recentPlay.id)
                    true
                } else {
                    false
                }
            },
            lifecycleScope = lifecycleScope,
            onCoverLoaded = { id, coverPath ->
                // 封面加载完成后的回调
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
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
            showMoreOptions()
        }

        binding.btnPlayAll.setOnClickListener {
            playAllSongs()
        }

        binding.btnBatchManage.setOnClickListener {
            enterMultiSelectMode()
        }

        binding.btnSelectAll.setOnClickListener {
            if (selectedItems.size == adapter.itemCount) {
                selectedItems.clear()
            } else {
                selectedItems.clear()
                adapter.getAllRecentPlays().forEach { selectedItems.add(it.id) }
            }
            adapter.setSelectedItems(selectedItems)
            updateSelectedCount()
        }

        binding.btnAddToNowPlaying.setOnClickListener {
            val selectedSongs = adapter.getAllRecentPlays().filter { selectedItems.contains(it.id) }
            if (selectedSongs.isNotEmpty()) {
                addSongsToNowPlaying(selectedSongs)
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

    private fun observeRecentPlays() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                recentPlayRepository.getRecentPlays().collect { recentPlays ->
                    adapter.submitList(recentPlays)
                    updateEmptyState(recentPlays.isEmpty())
                    updateRecordCount(recentPlays.size)

                    if (isMultiSelectMode) {
                        val validSelections = selectedItems.intersect(recentPlays.map { it.id }.toSet())
                        if (validSelections.size != selectedItems.size) {
                            selectedItems.clear()
                            selectedItems.addAll(validSelections)
                            adapter.setSelectedItems(selectedItems)
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

    private fun updateRecordCount(count: Int) {
        binding.tvSongListTitle.text = "播放记录（共${count}首）"
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        binding.batchActionBar.visibility = View.VISIBLE
        binding.btnBatchManage.visibility = View.GONE
        binding.btnPlayAll.visibility = View.GONE
        adapter.setMultiSelectMode(true)
        selectedItems.clear()
        updateSelectedCount()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        binding.batchActionBar.visibility = View.GONE
        binding.btnBatchManage.visibility = View.VISIBLE
        binding.btnPlayAll.visibility = View.VISIBLE
        adapter.setMultiSelectMode(false)
        selectedItems.clear()
    }

    private fun toggleSelection(id: String) {
        if (selectedItems.contains(id)) {
            selectedItems.remove(id)
        } else {
            selectedItems.add(id)
        }
        adapter.setSelectedItems(selectedItems)
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        binding.tvSelectedCount.text = "已选择 ${selectedItems.size} 项"
    }

    private fun showSongOptions(recentPlay: RecentPlay) {
        val options = arrayOf("播放", "删除记录")

        MaterialAlertDialogBuilder(this)
            .setTitle(recentPlay.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playSong(recentPlay)
                    1 -> deleteSingleRecord(recentPlay.id)
                }
            }
            .show()
    }

    private fun showMoreOptions() {
        val options = arrayOf("清空所有记录")

        MaterialAlertDialogBuilder(this)
            .setTitle("更多选项")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showClearAllDialog()
                }
            }
            .show()
    }

    private fun showDeleteConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除记录")
            .setMessage("确定要删除选中的 ${selectedItems.size} 条播放记录吗？")
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedRecords()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedRecords() {
        lifecycleScope.launch {
            try {
                selectedItems.forEach { id ->
                    recentPlayRepository.deleteById(id)
                }
                exitMultiSelectMode()
                Toast.makeText(this@RecentPlayActivity, "已删除 ${selectedItems.size} 条记录", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteSingleRecord(id: String) {
        lifecycleScope.launch {
            try {
                recentPlayRepository.deleteById(id)
                Toast.makeText(this@RecentPlayActivity, "已删除记录", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showClearAllDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空记录")
            .setMessage("确定要清空所有播放记录吗？")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    recentPlayRepository.clearAll()
                    Toast.makeText(this@RecentPlayActivity, "已清空播放记录", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun playSong(recentPlay: RecentPlay) {
        lifecycleScope.launch {
            try {
                // 使用 playFromRecentPlay 方法，自动验证平台并获取歌曲详情
                val success = playbackManager.playFromRecentPlay(this@RecentPlayActivity, recentPlay)
                if (!success) {
                    Toast.makeText(this@RecentPlayActivity, "获取歌曲信息失败，可能音源不可用", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAllSongs() {
        val recentPlays = adapter.getAllRecentPlays()
        if (recentPlays.isEmpty()) {
            Toast.makeText(this, "暂无播放记录", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                // 清空当前播放列表并添加所有歌曲
                playlistManager.clearPlaylist()
                recentPlays.forEach { recentPlay ->
                    playlistManager.addSong(recentPlay.toPlaylistSong())
                }

                // 播放第一首 - 使用 PlaylistSong 方式播放，避免 playFromRecentPlay 清空列表
                val firstPlay = recentPlays.first()
                val platform = try {
                    MusicRepository.Platform.valueOf(firstPlay.platform.uppercase())
                } catch (e: Exception) {
                    MusicRepository.Platform.KUWO
                }

                // 获取歌曲详情
                val detail = withContext(Dispatchers.IO) {
                    MusicRepository().getSongDetail(platform, firstPlay.id, "320k")
                }

                if (detail != null) {
                    val playlistSong = firstPlay.toPlaylistSong()
                    playbackManager.playFromPlaylist(
                        this@RecentPlayActivity,
                        playlistSong,
                        detail.url,
                        detail
                    )
                    Toast.makeText(this@RecentPlayActivity, "开始播放全部", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@RecentPlayActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongsToNowPlaying(recentPlays: List<RecentPlay>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                recentPlays.forEach { recentPlay ->
                    val currentList = playlistManager.currentPlaylist.value
                    if (currentList.none { it.id == recentPlay.id }) {
                        playlistManager.addSong(recentPlay.toPlaylistSong())
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }
                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到正在播放列表"
                }
                Toast.makeText(this@RecentPlayActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
