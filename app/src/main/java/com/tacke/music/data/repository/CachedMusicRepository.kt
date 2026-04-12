package com.tacke.music.data.repository

import android.content.Context
import android.util.Log
import com.tacke.music.data.model.SongDetail
import com.tacke.music.ui.CacheManageActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 带缓存功能的音乐仓库
 * 优先从本地缓存加载歌曲详情，如果没有缓存则从网络获取并保存到本地
 */
class CachedMusicRepository(private val context: Context) {

    private val musicRepository = MusicRepository()
    private val songDetailRepository = SongDetailRepository(context)

    companion object {
        private const val TAG = "CachedMusicRepository"
    }

    /**
     * 获取缓存过期时间（天数）
     */
    private fun getCacheExpiryDays(): Int {
        return CacheManageActivity.getCacheExpiryDays(context)
    }

    /**
     * 获取歌曲详情
     * 优先从本地缓存获取，如果没有缓存或缓存已过期则从网络获取
     *
     * @param platform 音乐平台
     * @param songId 歌曲ID
     * @param quality 音质
     * @param coverUrlFromSearch 从搜索获取的封面URL（酷我平台的web_albumpic_short字段）
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
        Log.d(TAG, "从网络获取歌曲详情: $songId, platform=$platform, coverUrlFromSearch=$coverUrlFromSearch")
        
        // 获取歌曲URL和歌词（使用双重数据源机制）
        val detail = musicRepository.getSongDetail(platform, songId, quality, coverUrlFromSearch, songName, artists)
        
        if (detail != null) {
            // 处理封面URL：如果网络请求没有返回封面，根据平台使用不同的获取逻辑
            val finalCoverUrl = if (detail.cover.isNullOrEmpty()) {
                Log.d(TAG, "网络请求未返回封面，尝试根据平台获取: $songId, platform=$platform")
                when (platform) {
                    MusicRepository.Platform.KUWO -> {
                        // 酷我平台：使用coverUrlFromSearch（web_albumpic_short）获取封面
                        if (!coverUrlFromSearch.isNullOrEmpty()) {
                            musicRepository.getKuwoCoverByAlbumPic(coverUrlFromSearch)
                        } else null
                    }
                    MusicRepository.Platform.NETEASE -> {
                        // 网易云：使用"歌名+歌手"搜索获取封面
                        if (songName.isNotEmpty() && artists.isNotEmpty()) {
                            musicRepository.getCoverUrlFromNetease(songName, artists)
                        } else null
                    }
                }
            } else {
                detail.cover
            }
            
            val finalDetail = detail.copy(cover = finalCoverUrl)
            
            // 保存到本地缓存
            if (songName.isNotEmpty()) {
                try {
                    songDetailRepository.saveSongDetail(
                        songId = songId,
                        name = songName,
                        artists = artists,
                        platform = platform.name,
                        songDetail = finalDetail,
                        quality = quality
                    )
                    Log.d(TAG, "保存歌曲详情到本地缓存: $songId, coverUrl=$finalCoverUrl")
                } catch (e: Exception) {
                    Log.e(TAG, "保存歌曲详情到本地缓存失败: $songId", e)
                }
            }
            
            return@withContext finalDetail
        }

        return@withContext null
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
        songDetailRepository.cleanOldCache(getCacheExpiryDays())
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
     * @param coverUrlFromSearch 从搜索获取的封面URL（酷我平台的web_albumpic_short字段）
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
        Log.d(TAG, "getSongUrlWithCache 请求音质: $quality, songId=$songId")
        // 先尝试从缓存获取封面和歌词（如果允许使用缓存）
        var cachedCover: String? = null
        var cachedLyrics: String? = null

        if (useCache) {
            val cachedDetail = songDetailRepository.getSongDetail(songId)
            if (cachedDetail != null) {
                cachedCover = cachedDetail.cover
                cachedLyrics = cachedDetail.lyrics
                // 检查缓存的音质是否与请求的音质匹配
                val cachedQuality = songDetailRepository.getSongQuality(songId)
                if (cachedQuality == quality) {
                    Log.d(TAG, "缓存音质匹配，直接返回缓存数据: $songId, quality=$quality")
                    // 音质匹配，直接返回缓存数据
                    return@withContext cachedDetail
                } else {
                    Log.d(TAG, "缓存音质不匹配，需要重新获取: $songId, cached=$cachedQuality, requested=$quality")
                    // 音质不匹配，继续使用缓存的封面和歌词，但需要从网络获取新URL
                }
            }
        }

        // 强制从网络获取最新的URL（使用双重数据源机制）
        Log.d(TAG, "从网络获取最新播放URL: $songId, coverUrlFromSearch=$coverUrlFromSearch")
        val freshDetail = musicRepository.getSongDetail(platform, songId, quality, coverUrlFromSearch, songName, artists)

        if (freshDetail != null) {
            // 处理封面URL：如果网络请求没有返回封面，根据平台使用不同的获取逻辑
            val finalCoverUrl = if (freshDetail.cover.isNullOrEmpty()) {
                Log.d(TAG, "网络请求未返回封面，尝试根据平台获取: $songId, platform=$platform")
                when (platform) {
                    MusicRepository.Platform.KUWO -> {
                        // 酷我平台：使用coverUrlFromSearch（web_albumpic_short）获取封面
                        if (!coverUrlFromSearch.isNullOrEmpty()) {
                            musicRepository.getKuwoCoverByAlbumPic(coverUrlFromSearch)
                        } else null
                    }
                    MusicRepository.Platform.NETEASE -> {
                        // 网易云：使用"歌名+歌手"搜索获取封面
                        if (songName.isNotEmpty() && artists.isNotEmpty()) {
                            musicRepository.getCoverUrlFromNetease(songName, artists)
                        } else null
                    }
                }
            } else {
                freshDetail.cover
            }
            
            // 合并数据：使用最新的URL和封面，但保留缓存的歌词（如果网络请求没有返回）
            val mergedDetail = freshDetail.copy(
                cover = finalCoverUrl,
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
                    Log.d(TAG, "保存合并后的歌曲详情到本地缓存: $songId, coverUrl=$finalCoverUrl")
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
     * @param songName 歌曲名称（用于备用数据源搜索）
     * @param artists 艺术家（用于备用数据源搜索）
     * @return 仅包含URL的歌曲详情（cover和lyrics可能为空）
     */
    suspend fun getSongUrlOnly(
        platform: MusicRepository.Platform,
        songId: String,
        quality: String = "320k",
        songName: String = "",
        artists: String = ""
    ): SongDetail? = withContext(Dispatchers.IO) {
        // 强制从网络获取最新的URL，不传递封面URL以减少请求时间
        Log.d(TAG, "快速获取播放URL: $songId")
        val freshDetail = musicRepository.getSongDetail(platform, songId, quality, null, songName, artists)
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
     * 仅从本地缓存读取完整歌曲详情（不触发任何网络请求）
     */
    suspend fun getLocalSongDetail(songId: String): SongDetail? = withContext(Dispatchers.IO) {
        val cachedDetail = songDetailRepository.getSongDetail(songId)
        if (cachedDetail != null) {
            Log.d(TAG, "命中本地完整缓存（直放）: $songId")
        }
        cachedDetail
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
