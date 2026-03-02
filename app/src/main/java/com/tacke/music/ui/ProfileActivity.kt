package com.tacke.music.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityProfileBinding
import com.tacke.music.download.DownloadManager
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var playlistRepository: PlaylistRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloadManager = DownloadManager.getInstance(this)
        playlistRepository = PlaylistRepository(this)

        setupClickListeners()
        setupBottomNavigation()
        observeDownloadCount()
        observePlaylistCount()
    }

    private fun setupClickListeners() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadActivity::class.java))
        }

        binding.btnRecent.setOnClickListener {
            startActivity(Intent(this, RecentPlayActivity::class.java))
        }

        binding.btnMyPlaylists.setOnClickListener {
            startActivity(Intent(this, PlaylistActivity::class.java))
        }

        binding.btnSettingsMenu.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupBottomNavigation() {
        binding.navHome.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.navDiscover.setOnClickListener {
            // 跳转到正在播放页面 - 支持空状态进入
            PlayerActivity.startEmpty(this)
        }

        binding.navProfile.setOnClickListener {
            // 当前页面
        }
    }

    private fun observeDownloadCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                downloadManager.completedTasks.collect { tasks ->
                    // 数量显示已移除
                }
            }
        }
    }

    private fun observePlaylistCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistRepository.getAllPlaylists().collect { playlists ->
                    // 数量显示已移除
                }
            }
        }
    }
}
