package com.tacke.music.utils

import android.content.Context
import android.util.Log
import com.tacke.music.data.api.RetrofitClient
import com.tacke.music.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面URL解析器
 * 处理酷我音乐等平台的相对路径封面URL，转换为完整的可加载URL
 */
object CoverUrlResolver {

    private const val TAG = "CoverUrlResolver"

    /**
     * 检查URL是否为相对路径（需要转换）
     * 相对路径指的是不是完整的 URL，也不是本地文件路径
     */
    fun isRelativePath(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        // 如果以 http:// 或 https:// 开头，是完整 URL
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) return false
        // 如果以 / 开头，是本地文件路径
        if (url.startsWith("/")) return false
        // 其他情况认为是相对路径
        return true
    }

    /**
     * 解析封面URL，将相对路径转换为完整URL
     * @param context 上下文
     * @param coverUrl 原始封面URL（可能是相对路径或完整URL）
     * @param songId 歌曲ID
     * @param platform 平台（kuwo/netease等）
     * @param songName 歌曲名称（用于网易云平台搜索后备方案）
     * @param artist 歌手名称（用于网易云平台搜索后备方案）
     * @return 完整的封面URL，如果转换失败返回null
     */
    suspend fun resolveCoverUrl(
        context: Context,
        coverUrl: String?,
        songId: String,
        platform: String,
        songName: String? = null,
        artist: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (coverUrl.isNullOrEmpty()) {
            return@withContext null
        }

        // 如果已经是完整URL，直接返回
        if (!isRelativePath(coverUrl)) {
            return@withContext coverUrl
        }

        // 尝试从本地缓存获取
        val cachedPath = CoverImageManager.getCoverPath(context, songId, platform)
        if (cachedPath != null) {
            Log.d(TAG, "使用本地缓存的封面: $cachedPath")
            return@withContext cachedPath
        }

        // 相对路径需要转换为完整URL
        val platformStr = platform.lowercase()
        try {
            Log.d(TAG, "转换相对路径为完整URL: $coverUrl, platform: $platformStr")

            // 使用GdStudio API获取完整封面URL
            val picResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                source = platformStr,
                id = coverUrl,
                size = "500"
            )

            if (!picResponse.url.isNullOrEmpty()) {
                Log.d(TAG, "获取到完整封面URL: ${picResponse.url}")
                return@withContext picResponse.url
            }

            // 尝试300尺寸
            val fallbackResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                source = platformStr,
                id = coverUrl,
                size = "300"
            )

            if (!fallbackResponse.url.isNullOrEmpty()) {
                Log.d(TAG, "获取到完整封面URL(300): ${fallbackResponse.url}")
                return@withContext fallbackResponse.url
            }
        } catch (e: Exception) {
            Log.e(TAG, "转换封面URL失败: ${e.message}", e)
        }

        // 注意：酷我平台的歌曲图片获取始终通过 GdStudio API
        // 不直接构建图片URL，而是使用网易云平台搜索作为后备方案

        // 如果API转换失败，尝试使用网易云平台搜索后备方案
        if (!songName.isNullOrEmpty()) {
            Log.w(TAG, "GdStudio API 获取封面失败，尝试使用网易云平台搜索: songName=$songName, artist=$artist")
            val neteaseCoverUrl = getCoverFromNeteaseSearch(songName, artist)
            if (!neteaseCoverUrl.isNullOrEmpty()) {
                return@withContext neteaseCoverUrl
            }
        }

        null
    }
    
    /**
     * 从网易云平台搜索获取专辑图
     * 使用"歌曲+歌手"格式搜索，取返回的第一个歌曲的专辑图
     * @param songName 歌曲名称
     * @param artist 歌手名称
     * @return 专辑图URL，获取失败返回null
     */
    private suspend fun getCoverFromNeteaseSearch(
        songName: String?,
        artist: String?
    ): String? {
        if (songName.isNullOrEmpty()) {
            return null
        }
        
        return try {
            // 构建搜索关键词："歌曲+歌手"
            val keyword = if (!artist.isNullOrEmpty()) {
                "$songName $artist"
            } else {
                songName
            }
            
            Log.d(TAG, "从网易云平台搜索封面: keyword=$keyword")
            
            // 使用 MusicRepository 的搜索方法
            val musicRepository = MusicRepository()
            val searchResults = musicRepository.searchMusic(
                MusicRepository.Platform.NETEASE,
                keyword,
                0
            )
            
            if (searchResults.isNotEmpty()) {
                // 取返回的第一个歌曲的专辑图
                val firstSong = searchResults.first()
                val coverUrl = firstSong.coverUrl
                if (!coverUrl.isNullOrEmpty()) {
                    Log.d(TAG, "从网易云平台获取到封面URL: $coverUrl")
                    return coverUrl
                }
            }
            
            Log.w(TAG, "网易云平台搜索未返回结果: keyword=$keyword")
            null
        } catch (e: Exception) {
            Log.e(TAG, "从网易云平台搜索封面失败: ${e.message}", e)
            null
        }
    }

    /**
     * 批量解析封面URL
     */
    suspend fun resolveCoverUrls(
        context: Context,
        items: List<Triple<String, String?, String>> // Triple<songId, coverUrl, platform>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, String>()
        items.forEach { (songId, coverUrl, platform) ->
            val resolvedUrl = resolveCoverUrl(context, coverUrl, songId, platform)
            if (resolvedUrl != null) {
                results[songId] = resolvedUrl
            }
        }
        results
    }

    /**
     * 获取用于Glide加载的封面URL
     * 如果本地缓存存在则返回本地路径，否则返回网络URL
     * @param context 上下文
     * @param coverUrl 原始封面URL
     * @param songId 歌曲ID
     * @param platform 平台
     * @param songName 歌曲名称（用于网易云平台搜索后备方案）
     * @param artist 歌手名称（用于网易云平台搜索后备方案）
     * @return 完整的封面URL或本地路径
     */
    suspend fun getCoverUrlForGlide(
        context: Context,
        coverUrl: String?,
        songId: String,
        platform: String,
        songName: String? = null,
        artist: String? = null
    ): String? {
        // 首先检查本地缓存
        val cachedPath = CoverImageManager.getCoverPath(context, songId, platform)
        if (cachedPath != null) {
            return cachedPath
        }

        // 否则解析URL
        return resolveCoverUrl(context, coverUrl, songId, platform, songName, artist)
    }
}
