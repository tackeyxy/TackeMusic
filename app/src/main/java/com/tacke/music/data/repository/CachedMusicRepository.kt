package com.tacke.music.data.repository

import android.content.Context
import android.util.Log
import com.tacke.music.data.model.SongDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 带缓存功能的音乐仓库
 * 优先从本地缓存加载歌曲详情，如果没有缓存则从网络获取并保存到本地
 */
class CachedMusicRepository(context: Context) {

    private val musicRepository = MusicRepository()
    private val songDetailRepository = SongDetailRepository(context)

    companion object {
        private const val TAG = "CachedMusicRepository"
        // 缓存有效期：7天
        const val CACHE_VALIDITY_DAYS = 7
    }

    /**
     * 获取歌曲详情
     * 优先从本地缓存获取，如果没有缓存或缓存已过期则从网络获取
     *
     * @param platform 音乐平台
     * @param songId 歌曲ID
     * @param quality 音质
     * @param coverUrlFromSearch 从搜索获取的封面URL
     * @param songName 歌曲名称（用于保存缓存）
     * @param artists 艺术家（用于保存缓存）
     * @param forceRefresh 是否强制刷新缓存
     * @return 歌曲详情
     */
    suspend fun getSongDetail(
        platform: MusicRepository.Platform,
        songId: String,
        quality: String = "320k",
        coverUrlFromSearch: String? = null,
        songName: String = "",
        artists: String = "",
        forceRefresh: Boolean = false
    ): SongDetail? = withContext(Dispatchers.IO) {
        // 如果不是强制刷新，先尝试从本地缓存获取
        if (!forceRefresh) {
            val cachedDetail = songDetailRepository.getSongDetail(songId)
            if (cachedDetail != null) {
                Log.d(TAG, "从本地缓存获取歌曲详情: $songId")
                return@withContext cachedDetail
            }
        }

        // 从网络获取
        Log.d(TAG, "从网络获取歌曲详情: $songId")
        val detail = musicRepository.getSongDetail(platform, songId, quality, coverUrlFromSearch)

        // 保存到本地缓存
        if (detail != null && songName.isNotEmpty()) {
            try {
                songDetailRepository.saveSongDetail(
                    songId = songId,
                    name = songName,
                    artists = artists,
                    platform = platform.name,
                    songDetail = detail,
                    quality = quality
                )
                Log.d(TAG, "保存歌曲详情到本地缓存: $songId")
            } catch (e: Exception) {
                Log.e(TAG, "保存歌曲详情到本地缓存失败: $songId", e)
            }
        }

        return@withContext detail
    }

    /**
     * 检查本地是否有缓存
     */
    suspend fun hasLocalCache(songId: String): Boolean {
        return songDetailRepository.hasSongDetail(songId)
    }

    /**
     * 清除指定歌曲的缓存
     */
    suspend fun clearCache(songId: String) {
        songDetailRepository.deleteSongDetail(songId)
    }

    /**
     * 清理过期缓存
     */
    suspend fun cleanExpiredCache() {
        songDetailRepository.cleanOldCache(CACHE_VALIDITY_DAYS)
    }

    /**
     * 获取歌曲播放URL（强制从网络获取最新URL）
     * 用于非下载管理页面的播放和下载，确保获取最新的有效URL
     *
     * @param platform 音乐平台
     * @param songId 歌曲ID
     * @param quality 音质
     * @param songName 歌曲名称（用于保存缓存）
     * @param artists 艺术家（用于保存缓存）
     * @param useCache 是否允许使用缓存的封面和歌词
     * @param coverUrlFromSearch 从搜索获取的封面URL（酷我平台需要）
     * @return 包含最新URL的歌曲详情
     */
    suspend fun getSongUrlWithCache(
        platform: MusicRepository.Platform,
        songId: String,
        quality: String = "320k",
        songName: String = "",
        artists: String = "",
        useCache: Boolean = true,
        coverUrlFromSearch: String? = null
    ): SongDetail? = withContext(Dispatchers.IO) {
        // 先尝试从缓存获取封面和歌词（如果允许使用缓存）
        var cachedCover: String? = null
        var cachedLyrics: String? = null

        if (useCache) {
            val cachedDetail = songDetailRepository.getSongDetail(songId)
            if (cachedDetail != null) {
                cachedCover = cachedDetail.cover
                cachedLyrics = cachedDetail.lyrics
                Log.d(TAG, "从缓存获取封面和歌词: $songId")
            }
        }

        // 优先使用从搜索传递过来的封面URL（对于酷我平台的相对路径封面）
        val effectiveCoverUrl = coverUrlFromSearch ?: cachedCover

        // 强制从网络获取最新的URL
        Log.d(TAG, "从网络获取最新播放URL: $songId, coverUrlFromSearch=$coverUrlFromSearch")
        val freshDetail = musicRepository.getSongDetail(platform, songId, quality, effectiveCoverUrl)

        if (freshDetail != null) {
            // 合并数据：使用最新的URL，但保留缓存的封面和歌词（如果网络请求没有返回）
            val mergedDetail = freshDetail.copy(
                cover = freshDetail.cover ?: cachedCover,
                lyrics = freshDetail.lyrics ?: cachedLyrics
            )

            // 保存到本地缓存
            if (songName.isNotEmpty()) {
                try {
                    songDetailRepository.saveSongDetail(
                        songId = songId,
                        name = songName,
                        artists = artists,
                        platform = platform.name,
                        songDetail = mergedDetail,
                        quality = quality
                    )
                    Log.d(TAG, "保存合并后的歌曲详情到本地缓存: $songId")
                } catch (e: Exception) {
                    Log.e(TAG, "保存歌曲详情到本地缓存失败: $songId", e)
                }
            }

            return@withContext mergedDetail
        }

        return@withContext null
    }

    /**
     * 仅获取歌曲播放URL（强制从网络获取，不缓存其他信息）
     * 用于快速播放场景，只获取URL，封面和歌词使用已缓存的数据
     *
     * @param platform 音乐平台
     * @param songId 歌曲ID
     * @param quality 音质
     * @return 仅包含URL的歌曲详情（cover和lyrics可能为空）
     */
    suspend fun getSongUrlOnly(
        platform: MusicRepository.Platform,
        songId: String,
        quality: String = "320k"
    ): SongDetail? = withContext(Dispatchers.IO) {
        // 强制从网络获取最新的URL，不传递封面URL以减少请求时间
        Log.d(TAG, "快速获取播放URL: $songId")
        val freshDetail = musicRepository.getSongDetail(platform, songId, quality, null)
        freshDetail
    }

    /**
     * 获取缓存的封面和歌词（不获取URL）
     * 用于播放页面快速显示已缓存的信息
     *
     * @param songId 歌曲ID
     * @return 包含封面和歌词的缓存数据，如果没有缓存返回null
     */
    suspend fun getCachedCoverAndLyrics(songId: String): SongDetail? = withContext(Dispatchers.IO) {
        val cachedDetail = songDetailRepository.getSongDetail(songId)
        if (cachedDetail != null) {
            Log.d(TAG, "从缓存获取封面和歌词: $songId")
            // 返回一个只有cover和lyrics的SongDetail，url为空
            SongDetail(
                url = "",
                info = cachedDetail.info,
                cover = cachedDetail.cover,
                lyrics = cachedDetail.lyrics
            )
        } else {
            null
        }
    }

    /**
     * 获取搜索音乐方法（透传给MusicRepository）
     */
    suspend fun searchMusic(platform: MusicRepository.Platform, keyword: String, page: Int = 0): List<com.tacke.music.data.model.Song> {
        return musicRepository.searchMusic(platform, keyword, page)
    }

    /**
     * 获取封面URL方法（透传给MusicRepository）
     */
    suspend fun getCoverUrlFromNetease(songName: String, artistName: String): String? {
        return musicRepository.getCoverUrlFromNetease(songName, artistName)
    }
}
