package com.tacke.music.data.repository

import android.util.Log
import com.tacke.music.data.api.RetrofitClient
import com.tacke.music.data.model.*
import com.tacke.music.utils.EApiCrypto
import org.json.JSONArray
import org.json.JSONObject

class MusicRepository {

    enum class Platform {
        KUWO,
        NETEASE
    }

    companion object {
        const val PAGE_SIZE = 30
    }

    suspend fun searchMusic(platform: Platform, keyword: String, page: Int = 0): List<Song> {
        val platformStr = when (platform) {
            Platform.KUWO -> "KUWO"
            Platform.NETEASE -> "NETEASE"
        }
        return when (platform) {
            Platform.KUWO -> searchKuwoMusic(keyword, page, platformStr)
            Platform.NETEASE -> searchNeteaseMusic(keyword, page, platformStr)
        }
    }

    suspend fun searchNeteaseMusicForCover(songName: String, artistName: String): String? {
        return try {
            val keyword = "$songName $artistName"
            val apiPath = "/api/cloudsearch/pc"
            val data = JSONObject().apply {
                put("s", keyword)
                put("type", 1)
                put("limit", 1)
                put("total", true)
                put("offset", 0)
            }

            val encryptedParams = EApiCrypto.encrypt(apiPath, data)
            val response = RetrofitClient.neteaseApi.searchMusic(params = encryptedParams)

            if (response.code != 200) return null

            val songs = response.result?.songs ?: return null
            songs.firstOrNull()?.al?.picUrl
        } catch (e: Exception) {
            Log.e("MusicRepository", "Search netease for cover failed", e)
            null
        }
    }

    suspend fun getCoverUrlFromNetease(songName: String, artistName: String): String? {
        return try {
            searchNeteaseMusicForCover(songName, artistName)
        } catch (e: Exception) {
            Log.e("MusicRepository", "Get cover from netease failed", e)
            null
        }
    }

    /**
     * 根据酷我的web_albumpic_short字段获取封面URL
     * 直接将web_albumpic_short传递给GdStudio API获取真实图片链接
     * @param albumPicShort 酷我平台的web_albumpic_short字段值
     * @return 完整的封面URL
     */
    suspend fun getKuwoCoverByAlbumPic(albumPicShort: String): String? {
        if (albumPicShort.isEmpty()) return null
        
        return try {
            Log.d("MusicRepository", "使用web_albumpic_short获取酷我封面: $albumPicShort")
            
            // 如果已经是完整URL或本地文件路径，直接返回
            if (albumPicShort.startsWith("http://", ignoreCase = true) || 
                albumPicShort.startsWith("https://", ignoreCase = true) ||
                albumPicShort.startsWith("/")) {
                return albumPicShort
            }
            
            // 使用GdStudio API获取封面，直接将web_albumpic_short作为id参数
            val picResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                source = "kuwo",
                id = albumPicShort,
                size = "500"
            )
            
            if (!picResponse.url.isNullOrEmpty()) {
                Log.d("MusicRepository", "获取到酷我封面URL: ${picResponse.url}")
                picResponse.url
            } else {
                // 尝试300尺寸
                val fallbackPicResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                    source = "kuwo",
                    id = albumPicShort,
                    size = "300"
                )
                if (!fallbackPicResponse.url.isNullOrEmpty()) {
                    Log.d("MusicRepository", "获取到酷我封面URL(300): ${fallbackPicResponse.url}")
                }
                fallbackPicResponse.url
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "使用web_albumpic_short获取酷我封面失败: $albumPicShort", e)
            null
        }
    }

    private suspend fun searchKuwoMusic(keyword: String, page: Int = 0, platformStr: String): List<Song> {
        return try {
            val responseBody = RetrofitClient.kuwoApi.searchMusic(
                keywords = keyword,
                pageIndex = page,
                pageSize = PAGE_SIZE
            )
            val rawString = responseBody.string()
            Log.d("MusicRepository", "Raw Kuwo Response: $rawString")

            // 提取 JSON 内容：寻找第一个 '{' 和最后一个 '}'
            val startIndex = rawString.indexOf('{')
            val endIndex = rawString.lastIndexOf('}')

            if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
                Log.e("MusicRepository", "No valid JSON found")
                return emptyList()
            }

            var jsonString = rawString.substring(startIndex, endIndex + 1)

            // 针对酷我可能的双重转义进行预处理
            if (jsonString.startsWith("\"{") && jsonString.endsWith("}\"")) {
                // 如果是被引号包裹的 JSON 字符串，先处理转义再解析
                jsonString = jsonString.substring(1, jsonString.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }

            val songs = mutableListOf<Song>()
            val rootObj = JSONObject(jsonString)
            if (rootObj.has("abslist")) {
                val abslist: JSONArray = rootObj.getJSONArray("abslist")
                for (i in 0 until abslist.length()) {
                    val item = abslist.getJSONObject(i)
                    val name = item.optString("SONGNAME", item.optString("NAME", "未知"))
                    val artist = item.optString("ARTIST", "未知")
                    val id = item.optString("DC_TARGETID", "")
                    var coverUrl = item.optString("web_albumpic_short", "")
                    if (coverUrl.isEmpty()) {
                        coverUrl = item.optString("WEB_ALBUMPIC_SHORT", "")
                    }
                    Log.d("MusicRepository", "Kuwo song: name=$name, id=$id, web_albumpic_short=$coverUrl")

                    songs.add(Song(
                        index = page * PAGE_SIZE + i + 1,
                        id = id,
                        name = name,
                        artists = artist,
                        coverUrl = coverUrl,
                        platform = platformStr
                    ))
                }
            }
            Log.d("MusicRepository", "Parsed ${songs.size} songs from Kuwo")
            songs
        } catch (e: Exception) {
            Log.e("MusicRepository", "Kuwo search failed: ${e.message}", e)
            emptyList()
        }
    }

    private suspend fun searchNeteaseMusic(keyword: String, page: Int = 0, platformStr: String): List<Song> {
        return try {
            val apiPath = "/api/cloudsearch/pc"
            val data = JSONObject().apply {
                put("s", keyword)
                put("type", 1)
                put("limit", PAGE_SIZE)
                put("total", true)
                put("offset", page * PAGE_SIZE)
            }

            val encryptedParams = EApiCrypto.encrypt(apiPath, data)
            val response = RetrofitClient.neteaseApi.searchMusic(params = encryptedParams)

            if (response.code != 200) return emptyList()

            val songs = response.result?.songs ?: return emptyList()

            songs.mapIndexed { index, song ->
                val artistNames = song.ar?.map { it.name ?: "未知" }?.joinToString("、") ?: "未知"
                Song(
                    index = page * PAGE_SIZE + index + 1,
                    id = song.id.toString(),
                    name = song.name ?: "未知",
                    artists = artistNames,
                    coverUrl = song.al?.picUrl ?: "",
                    platform = platformStr
                )
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Netease search failed", e)
            emptyList()
        }
    }
    
    suspend fun getSongDetail(platform: Platform, songId: String, quality: String, coverUrlFromSearch: String? = null, songName: String = "", artists: String = ""): SongDetail? {
        val platformStr = when (platform) {
            Platform.KUWO -> "kuwo"
            Platform.NETEASE -> "netease"
        }

        // 双重数据源获取机制
        return try {
            getDualDataSourceSongDetail(platformStr, platform, songId, quality, coverUrlFromSearch, songName, artists)
        } catch (e: Exception) {
            Log.e("MusicRepository", "Get song detail failed in dual data source mechanism", e)
            null
        }
    }

    /**
     * 从 GdStudio API 获取歌曲详情（带重试机制）
     */
    private suspend fun getSongDetailFromGdStudio(
        platformStr: String,
        songId: String,
        quality: String,
        coverUrlFromSearch: String?
    ): SongDetail? {
        val br = when (quality) {
            "flac24bit" -> "999"
            "flac" -> "740"
            "320k" -> "320"
            "192k" -> "192"
            "128k" -> "128"
            else -> "999"
        }

        // 网易平台的音质参数可能需要调整
        val effectiveBr = if (platformStr == "netease" && br == "740") {
            // 网易平台可能不支持 740，使用 320 代替
            "320"
        } else {
            br
        }

        Log.d("MusicRepository", "Getting song URL from GdStudio: platform=$platformStr, songId=$songId, br=$effectiveBr")

        // 带重试机制获取歌曲URL
        val urlResponse = retryWithBackoff(
            times = 3,
            initialDelay = 500L,
            maxDelay = 3000L
        ) {
            RetrofitClient.gdStudioApi.getSongUrl(
                source = platformStr,
                id = songId,
                br = effectiveBr
            )
        }

        if (urlResponse == null) {
            Log.e("MusicRepository", "Failed to get song url after retries: platform=$platformStr, songId=$songId")
            return null
        }

        Log.d("MusicRepository", "Song URL response: ${urlResponse.url}")

        if (urlResponse.url.isNullOrEmpty()) {
            Log.e("MusicRepository", "Failed to get song url: platform=$platformStr, songId=$songId, response=${urlResponse.url}")
            return null
        }

        // 检查URL是否有效（不是HTML错误页面）
        if (urlResponse.url.contains("<html") || urlResponse.url.contains("<!DOCTYPE")) {
            Log.e("MusicRepository", "Invalid song URL (HTML response): platform=$platformStr, songId=$songId")
            return null
        }

        // 带重试机制获取歌词
        val lyricResponse = retryWithBackoff(
            times = 2,
            initialDelay = 300L,
            maxDelay = 1500L
        ) {
            RetrofitClient.gdStudioApi.getLyric(
                source = platformStr,
                id = songId
            )
        }

        Log.d("MusicRepository", "getSongDetail: platform=$platformStr, songId=$songId, coverUrlFromSearch=$coverUrlFromSearch")

        // 获取封面URL - 简化逻辑，只尝试使用coverUrlFromSearch获取
        // 详细的平台特定逻辑在CachedMusicRepository中处理
        val coverUrl = if (!coverUrlFromSearch.isNullOrEmpty()) {
            if (coverUrlFromSearch.startsWith("http://") || coverUrlFromSearch.startsWith("https://")) {
                // 已经是完整URL，直接使用
                coverUrlFromSearch
            } else {
                // 相对路径（如酷我的 web_albumpic_short），尝试使用GdStudio API获取
                retryWithBackoff(
                    times = 2,
                    initialDelay = 300L,
                    maxDelay = 1500L
                ) {
                    Log.d("MusicRepository", "尝试使用web_albumpic_short获取封面: $coverUrlFromSearch")
                    val picResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                        source = platformStr,
                        id = coverUrlFromSearch,
                        size = "500"
                    )
                    picResponse
                }?.url
            }
        } else {
            // 没有提供封面URL，返回null，由上层根据平台使用不同的获取逻辑
            null
        }

        return SongDetail(
            url = urlResponse.url ?: "",
            info = SongInfo(
                name = "",
                artist = ""
            ),
            cover = coverUrl,
            lyrics = lyricResponse?.lyric
        )
    }

    /**
     * 双重数据源获取机制
     * 当主数据源获取失败时，自动切换到备用数据源
     */
    private suspend fun getDualDataSourceSongDetail(
        platformStr: String,
        platform: Platform,
        songId: String,
        quality: String,
        coverUrlFromSearch: String?,
        songName: String,
        artists: String
    ): SongDetail? {
        Log.d("MusicRepository", "开始双重数据源获取: platform=$platformStr, songId=$songId, songName=$songName, artists=$artists")
        
        val startTime = System.currentTimeMillis()
        
        // 1. 尝试从主数据源（GdStudio API）获取
        val primaryDetail = try {
            Log.d("MusicRepository", "尝试从主数据源获取歌曲信息")
            getSongDetailFromGdStudio(platformStr, songId, quality, coverUrlFromSearch)
        } catch (e: Exception) {
            Log.e("MusicRepository", "主数据源获取失败", e)
            null
        }
        
        // 2. 检查主数据源结果
        if (primaryDetail != null) {
            val hasValidUrl = !primaryDetail.url.isNullOrEmpty() && !primaryDetail.url.contains("<html")
            val hasCover = !primaryDetail.cover.isNullOrEmpty()
            val hasLyrics = !primaryDetail.lyrics.isNullOrEmpty()
            
            Log.d("MusicRepository", "主数据源结果: URL=${if (hasValidUrl) "有效" else "无效"}, 封面=${if (hasCover) "有" else "无"}, 歌词=${if (hasLyrics) "有" else "无"}")
            
            // 如果主数据源获取了所有需要的信息，直接返回
            if (hasValidUrl && hasCover && hasLyrics) {
                Log.d("MusicRepository", "主数据源获取成功，使用主数据源结果")
                return primaryDetail
            }
        } else {
            Log.d("MusicRepository", "主数据源完全失败，准备使用备用数据源")
        }
        
        // 3. 尝试从备用数据源获取
        val fallbackDetail = try {
            Log.d("MusicRepository", "尝试从备用数据源获取歌曲信息")
            getSongDetailFromFallbackSource(platform, songId, quality, songName, artists)
        } catch (e: Exception) {
            Log.e("MusicRepository", "备用数据源获取失败", e)
            null
        }
        
        // 4. 合并结果，优先使用主数据源的数据
        val mergedDetail = if (primaryDetail != null && fallbackDetail != null) {
            Log.d("MusicRepository", "合并主数据源和备用数据源的结果")
            mergeSongDetails(primaryDetail, fallbackDetail)
        } else if (primaryDetail != null) {
            Log.d("MusicRepository", "仅使用主数据源结果")
            primaryDetail
        } else if (fallbackDetail != null) {
            Log.d("MusicRepository", "仅使用备用数据源结果")
            fallbackDetail
        } else {
            Log.e("MusicRepository", "所有数据源都获取失败")
            null
        }
        
        val endTime = System.currentTimeMillis()
        Log.d("MusicRepository", "双重数据源获取完成，耗时 ${endTime - startTime}ms, 结果: ${if (mergedDetail != null) "成功" else "失败"}")
        
        return mergedDetail
    }

    /**
     * 从备用数据源获取歌曲详情
     */
    private suspend fun getSongDetailFromFallbackSource(
        platform: Platform,
        songId: String,
        quality: String,
        songName: String,
        artists: String
    ): SongDetail? {
        try {
            // 检查 songName 和 artists 是否为空，如果为空则尝试从原始平台搜索获取
            var effectiveSongName = songName
            var effectiveArtists = artists
            
            if (effectiveSongName.isBlank() || effectiveArtists.isBlank()) {
                Log.d("MusicRepository", "歌曲名称或歌手为空，尝试从原始平台搜索获取: songName='$effectiveSongName', artists='$effectiveArtists'")
                
                // 尝试从原始平台搜索歌曲ID获取完整信息
                val originalPlatform = platform
                val searchResults = searchMusic(originalPlatform, songId, 0)
                
                if (searchResults.isNotEmpty()) {
                    val foundSong = searchResults.first()
                    if (effectiveSongName.isBlank()) {
                        effectiveSongName = foundSong.name
                        Log.d("MusicRepository", "从原始平台获取到歌曲名称: $effectiveSongName")
                    }
                    if (effectiveArtists.isBlank()) {
                        effectiveArtists = foundSong.artists
                        Log.d("MusicRepository", "从原始平台获取到歌手: $effectiveArtists")
                    }
                } else {
                    Log.e("MusicRepository", "无法从原始平台获取歌曲信息")
                    return null
                }
            }
            
            // 使用标准格式的搜索关键词
            val searchKeyword = "$effectiveSongName $effectiveArtists"
            Log.d("MusicRepository", "备用数据源搜索关键词: $searchKeyword")
            
            // 尝试从另一个平台搜索
            val fallbackPlatform = when (platform) {
                Platform.KUWO -> Platform.NETEASE
                Platform.NETEASE -> Platform.KUWO
            }
            
            Log.d("MusicRepository", "切换到备用平台: $fallbackPlatform")
            
            // 搜索歌曲
            val searchResults = searchMusic(fallbackPlatform, searchKeyword, 0)
            if (searchResults.isEmpty()) {
                Log.e("MusicRepository", "备用平台搜索无结果")
                return null
            }
            
            // 使用搜索结果中的第一首歌
            val fallbackSong = searchResults.first()
            Log.d("MusicRepository", "备用平台搜索结果: ${fallbackSong.name} - ${fallbackSong.artists}, id=${fallbackSong.id}")
            
            // 获取备用歌曲的详情
            val fallbackPlatformStr = when (fallbackPlatform) {
                Platform.KUWO -> "kuwo"
                Platform.NETEASE -> "netease"
            }
            
            val fallbackDetail = getSongDetailFromGdStudio(fallbackPlatformStr, fallbackSong.id, quality, fallbackSong.coverUrl)
            if (fallbackDetail == null) {
                Log.e("MusicRepository", "备用平台获取歌曲详情失败")
                return null
            }
            
            Log.d("MusicRepository", "备用数据源获取成功")
            return fallbackDetail
        } catch (e: Exception) {
            Log.e("MusicRepository", "备用数据源处理失败: ${e.message}", e)
            return null
        }
    }

    /**
     * 合并两个数据源的结果，优先使用主数据源的数据
     */
    private fun mergeSongDetails(primary: SongDetail, fallback: SongDetail): SongDetail {
        val mergedUrl = if (!primary.url.isNullOrEmpty() && !primary.url.contains("<html")) {
            primary.url
        } else {
            fallback.url
        }
        
        val mergedCover = if (!primary.cover.isNullOrEmpty()) {
            primary.cover
        } else {
            fallback.cover
        }
        
        val mergedLyrics = if (!primary.lyrics.isNullOrEmpty()) {
            primary.lyrics
        } else {
            fallback.lyrics
        }
        
        Log.d("MusicRepository", "合并结果 - URL: ${if (mergedUrl == primary.url) "来自主数据源" else "来自备用数据源"}, " +
                "封面: ${if (mergedCover == primary.cover) "来自主数据源" else "来自备用数据源"}, " +
                "歌词: ${if (mergedLyrics == primary.lyrics) "来自主数据源" else "来自备用数据源"}")
        
        return SongDetail(
            url = mergedUrl ?: "",
            info = primary.info,
            cover = mergedCover,
            lyrics = mergedLyrics
        )
    }

    /**
     * 带指数退避的重试机制
     * @param times 重试次数
     * @param initialDelay 初始延迟（毫秒）
     * @param maxDelay 最大延迟（毫秒）
     * @param block 执行的代码块
     * @return 执行结果，如果所有重试都失败则返回null
     */
    private suspend fun <T> retryWithBackoff(
        times: Int,
        initialDelay: Long,
        maxDelay: Long,
        block: suspend () -> T
    ): T? {
        var currentDelay = initialDelay
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                val isLastAttempt = attempt == times - 1
                Log.w("MusicRepository", "Request failed (attempt ${attempt + 1}/$times): ${e.message}")
                if (isLastAttempt) {
                    Log.e("MusicRepository", "All retry attempts failed", e)
                    return null
                }
                // 指数退避
                kotlinx.coroutines.delay(currentDelay)
                currentDelay = (currentDelay * 2).coerceAtMost(maxDelay)
            }
        }
        return null
    }
}
