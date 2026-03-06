package com.tacke.music.playback

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.RecentPlay
import com.tacke.music.data.model.Song
import com.tacke.music.data.model.SongDetail
import com.tacke.music.data.preferences.PlaybackPreferences
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一播放管理器
 * 负责统一处理所有播放入口的逻辑，确保播放进度存储的一致性
 */
class PlaybackManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val playlistManager = PlaylistManager.getInstance(appContext)
    private val playbackPreferences = PlaybackPreferences.getInstance(appContext)

    companion object {
        @Volatile
        private var instance: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return instance ?: synchronized(this) {
                instance ?: PlaybackManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        // 支持的平台列表
        val SUPPORTED_PLATFORMS = listOf("KUWO", "NETEASE")
    }

    private val repository = MusicRepository()

    /**
     * 播放歌曲（从搜索列表）
     * @param context 上下文
     * @param song 搜索结果的歌曲
     * @param platform 音乐平台
     * @param songDetail 歌曲详情（包含URL、封面、歌词等）
     */
    suspend fun playFromSearch(
        context: Context,
        song: Song,
        platform: MusicRepository.Platform,
        songDetail: SongDetail
    ) {
        // 创建播放列表歌曲
        val playlistSong = PlaylistSong(
            id = song.id,
            name = song.name,
            artists = song.artists,
            coverUrl = songDetail.cover ?: song.coverUrl,
            platform = platform.name
        )

        // 添加到播放列表并设置为当前播放
        // 不再清空播放列表，保持持久化，新歌曲添加到列表末尾
        playlistManager.addSong(playlistSong)
        // 设置新添加的歌曲为当前播放索引（列表末尾）
        val newIndex = playlistManager.currentPlaylist.value.size - 1
        playlistManager.setCurrentIndex(newIndex.coerceAtLeast(0))

        // 保存播放状态（新歌曲从头开始播放，位置为0）
        savePlaybackState(
            songId = song.id,
            position = 0L,
            isPlaying = true,
            quality = "320k",
            songDetail = songDetail
        )

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = song.id,
            songName = song.name,
            songArtists = song.artists,
            platform = platform,
            songUrl = songDetail.url,
            songCover = songDetail.cover ?: song.coverUrl,
            songLyrics = songDetail.lyrics
        )
    }

    /**
     * 播放歌曲（从下载历史）
     * @param context 上下文
     * @param songId 歌曲ID
     * @param songName 歌曲名称
     * @param artist 艺术家
     * @param coverUrl 封面URL
     * @param playUrl 播放URL（本地文件路径或在线URL）
     * @param songDetail 歌曲详情（可选，用于获取歌词和封面）
     */
    suspend fun playFromDownload(
        context: Context,
        songId: String,
        songName: String,
        artist: String,
        coverUrl: String?,
        playUrl: String,
        platform: MusicRepository.Platform = MusicRepository.Platform.KUWO,
        songDetail: SongDetail? = null
    ) {
        // 创建播放列表歌曲
        val playlistSong = PlaylistSong(
            id = songId,
            name = songName,
            artists = artist,
            coverUrl = coverUrl ?: "",
            platform = platform.name
        )

        // 添加到播放列表
        playlistManager.addSong(playlistSong)

        // 关键修复：设置当前播放索引为新添加的歌曲
        // 这样播放列表中的"正在播放"图标才能正确显示
        val newIndex = playlistManager.currentPlaylist.value.size - 1
        playlistManager.setCurrentIndex(newIndex.coerceAtLeast(0))

        // 保存播放状态（新歌曲从头开始播放，位置为0）
        savePlaybackState(
            songId = songId,
            position = 0L,
            isPlaying = true,
            quality = "320k",
            songDetail = songDetail
        )

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = songId,
            songName = songName,
            songArtists = artist,
            platform = platform,
            songUrl = playUrl,
            songCover = songDetail?.cover ?: coverUrl,
            songLyrics = songDetail?.lyrics
        )
    }

    /**
     * 播放歌曲（从歌单）
     * @param context 上下文
     * @param song 歌单中的歌曲
     * @param playUrl 播放URL（本地文件路径或在线URL）
     * @param songDetail 歌曲详情（可选，用于获取歌词和封面）
     */
    suspend fun playFromPlaylist(
        context: Context,
        song: PlaylistSong,
        playUrl: String,
        songDetail: SongDetail? = null
    ) {
        val platform = try {
            MusicRepository.Platform.valueOf(song.platform.uppercase())
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }

        // 如果 songDetail 中有封面，更新 song 的 coverUrl
        val updatedSong = if (songDetail?.cover != null && song.coverUrl == null) {
            song.copy(coverUrl = songDetail.cover)
        } else {
            song
        }

        // 添加到播放列表
        playlistManager.addSong(updatedSong)

        // 关键修复：设置当前播放索引为新添加的歌曲
        // 这样播放列表中的"正在播放"图标才能正确显示
        val newIndex = playlistManager.currentPlaylist.value.size - 1
        playlistManager.setCurrentIndex(newIndex.coerceAtLeast(0))

        // 保存播放状态（新歌曲从头开始播放，位置为0）
        savePlaybackState(
            songId = song.id,
            position = 0L,
            isPlaying = true,
            quality = "320k",
            songDetail = songDetail
        )

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = song.id,
            songName = song.name,
            songArtists = song.artists,
            platform = platform,
            songUrl = playUrl,
            songCover = songDetail?.cover ?: song.coverUrl,
            songLyrics = songDetail?.lyrics
        )
    }

    /**
     * 播放歌曲（从正在播放列表）
     * 这种情况通常是切换歌曲，需要重置播放进度为0，确保新歌曲从头开始播放
     * @param context 上下文
     * @param song 要播放的歌曲
     * @param playUrl 播放URL
     * @param songDetail 歌曲详情
     */
    suspend fun playFromNowPlaying(
        context: Context,
        song: PlaylistSong,
        playUrl: String,
        songDetail: SongDetail? = null
    ) {
        val platform = try {
            MusicRepository.Platform.valueOf(song.platform.uppercase())
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }

        // 关键修复：彻底清除之前的播放状态，确保新歌曲从头开始播放
        // 先清除再保存，避免任何旧状态残留
        clearPlaybackState()

        // 确保播放位置为0，新歌曲从头开始
        playbackPreferences.currentPosition = 0L
        playbackPreferences.isPlaying = false

        // 保存播放状态（新歌曲从头开始播放，位置为0）
        savePlaybackState(
            songId = song.id,
            position = 0L,
            isPlaying = true,
            quality = "320k",
            songDetail = songDetail
        )

        // 再次确保位置为0
        playbackPreferences.currentPosition = 0L

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = song.id,
            songName = song.name,
            songArtists = song.artists,
            platform = platform,
            songUrl = playUrl,
            songCover = songDetail?.cover ?: song.coverUrl,
            songLyrics = songDetail?.lyrics
        )
    }

    /**
     * 保存播放状态
     */
    private fun savePlaybackState(
        songId: String,
        position: Long,
        isPlaying: Boolean,
        quality: String,
        songDetail: SongDetail? = null
    ) {
        playbackPreferences.apply {
            currentSongId = songId
            currentPosition = position
            this.isPlaying = isPlaying
            currentQuality = quality
        }

        // 同时保存完整的歌曲信息到PlaybackPreferences，用于恢复播放
        songDetail?.let { detail ->
            playbackPreferences.saveSongDetail(songId, detail)
        }
    }

    /**
     * 启动播放页面
     */
    private fun startPlayerActivity(
        context: Context,
        songId: String,
        songName: String,
        songArtists: String,
        platform: MusicRepository.Platform,
        songUrl: String,
        songCover: String?,
        songLyrics: String?
    ) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("song_id", songId)
            putExtra("song_name", songName)
            putExtra("song_artists", songArtists)
            putExtra("platform", platform.name)
            putExtra("song_url", songUrl)
            putExtra("song_cover", songCover)
            putExtra("song_lyrics", songLyrics)
            putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_NORMAL)
        }
        context.startActivity(intent)
    }

    /**
     * 更新当前播放进度
     * 应在播放进度变化时定期调用
     */
    fun updatePlaybackPosition(position: Long) {
        playbackPreferences.currentPosition = position
    }

    /**
     * 获取上次保存的播放状态
     */
    fun getLastPlaybackState(): PlaybackState {
        return PlaybackState(
            songId = playbackPreferences.currentSongId,
            position = playbackPreferences.currentPosition,
            isPlaying = playbackPreferences.isPlaying,
            quality = playbackPreferences.currentQuality
        )
    }

    /**
     * 清除播放状态
     */
    fun clearPlaybackState() {
        playbackPreferences.clearPlaybackState()
    }

    /**
     * 播放状态数据类
     */
    data class PlaybackState(
        val songId: String?,
        val position: Long,
        val isPlaying: Boolean,
        val quality: String
    )

    /**
     * 验证音源平台是否可用
     * @param platform 平台名称
     * @return 是否可用
     */
    fun isPlatformValid(platform: String?): Boolean {
        return platform != null && SUPPORTED_PLATFORMS.contains(platform.uppercase())
    }

    /**
     * 获取有效的平台，如果无效则返回默认平台
     * @param platform 平台名称
     * @return 有效的平台
     */
    fun getValidPlatform(platform: String?): MusicRepository.Platform {
        return try {
            if (isPlatformValid(platform)) {
                MusicRepository.Platform.valueOf(platform!!.uppercase())
            } else {
                MusicRepository.Platform.KUWO
            }
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }
    }

    /**
     * 播放歌曲（从最近播放记录）
     * @param context 上下文
     * @param recentPlay 最近播放记录
     */
    suspend fun playFromRecentPlay(
        context: Context,
        recentPlay: RecentPlay
    ): Boolean {
        // 验证平台可用性
        val platform = getValidPlatform(recentPlay.platform)

        // 获取歌曲详情
        val detail = withContext(Dispatchers.IO) {
            repository.getSongDetail(platform, recentPlay.id, "320k")
        }

        return if (detail != null) {
            // 创建播放列表歌曲
            val playlistSong = PlaylistSong(
                id = recentPlay.id,
                name = recentPlay.name,
                artists = recentPlay.artists,
                coverUrl = recentPlay.coverUrl ?: detail.cover,
                platform = platform.name
            )

            // 添加到播放列表并设置为当前播放
            // 不再清空播放列表，保持持久化
            playlistManager.addSong(playlistSong)
            // 设置新添加的歌曲为当前播放索引
            val newIndex = playlistManager.currentPlaylist.value.size - 1
            playlistManager.setCurrentIndex(newIndex.coerceAtLeast(0))

            // 保存播放状态
            savePlaybackState(
                songId = recentPlay.id,
                position = 0L,
                isPlaying = true,
                quality = "320k",
                songDetail = detail
            )

            // 启动播放页面
            startPlayerActivity(
                context = context,
                songId = recentPlay.id,
                songName = recentPlay.name,
                songArtists = recentPlay.artists,
                platform = platform,
                songUrl = detail.url,
                songCover = detail.cover ?: recentPlay.coverUrl,
                songLyrics = detail.lyrics
            )
            true
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "获取歌曲信息失败，可能音源不可用", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    /**
     * 播放歌曲（从下载历史）- 带平台信息版本
     * @param context 上下文
     * @param songId 歌曲ID
     * @param songName 歌曲名称
     * @param artist 艺术家
     * @param coverUrl 封面URL
     * @param playUrl 播放URL（本地文件路径）
     * @param platform 音源平台
     */
    suspend fun playFromDownloadWithPlatform(
        context: Context,
        songId: String,
        songName: String,
        artist: String,
        coverUrl: String?,
        playUrl: String,
        platform: String
    ): Boolean {
        // 验证平台
        val validPlatform = getValidPlatform(platform)

        // 本地文件直接播放，不需要获取详情
        val playlistSong = PlaylistSong(
            id = songId,
            name = songName,
            artists = artist,
            coverUrl = coverUrl ?: "",
            platform = validPlatform.name
        )

        // 添加到播放列表
        playlistManager.addSong(playlistSong)

        // 关键修复：设置当前播放索引为新添加的歌曲
        // 这样播放列表中的"正在播放"图标才能正确显示
        val newIndex = playlistManager.currentPlaylist.value.size - 1
        playlistManager.setCurrentIndex(newIndex.coerceAtLeast(0))

        // 尝试获取歌曲详情（用于歌词和封面）
        val detail = withContext(Dispatchers.IO) {
            try {
                repository.getSongDetail(validPlatform, songId, "320k")
            } catch (e: Exception) {
                null
            }
        }

        // 保存播放状态
        savePlaybackState(
            songId = songId,
            position = 0L,
            isPlaying = true,
            quality = "320k",
            songDetail = detail
        )

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = songId,
            songName = songName,
            songArtists = artist,
            platform = validPlatform,
            songUrl = playUrl,
            songCover = detail?.cover ?: coverUrl,
            songLyrics = detail?.lyrics
        )
        return true
    }
}
