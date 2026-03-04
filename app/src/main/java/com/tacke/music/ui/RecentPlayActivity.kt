package com.tacke.music.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tacke.music.data.model.RecentPlay
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.RecentPlayRepository
import com.tacke.music.databinding.ActivityRecentPlayBinding
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.ui.adapter.RecentPlayAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecentPlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecentPlayBinding
    private lateinit var recentPlayRepository: RecentPlayRepository
    private lateinit var playbackManager: PlaybackManager
    private lateinit var adapter: RecentPlayAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecentPlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recentPlayRepository = RecentPlayRepository(this)
        playbackManager = PlaybackManager.getInstance(this)

        setupRecyclerView()
        setupClickListeners()
        observeRecentPlays()
    }

    private fun setupRecyclerView() {
        adapter = RecentPlayAdapter(
            onItemClick = { recentPlay ->
                playSong(recentPlay)
            },
            onItemLongClick = { recentPlay ->
                showDeleteDialog(recentPlay)
                true
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnClearAll.setOnClickListener {
            showClearAllDialog()
        }
    }

    private fun observeRecentPlays() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                recentPlayRepository.getRecentPlays().collect { recentPlays ->
                    adapter.submitList(recentPlays)
                    binding.tvTitle.text = "最近播放"

                    if (recentPlays.isEmpty()) {
                        binding.tvEmpty.visibility = android.view.View.VISIBLE
                        binding.recyclerView.visibility = android.view.View.GONE
                    } else {
                        binding.tvEmpty.visibility = android.view.View.GONE
                        binding.recyclerView.visibility = android.view.View.VISIBLE
                    }
                }
            }
        }
    }

    private fun playSong(recentPlay: RecentPlay) {
        lifecycleScope.launch {
            try {
                // 使用新的 playFromRecentPlay 方法，自动验证平台并获取歌曲详情
                val success = playbackManager.playFromRecentPlay(this@RecentPlayActivity, recentPlay)
                if (!success) {
                    Toast.makeText(this@RecentPlayActivity, "获取歌曲信息失败，可能音源不可用", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RecentPlayActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteDialog(recentPlay: RecentPlay) {
        AlertDialog.Builder(this)
            .setTitle("删除记录")
            .setMessage("确定要删除这条播放记录吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    // 由于 RecentPlayDao 没有单个删除方法，这里需要重新插入所有记录（除了被删除的）
                    // 或者可以添加一个 deleteById 方法
                    Toast.makeText(this@RecentPlayActivity, "功能开发中", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
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
}
