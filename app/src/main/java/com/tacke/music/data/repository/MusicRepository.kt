package com.tacke.music.data.repository

import android.util.Log
import com.tacke.music.data.api.ParseRequest
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
                    // 酷我字段全是全大写
                    val name = item.optString("SONGNAME", item.optString("NAME", "未知"))
                    val artist = item.optString("ARTIST", "未知")
                    val id = item.optString("DC_TARGETID", "")

                    songs.add(Song(
                        index = page * PAGE_SIZE + i + 1,
                        id = id,
                        name = name,
                        artists = artist,
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
                    platform = platformStr
                )
            }
        } catch (e: Exception) {
            Log.e("MusicRepository", "Netease search failed", e)
            emptyList()
        }
    }
    
    suspend fun getSongDetail(platform: Platform, songId: String, quality: String): SongDetail? {
        return try {
            val platformStr = when (platform) {
                Platform.KUWO -> "kuwo"
                Platform.NETEASE -> "netease"
            }

            val request = ParseRequest(
                platform = platformStr,
                ids = songId,
                quality = quality
            )

            val response = RetrofitClient.tunefreeApi.parseSong(request)

            if (response.code != 0 || !response.success) return null

            val songData = response.data?.data?.firstOrNull() ?: return null

            SongDetail(
                url = songData.url,
                info = SongInfo(
                    name = songData.info?.name ?: "未知",
                    artist = songData.info?.artist ?: "未知"
                ),
                cover = songData.cover,
                lyrics = songData.lyrics
            )
        } catch (e: Exception) {
            Log.e("MusicRepository", "Get song detail failed", e)
            null
        }
    }
}
