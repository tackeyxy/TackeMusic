package com.tacke.music.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.model.Playlist
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityPlaylistBinding
import com.tacke.music.ui.adapter.PlaylistAdapter
import kotlinx.coroutines.launch

class PlaylistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistBinding
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var playlistAdapter: PlaylistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistRepository = PlaylistRepository(this)

        setupRecyclerView()
        setupClickListeners()
        observePlaylists()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onItemClick = { playlist ->
                openPlaylistDetail(playlist)
            },
            onMoreClick = { playlist ->
                showPlaylistOptions(playlist)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = playlistAdapter
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnAdd.setOnClickListener {
            showCreatePlaylistDialog()
        }
    }

    private fun observePlaylists() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playlistRepository.getAllPlaylists().collect { playlists ->
                    playlistAdapter.submitList(playlists)
                    updateEmptyState(playlists.isEmpty())
                }
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) android.view.View.VISIBLE else android.view.View.GONE
        binding.recyclerView.visibility = if (isEmpty) android.view.View.GONE else android.view.View.VISIBLE
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
                Toast.makeText(this@PlaylistActivity, "歌单创建成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistActivity, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPlaylistOptions(playlist: Playlist) {
        val options = arrayOf("重命名", "删除")

        MaterialAlertDialogBuilder(this)
            .setTitle(playlist.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog(playlist)
                    1 -> showDeletePlaylistConfirm(playlist)
                }
            }
            .show()
    }

    private fun showRenamePlaylistDialog(playlist: Playlist) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_playlist, null)
        val etName = dialogView.findViewById<EditText>(R.id.etPlaylistName)
        etName.setText(playlist.name)
        etName.setSelection(playlist.name.length)

        MaterialAlertDialogBuilder(this)
            .setTitle("重命名歌单")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isNotEmpty()) {
                    renamePlaylist(playlist, newName)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renamePlaylist(playlist: Playlist, newName: String) {
        lifecycleScope.launch {
            try {
                val updatedPlaylist = playlist.copy(name = newName)
                playlistRepository.updatePlaylist(updatedPlaylist)
                Toast.makeText(this@PlaylistActivity, "重命名成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistActivity, "重命名失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeletePlaylistConfirm(playlist: Playlist) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除歌单")
            .setMessage("确定要删除歌单\"${playlist.name}\"吗？歌单内的歌曲也会被删除。")
            .setPositiveButton("删除") { _, _ ->
                deletePlaylist(playlist.id)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deletePlaylist(playlistId: String) {
        lifecycleScope.launch {
            try {
                playlistRepository.deletePlaylist(playlistId)
                Toast.makeText(this@PlaylistActivity, "歌单已删除", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistActivity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openPlaylistDetail(playlist: Playlist) {
        val intent = Intent(this, PlaylistDetailActivity::class.java).apply {
            putExtra("playlist_id", playlist.id)
            putExtra("playlist_name", playlist.name)
        }
        startActivity(intent)
    }
}
