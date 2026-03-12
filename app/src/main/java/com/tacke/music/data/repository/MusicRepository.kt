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
    
    suspend fun getSongDetail(platform: Platform, songId: String, quality: String, coverUrlFromSearch: String? = null): SongDetail? {
        return try {
            val platformStr = when (platform) {
                Platform.KUWO -> "kuwo"
                Platform.NETEASE -> "netease"
            }

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

            Log.d("MusicRepository", "Getting song URL: platform=$platformStr, songId=$songId, br=$effectiveBr")
            val urlResponse = RetrofitClient.gdStudioApi.getSongUrl(
                source = platformStr,
                id = songId,
                br = effectiveBr
            )

            Log.d("MusicRepository", "Song URL response: ${urlResponse.url}")

            if (urlResponse.url.isNullOrEmpty()) {
                Log.e("MusicRepository", "Failed to get song url: platform=$platformStr, songId=$songId, response=${urlResponse.url}")
                return null
            }

            val lyricResponse = RetrofitClient.gdStudioApi.getLyric(
                source = platformStr,
                id = songId
            )

            Log.d("MusicRepository", "getSongDetail: platform=$platform, songId=$songId, coverUrlFromSearch=$coverUrlFromSearch")

            // 获取封面URL
            // 优先使用从搜索获取的 web_albumpic_short 值，直接传递给 GdStudio API
            val coverUrl = if (!coverUrlFromSearch.isNullOrEmpty()) {
                if (coverUrlFromSearch.startsWith("http://") || coverUrlFromSearch.startsWith("https://")) {
                    // 已经是完整URL，直接使用
                    coverUrlFromSearch
                } else {
                    // 相对路径（如酷我的 web_albumpic_short），直接传递给 GdStudio API 获取真实图片链接
                    try {
                        Log.d("MusicRepository", "Calling GdStudio getAlbumPic with web_albumpic_short: $coverUrlFromSearch, source: $platformStr")
                        val picResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                            source = platformStr,
                            id = coverUrlFromSearch,
                            size = "500"
                        )
                        Log.d("MusicRepository", "GdStudio getAlbumPic response: ${picResponse.url}")
                        if (picResponse.url.isNullOrEmpty()) {
                            // 尝试300尺寸
                            val fallbackPicResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                                source = platformStr,
                                id = coverUrlFromSearch,
                                size = "300"
                            )
                            fallbackPicResponse.url
                        } else {
                            picResponse.url
                        }
                    } catch (e: Exception) {
                        Log.e("MusicRepository", "Failed to get cover with web_albumpic_short, try 300 size", e)
                        try {
                            val fallbackPicResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                                source = platformStr,
                                id = coverUrlFromSearch,
                                size = "300"
                            )
                            fallbackPicResponse.url
                        } catch (e2: Exception) {
                            Log.e("MusicRepository", "Failed to get cover with web_albumpic_short (300)", e2)
                            null
                        }
                    }
                }
            } else {
                // 没有从搜索获取封面URL，使用歌曲ID获取
                try {
                    Log.d("MusicRepository", "Calling GdStudio getAlbumPic with songId: $songId, source: $platformStr")
                    val picResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                        source = platformStr,
                        id = songId,
                        size = "500"
                    )
                    if (picResponse.url.isNullOrEmpty()) {
                        val fallbackPicResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                            source = platformStr,
                            id = songId,
                            size = "300"
                        )
                        fallbackPicResponse.url
                    } else {
                        picResponse.url
                    }
                } catch (e: Exception) {
                    Log.e("MusicRepository", "Failed to get cover with songId, try 300 size", e)
                    try {
                        val fallbackPicResponse = RetrofitClient.gdStudioApi.getAlbumPic(
                            source = platformStr,
                            id = songId,
                            size = "300"
                        )
                        fallbackPicResponse.url
                    } catch (e2: Exception) {
                        Log.e("MusicRepository", "Failed to get cover with songId (300)", e2)
                        null
                    }
                }
            }

            SongDetail(
                url = urlResponse.url ?: "",
                info = SongInfo(
                    name = "",
                    artist = ""
                ),
                cover = coverUrl,
                lyrics = lyricResponse.lyric
            )
        } catch (e: Exception) {
            Log.e("MusicRepository", "Get song detail failed", e)
            null
        }
    }
}
