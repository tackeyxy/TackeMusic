package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.data.db.FavoriteSongEntity
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.databinding.ActivityPlaylistDetailBinding
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import kotlinx.coroutines.launch
import java.io.File

class FavoriteSongsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playlistManager: PlaylistManager
    private lateinit var playbackManager: PlaybackManager
    private lateinit var songAdapter: FavoriteSongAdapter
    private var favoriteSongs: List<FavoriteSongEntity> = emptyList()
    private var isMultiSelectMode = false
    private val selectedSongs = mutableSetOf<String>()

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FavoriteSongsActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        favoriteRepository = FavoriteRepository(this)
        playlistManager = PlaylistManager.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        observeFavoriteSongs()
    }

    private fun setupUI() {
        // 设置标题
        binding.tvTitle.text = "我喜欢的"
        binding.tvSongListTitle.text = "我的收藏歌曲"

        // 返回按钮
        binding.btnBack.setOnClickListener {
            if (isMultiSelectMode) {
                exitMultiSelectMode()
            } else {
                finish()
            }
        }

        // 播放全部按钮
        binding.btnPlayAll.setOnClickListener {
            if (!isMultiSelectMode) {
                playAllSongs()
            }
        }

        // 隐藏更多按钮（我喜欢的歌单不需要编辑功能）
        binding.btnMore.visibility = View.GONE
    }

    private fun setupRecyclerView() {
        songAdapter = FavoriteSongAdapter(
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
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@FavoriteSongsActivity)
            adapter = songAdapter
        }
    }

    private fun setupClickListeners() {
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
                favoriteSongs.forEach { selectedSongs.add(it.id) }
            }
            songAdapter.setSelectedItems(selectedSongs)
            updateSelectedCount()
        }

        // 添加到播放按钮
        binding.batchActionBarContainer.btnAddToNowPlaying.setOnClickListener {
            val selectedSongList = favoriteSongs.filter { selectedSongs.contains(it.id) }
            if (selectedSongList.isNotEmpty()) {
                addSongsToNowPlaying(selectedSongList)
            }
            exitMultiSelectMode()
        }

        // 删除按钮 - 使用下载按钮的位置作为删除按钮
        binding.batchActionBarContainer.btnBatchDownload.setOnClickListener {
            showDeleteConfirm()
        }
    }

    private fun observeFavoriteSongs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                favoriteRepository.getAllFavoriteSongs().collect { songs ->
                    favoriteSongs = songs
                    songAdapter.submitList(songs)
                    binding.tvSongListTitle.text = "歌曲列表（共${songs.size}首）"

                    // 更新空状态
                    if (songs.isEmpty()) {
                        binding.emptyView.visibility = View.VISIBLE
                        binding.recyclerView.visibility = View.GONE
                    } else {
                        binding.emptyView.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE
                    }

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

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        binding.batchActionBarContainer.root.visibility = View.VISIBLE
        binding.btnPlayAll.visibility = View.GONE
        songAdapter.setMultiSelectMode(true)
        selectedSongs.clear()
        updateSelectedCount()
        // 隐藏"喜欢"按钮（已经在喜欢列表中）
        binding.batchActionBarContainer.btnAddToFavorite.visibility = View.GONE
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

    private fun showDeleteConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle("移除收藏")
            .setMessage("确定要从我喜欢中移除选中的 ${selectedSongs.size} 首歌曲吗？")
            .setPositiveButton("移除") { _, _ ->
                removeSelectedFromFavorites()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun removeSelectedFromFavorites() {
        lifecycleScope.launch {
            try {
                val songsToRemove = favoriteSongs.filter { selectedSongs.contains(it.id) }
                songsToRemove.forEach { song ->
                    favoriteRepository.removeFromFavorites(song)
                }
                exitMultiSelectMode()
                Toast.makeText(this@FavoriteSongsActivity, "已移除 ${songsToRemove.size} 首歌曲", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@FavoriteSongsActivity, "移除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playSong(song: FavoriteSongEntity) {
        lifecycleScope.launch {
            try {
                val platform = when (song.platform.uppercase()) {
                    "KUWO" -> MusicRepository.Platform.KUWO
                    "NETEASE" -> MusicRepository.Platform.NETEASE
                    else -> MusicRepository.Platform.KUWO
                }

                val repository = MusicRepository()
                val detail = repository.getSongDetail(platform, song.id, "320k")

                if (detail != null) {
                    // 添加到播放列表
                    val playlistSong = com.tacke.music.data.model.PlaylistSong(
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl,
                        platform = song.platform
                    )
                    playlistManager.addSong(playlistSong)

                    // 播放歌曲
                    playbackManager.playFromPlaylist(
                        context = this@FavoriteSongsActivity,
                        song = playlistSong,
                        playUrl = detail.url,
                        songDetail = detail
                    )
                } else {
                    Toast.makeText(this@FavoriteSongsActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@FavoriteSongsActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAllSongs() {
        if (favoriteSongs.isEmpty()) {
            Toast.makeText(this, "暂无收藏歌曲", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                // 清空当前播放列表并添加所有喜欢歌曲
                playlistManager.clearPlaylist()

                favoriteSongs.forEach { song ->
                    val playlistSong = com.tacke.music.data.model.PlaylistSong(
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl,
                        platform = song.platform
                    )
                    playlistManager.addSong(playlistSong)
                }

                // 播放第一首
                val firstSong = favoriteSongs.first()
                val platform = when (firstSong.platform.uppercase()) {
                    "KUWO" -> MusicRepository.Platform.KUWO
                    "NETEASE" -> MusicRepository.Platform.NETEASE
                    else -> MusicRepository.Platform.KUWO
                }

                val repository = MusicRepository()
                val detail = repository.getSongDetail(platform, firstSong.id, "320k")

                if (detail != null) {
                    val playlistSong = com.tacke.music.data.model.PlaylistSong(
                        id = firstSong.id,
                        name = firstSong.name,
                        artists = firstSong.artists,
                        coverUrl = firstSong.coverUrl,
                        platform = firstSong.platform
                    )

                    playbackManager.playFromPlaylist(
                        context = this@FavoriteSongsActivity,
                        song = playlistSong,
                        playUrl = detail.url,
                        songDetail = detail
                    )

                    Toast.makeText(this@FavoriteSongsActivity, "开始播放全部 ${favoriteSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FavoriteSongsActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@FavoriteSongsActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSongsToNowPlaying(songs: List<FavoriteSongEntity>) {
        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0
                songs.forEach { song ->
                    val playlistSong = com.tacke.music.data.model.PlaylistSong(
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl,
                        platform = song.platform
                    )
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
                Toast.makeText(this@FavoriteSongsActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@FavoriteSongsActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
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

    /**
     * 我喜欢歌曲列表适配器
     */
    inner class FavoriteSongAdapter(
        private val onItemClick: (FavoriteSongEntity) -> Unit,
        private val onLongClick: (FavoriteSongEntity) -> Boolean
    ) : RecyclerView.Adapter<FavoriteSongAdapter.SongViewHolder>() {

        private var songs: List<FavoriteSongEntity> = emptyList()
        private var isMultiSelectMode = false
        private val selectedItems = mutableSetOf<String>()

        fun submitList(newSongs: List<FavoriteSongEntity>) {
            songs = newSongs
            notifyDataSetChanged()
        }

        fun setMultiSelectMode(enabled: Boolean) {
            isMultiSelectMode = enabled
            if (!enabled) {
                selectedItems.clear()
            }
            notifyDataSetChanged()
        }

        fun setSelectedItems(selected: Set<String>) {
            selectedItems.clear()
            selectedItems.addAll(selected)
            notifyDataSetChanged()
        }

        inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvSongName: TextView = itemView.findViewById(R.id.tvSongName)
            private val tvArtist: TextView = itemView.findViewById(R.id.tvArtist)
            private val ivSource: ImageView = itemView.findViewById(R.id.ivSource)
            private val ivCover: ImageView = itemView.findViewById(R.id.ivCover)
            private val cvCover: CardView = itemView.findViewById(R.id.cvCover)
            private val flCheckbox: View = itemView.findViewById(R.id.flCheckbox)
            private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)
            private val tvIndex: TextView = itemView.findViewById(R.id.tvIndex)
            private val coverOverlay: View = itemView.findViewById(R.id.coverOverlay)

            fun bind(song: FavoriteSongEntity, position: Int) {
                tvSongName.text = song.name
                tvArtist.text = song.artists
                tvIndex.text = (position + 1).toString()

                // 设置音源图标
                val iconRes = when (song.platform.uppercase()) {
                    "KUWO" -> R.drawable.ic_kuwo_logo
                    "NETEASE" -> R.drawable.ic_netease_logo
                    else -> R.drawable.ic_music_note
                }
                ivSource.setImageResource(iconRes)

                // 加载封面
                loadCover(song)

                val isSelected = selectedItems.contains(song.id)

                if (isMultiSelectMode) {
                    flCheckbox.visibility = View.VISIBLE
                    tvIndex.visibility = View.GONE
                    ivCheckbox.isSelected = isSelected
                    updateSelectedVisuals(isSelected)

                    itemView.setOnClickListener {
                        onItemClick(song)
                    }
                } else {
                    flCheckbox.visibility = View.GONE
                    tvIndex.visibility = View.VISIBLE
                    resetVisuals()

                    itemView.setOnClickListener { onItemClick(song) }
                }

                itemView.setOnLongClickListener { onLongClick(song) }
            }

            private fun updateSelectedVisuals(isSelected: Boolean) {
                if (isSelected) {
                    itemView.setBackgroundResource(R.drawable.bg_item_selected)
                    coverOverlay.visibility = View.VISIBLE
                    coverOverlay.alpha = 0.3f
                    cvCover.scaleX = 0.95f
                    cvCover.scaleY = 0.95f
                } else {
                    itemView.setBackgroundResource(android.R.color.transparent)
                    coverOverlay.visibility = View.GONE
                    cvCover.scaleX = 1f
                    cvCover.scaleY = 1f
                }
            }

            private fun resetVisuals() {
                itemView.setBackgroundResource(android.R.color.transparent)
                coverOverlay.visibility = View.GONE
                cvCover.scaleX = 1f
                cvCover.scaleY = 1f
            }

            private fun loadCover(song: FavoriteSongEntity) {
                val coverUrl = song.coverUrl

                when {
                    coverUrl.isNullOrEmpty() -> {
                        ivCover.setImageResource(R.drawable.ic_music_note)
                    }
                    coverUrl.startsWith("http") -> {
                        Glide.with(itemView.context)
                            .load(coverUrl)
                            .placeholder(R.drawable.ic_music_note)
                            .error(R.drawable.ic_music_note)
                            .into(ivCover)
                    }
                    else -> {
                        try {
                            val file = File(coverUrl)
                            if (file.exists()) {
                                Glide.with(itemView.context)
                                    .load(file)
                                    .placeholder(R.drawable.ic_music_note)
                                    .error(R.drawable.ic_music_note)
                                    .into(ivCover)
                            } else {
                                ivCover.setImageResource(R.drawable.ic_music_note)
                            }
                        } catch (e: Exception) {
                            ivCover.setImageResource(R.drawable.ic_music_note)
                        }
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_song_modern, parent, false)
            return SongViewHolder(view)
        }

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            holder.bind(songs[position], position)
        }

        override fun getItemCount(): Int = songs.size
    }
}
