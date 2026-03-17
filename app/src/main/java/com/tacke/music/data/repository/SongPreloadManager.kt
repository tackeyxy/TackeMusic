package com.tacke.music.data.repository

import android.content.Context
import android.util.Log
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.Song
import com.tacke.music.ui.SettingsActivity
import com.tacke.music.utils.CoverImageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌曲预加载管理器
 * 用于在后台预加载歌曲的歌词、封面等信息
 * 当歌曲被添加到播放列表但尚未播放时，自动缓存歌词和封面
 */
class SongPreloadManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val cachedMusicRepository = CachedMusicRepository(appContext)
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "SongPreloadManager"

        @Volatile
        private var instance: SongPreloadManager? = null

        fun getInstance(context: Context): SongPreloadManager {
            return instance ?: synchronized(this) {
                instance ?: SongPreloadManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * 预加载歌曲信息（歌词和封面）
     * 在后台自动获取并缓存，不阻塞主线程
     *
     * @param song 歌曲信息
     * @param platform 音乐平台
     */
    fun preloadSongInfo(song: Song, platform: MusicRepository.Platform) {
        preloadScope.launch {
            try {
                Log.d(TAG, "开始预加载歌曲信息: ${song.name} - ${song.id}")

                // 检查是否已经有缓存
                val hasCache = cachedMusicRepository.hasLocalCache(song.id)
                if (hasCache) {
                    Log.d(TAG, "歌曲已有缓存，跳过预加载: ${song.name}")
                    return@launch
                }

                // 预加载歌曲详情（包含歌词和封面URL）
                // 使用用户设置的听音质进行预加载
                val playbackQuality = SettingsActivity.getPlaybackQuality(appContext)
                val detail = cachedMusicRepository.getSongDetail(
                    platform = platform,
                    songId = song.id,
                    quality = playbackQuality,
                    coverUrlFromSearch = song.coverUrl,
                    songName = song.name,
                    artists = song.artists
                )

                if (detail != null) {
                    Log.d(TAG, "歌曲信息预加载成功: ${song.name}, 封面=${detail.cover != null}, 歌词=${detail.lyrics != null}")

                    // 如果封面URL有效，预下载封面图片到本地缓存
                    if (!detail.cover.isNullOrEmpty()) {
                        preloadCoverImage(song.id, platform.name, detail.cover, song.name, song.artists)
                    }
                } else {
                    Log.w(TAG, "歌曲信息预加载失败: ${song.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "预加载歌曲信息异常: ${song.name}", e)
            }
        }
    }

    /**
     * 预加载播放列表歌曲信息
     *
     * @param song 播放列表歌曲
     */
    fun preloadPlaylistSong(song: PlaylistSong) {
        preloadScope.launch {
            try {
                Log.d(TAG, "开始预加载播放列表歌曲: ${song.name} - ${song.id}")

                val platform = try {
                    MusicRepository.Platform.valueOf(song.platform.uppercase())
                } catch (e: Exception) {
                    MusicRepository.Platform.KUWO
                }

                // 检查是否已经有缓存
                val hasCache = cachedMusicRepository.hasLocalCache(song.id)
                if (hasCache) {
                    Log.d(TAG, "播放列表歌曲已有缓存，跳过预加载: ${song.name}")
                    return@launch
                }

                // 预加载歌曲详情
                // 使用用户设置的听音质进行预加载
                val playbackQuality = SettingsActivity.getPlaybackQuality(appContext)
                val detail = cachedMusicRepository.getSongDetail(
                    platform = platform,
                    songId = song.id,
                    quality = playbackQuality,
                    coverUrlFromSearch = song.coverUrl,
                    songName = song.name,
                    artists = song.artists
                )

                if (detail != null) {
                    Log.d(TAG, "播放列表歌曲预加载成功: ${song.name}")

                    // 预下载封面图片
                    if (!detail.cover.isNullOrEmpty()) {
                        preloadCoverImage(song.id, song.platform, detail.cover, song.name, song.artists)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "预加载播放列表歌曲异常: ${song.name}", e)
            }
        }
    }

    /**
     * 预加载封面图片到本地缓存
     */
    private suspend fun preloadCoverImage(
        songId: String,
        platform: String,
        coverUrl: String,
        songName: String?,
        artist: String?
    ) {
        try {
            // 检查本地是否已有缓存
            val existingPath = CoverImageManager.getCoverPath(appContext, songId, platform)
            if (existingPath != null) {
                Log.d(TAG, "封面图片已缓存: $songId")
                return
            }

            // 下载并缓存封面
            val localPath = CoverImageManager.downloadAndCacheCover(
                context = appContext,
                songId = songId,
                platform = platform,
                quality = "320k",
                songName = songName,
                artist = artist
            )

            if (localPath != null) {
                Log.d(TAG, "封面图片预下载成功: $songId -> $localPath")
            } else {
                Log.w(TAG, "封面图片预下载失败: $songId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "预下载封面图片异常: $songId", e)
        }
    }

    /**
     * 批量预加载歌曲列表
     * 用于歌单页面加载时预加载所有歌曲信息
     *
     * @param songs 歌曲列表
     * @param platform 音乐平台
     */
    fun preloadSongsBatch(songs: List<Song>, platform: MusicRepository.Platform) {
        preloadScope.launch {
            Log.d(TAG, "开始批量预加载 ${songs.size} 首歌曲")
            songs.forEachIndexed { index, song ->
                // 延迟加载，避免同时发起过多请求
                if (index > 0) {
                    kotlinx.coroutines.delay(100)
                }
                preloadSongInfo(song, platform)
            }
            Log.d(TAG, "批量预加载完成")
        }
    }

    /**
     * 批量预加载播放列表歌曲
     */
    fun preloadPlaylistSongsBatch(songs: List<PlaylistSong>) {
        preloadScope.launch {
            Log.d(TAG, "开始批量预加载 ${songs.size} 首播放列表歌曲")
            songs.forEachIndexed { index, song ->
                if (index > 0) {
                    kotlinx.coroutines.delay(100)
                }
                preloadPlaylistSong(song)
            }
            Log.d(TAG, "批量预加载播放列表完成")
        }
    }

    /**
     * 预加载本地音乐信息（封面和歌词）
     * 优先从本地数据库获取，如果没有则通过API获取
     *
     * @param localMusic 本地音乐信息
     * @param platform 音乐平台
     */
    fun preloadLocalMusicInfo(
        localMusic: com.tacke.music.ui.LocalMusic,
        platform: MusicRepository.Platform
    ) {
        preloadScope.launch {
            try {
                Log.d(TAG, "开始预加载本地音乐信息: ${localMusic.title}")

                // 使用LocalMusicInfoRepository获取本地音乐信息（带缓存）
                val localMusicInfoRepository = LocalMusicInfoRepository(appContext)
                val musicInfo = localMusicInfoRepository.getLocalMusicInfo(localMusic)

                if (musicInfo != null) {
                    Log.d(TAG, "本地音乐信息获取成功: ${localMusic.title}, 封面=${musicInfo.coverUrl != null}, 歌词=${musicInfo.lyrics != null}")

                    // 如果封面URL有效，预下载封面图片到本地缓存
                    if (!musicInfo.coverUrl.isNullOrEmpty()) {
                        preloadCoverImage(
                            songId = "local_${localMusic.path.hashCode()}",
                            platform = "LOCAL",
                            coverUrl = musicInfo.coverUrl,
                            songName = localMusic.title,
                            artist = localMusic.artist
                        )
                    }
                } else {
                    Log.w(TAG, "本地音乐信息获取失败: ${localMusic.title}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "预加载本地音乐信息异常: ${localMusic.title}", e)
            }
        }
    }
}
