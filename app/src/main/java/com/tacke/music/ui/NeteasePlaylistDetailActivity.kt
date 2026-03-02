package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.tacke.music.data.api.PlaylistTrack
import com.tacke.music.data.api.RetrofitClient
import com.tacke.music.data.api.SongDetailInfo
import com.tacke.music.data.api.TrackArtist
import com.tacke.music.data.api.TrackAlbum
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.databinding.ActivityNeteasePlaylistDetailBinding
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.NeteasePlaylistTrackAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NeteasePlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNeteasePlaylistDetailBinding
    private lateinit var trackAdapter: NeteasePlaylistTrackAdapter
    private lateinit var playlistManager: PlaylistManager

    private var playlistId: Long = 0
    private var playlistName: String = ""
    private var playlistCover: String = ""

    // 分页加载相关
    private var allTrackIds: List<Long> = emptyList()
    private var loadedTrackCount = 0
    private var isLoadingMore = false
    private val batchSize = 100

    companion object {
        fun start(context: Context, playlistId: Long, playlistName: String, playlistCover: String) {
            val intent = Intent(context, NeteasePlaylistDetailActivity::class.java).apply {
                putExtra("playlist_id", playlistId)
                putExtra("playlist_name", playlistName)
                putExtra("playlist_cover", playlistCover)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNeteasePlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistId = intent.getLongExtra("playlist_id", 0)
        playlistName = intent.getStringExtra("playlist_name") ?: ""
        playlistCover = intent.getStringExtra("playlist_cover") ?: ""

        if (playlistId == 0L) {
            Toast.makeText(this, "歌单不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        playlistManager = PlaylistManager.getInstance(this)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        setupScrollListener()
        loadPlaylistDetail()
    }

    private fun setupUI() {
        binding.tvTitle.text = playlistName
        binding.tvPlaylistName.text = playlistName

        // 加载封面
        Glide.with(this)
            .load(playlistCover)
            .placeholder(com.tacke.music.R.drawable.ic_album_default)
            .error(com.tacke.music.R.drawable.ic_album_default)
            .into(binding.ivPlaylistCover)
    }

    private fun setupRecyclerView() {
        trackAdapter = NeteasePlaylistTrackAdapter(
            onItemClick = { track ->
                playTrack(track)
            },
            onMoreClick = { track ->
                showTrackOptions(track)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = trackAdapter
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnPlayAll.setOnClickListener {
            playAllTracks()
        }
    }

    private fun setupScrollListener() {
        binding.nestedScrollView.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
                // 检查是否滚动到底部
                val child = v.getChildAt(0)
                if (child != null) {
                    val diff = child.bottom - (v.height + scrollY)
                    // 当距离底部小于100px时触发加载更多
                    if (diff < 100 && !isLoadingMore && loadedTrackCount < allTrackIds.size) {
                        loadMoreTracks()
                    }
                }
            }
        )
    }

    private fun loadPlaylistDetail() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.playlistApi.getPlaylistDetail(playlistId)
                }

                if (response.code == 200 && response.playlist != null) {
                    val playlist = response.playlist
                    playlistName = playlist.name
                    playlistCover = playlist.coverImgUrl ?: ""

                    binding.tvPlaylistName.text = playlistName
                    binding.tvSongCount.text = "${playlist.trackCount} 首歌曲"
                    binding.tvDescription.text = playlist.description ?: "暂无简介"

                    // 重新加载封面
                    Glide.with(this@NeteasePlaylistDetailActivity)
                        .load(playlistCover)
                        .placeholder(com.tacke.music.R.drawable.ic_album_default)
                        .error(com.tacke.music.R.drawable.ic_album_default)
                        .into(binding.ivPlaylistCover)

                    // 获取所有trackIds
                    allTrackIds = playlist.trackIds?.map { it.id } ?: emptyList()

                    // 如果tracks已经有数据，先显示
                    val initialTracks = playlist.tracks ?: emptyList()
                    if (initialTracks.isNotEmpty()) {
                        trackAdapter.submitList(initialTracks)
                        loadedTrackCount = initialTracks.size
                        updateEmptyState(false)
                    } else {
                        updateEmptyState(true)
                    }

                    // 如果还有更多歌曲需要加载
                    if (loadedTrackCount < allTrackIds.size) {
                        loadMoreTracks()
                    }
                } else {
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "加载歌单失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun loadMoreTracks() {
        if (isLoadingMore || loadedTrackCount >= allTrackIds.size) return

        isLoadingMore = true
        binding.progressBarLoadMore.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // 计算本次要加载的id范围
                val endIndex = minOf(loadedTrackCount + batchSize, allTrackIds.size)
                val batchIds = allTrackIds.subList(loadedTrackCount, endIndex)

                // 构建ids参数 [id1,id2,id3,...]
                val idsString = "[${batchIds.joinToString(",")}]"

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.playlistApi.getSongDetails(idsString)
                }

                if (response.code == 200 && response.songs != null) {
                    val newTracks = response.songs.map { it.toPlaylistTrack() }
                    trackAdapter.addTracks(newTracks)
                    loadedTrackCount += batchIds.size
                    updateEmptyState(trackAdapter.getAllTracks().isEmpty())
                }
            } catch (e: Exception) {
                // 加载失败，不中断流程
            } finally {
                isLoadingMore = false
                binding.progressBarLoadMore.visibility = View.GONE
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun playTrack(track: PlaylistTrack) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val platform = MusicRepository.Platform.NETEASE

                // 获取歌曲详情
                val detail = withContext(Dispatchers.IO) {
                    MusicRepository().getSongDetail(platform, track.id.toString(), "320k")
                }

                if (detail != null) {
                    // 跳转到播放页面
                    PlayerActivity.start(
                        context = this@NeteasePlaylistDetailActivity,
                        songId = track.id.toString(),
                        songName = track.name,
                        songArtists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                        platform = platform,
                        songUrl = detail.url,
                        songCover = track.al?.picUrl ?: detail.cover,
                        songLyrics = detail.lyrics
                    )
                } else {
                    Toast.makeText(this@NeteasePlaylistDetailActivity, "获取歌曲信息失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun playAllTracks() {
        val tracks = trackAdapter.getAllTracks()
        if (tracks.isEmpty()) {
            Toast.makeText(this, "歌单为空", Toast.LENGTH_SHORT).show()
            return
        }

        // 播放第一首
        playTrack(tracks[0])

        // 将剩余歌曲添加到播放列表
        lifecycleScope.launch {
            tracks.drop(1).forEach { track ->
                val playlistSong = com.tacke.music.data.model.PlaylistSong(
                    id = track.id.toString(),
                    name = track.name,
                    artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                    coverUrl = track.al?.picUrl ?: "",
                    platform = "netease"
                )
                playlistManager.addSong(playlistSong)
            }
        }
    }

    private fun showTrackOptions(track: PlaylistTrack) {
        val options = arrayOf("播放", "添加到播放列表")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(track.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playTrack(track)
                    1 -> addToNowPlaying(track)
                }
            }
            .show()
    }

    private fun addToNowPlaying(track: PlaylistTrack) {
        lifecycleScope.launch {
            try {
                val playlistSong = com.tacke.music.data.model.PlaylistSong(
                    id = track.id.toString(),
                    name = track.name,
                    artists = track.ar?.joinToString(",") { it.name } ?: "未知艺人",
                    coverUrl = track.al?.picUrl ?: "",
                    platform = "netease"
                )
                playlistManager.addSong(playlistSong)
                Toast.makeText(this@NeteasePlaylistDetailActivity, "已添加到播放列表", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@NeteasePlaylistDetailActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 扩展函数：将SongDetailInfo转换为PlaylistTrack
    private fun SongDetailInfo.toPlaylistTrack(): PlaylistTrack {
        val trackArtists = this.artists?.map { artist ->
            TrackArtist(id = artist.id, name = artist.name)
        }
        val trackAlbum = this.album?.let { alb ->
            TrackAlbum(id = alb.id, name = alb.name, picUrl = alb.picUrl)
        }
        return PlaylistTrack(
            id = this.id,
            name = this.name,
            ar = trackArtists,
            al = trackAlbum,
            dt = this.duration
        )
    }
}
