package com.tacke.music.recognition

import android.content.Context
import android.util.Log
import com.tacke.music.data.api.RetrofitClient
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.recognition.api.AcoustIdTrack
import com.tacke.music.recognition.api.ShazamTrack
import com.tacke.music.utils.EApiCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MusicSearchService(private val context: Context) {

    private val repository = MusicRepository()

    data class SearchResult(
        val shazamTrack: ShazamTrack?,
        val acoustIdTrack: AcoustIdTrack?,
        val kuwoSong: Song?,
        val neteaseSong: Song?
    )

    data class SimpleSearchResult(
        val kuwoSong: Song?,
        val neteaseSong: Song?
    )

    /**
     * 根据 Shazam 结果搜索
     */
    suspend fun searchRecognizedSong(track: ShazamTrack): SearchResult = withContext(Dispatchers.IO) {
        val title = track.title ?: ""
        val artist = track.subtitle ?: ""
        val searchQuery = "$title $artist".trim()

        Log.d("MusicSearchService", "Shazam 搜索关键词: $searchQuery")

        coroutineScope {
            val kuwoDeferred = async { searchKuwo(searchQuery) }
            val neteaseDeferred = async { searchNetease(searchQuery) }

            val kuwoResult = kuwoDeferred.await()
            val neteaseResult = neteaseDeferred.await()

            SearchResult(
                shazamTrack = track,
                acoustIdTrack = null,
                kuwoSong = kuwoResult,
                neteaseSong = neteaseResult
            )
        }
    }

    /**
     * 根据 AcoustID 结果搜索
     */
    suspend fun searchRecognizedSong(track: AcoustIdTrack): SearchResult = withContext(Dispatchers.IO) {
        val title = track.title ?: ""
        val artist = track.artist ?: ""
        val searchQuery = "$title $artist".trim()

        Log.d("MusicSearchService", "AcoustID 搜索关键词: $searchQuery")

        coroutineScope {
            val kuwoDeferred = async { searchKuwo(searchQuery) }
            val neteaseDeferred = async { searchNetease(searchQuery) }

            val kuwoResult = kuwoDeferred.await()
            val neteaseResult = neteaseDeferred.await()

            SearchResult(
                shazamTrack = null,
                acoustIdTrack = track,
                kuwoSong = kuwoResult,
                neteaseSong = neteaseResult
            )
        }
    }

    private suspend fun searchKuwo(query: String): Song? {
        return try {
            val responseBody = RetrofitClient.kuwoApi.searchMusic(
                keywords = query,
                pageIndex = 0,
                pageSize = 5
            )
            val rawString = responseBody.string()

            // 提取 JSON 内容
            val startIndex = rawString.indexOf('{')
            val endIndex = rawString.lastIndexOf('}')

            if (startIndex == -1 || endIndex == -1) {
                return null
            }

            var jsonString = rawString.substring(startIndex, endIndex + 1)
            if (jsonString.startsWith("\"{") && jsonString.endsWith("}\"")) {
                jsonString = jsonString.substring(1, jsonString.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }

            val rootObj = org.json.JSONObject(jsonString)
            if (!rootObj.has("abslist")) return null

            val abslist = rootObj.getJSONArray("abslist")
            if (abslist.length() == 0) return null

            val item = abslist.getJSONObject(0)
            val name = item.optString("SONGNAME", item.optString("NAME", "未知"))
            val artist = item.optString("ARTIST", "未知")
            val id = item.optString("DC_TARGETID", "")
            var coverUrl = item.optString("web_albumpic_short", "")
            if (coverUrl.isEmpty()) {
                coverUrl = item.optString("WEB_ALBUMPIC_SHORT", "")
            }

            Song(
                index = 1,
                id = id,
                name = name,
                artists = artist,
                coverUrl = coverUrl,
                platform = "KUWO"
            )
        } catch (e: Exception) {
            Log.e("MusicSearchService", "酷我搜索失败", e)
            null
        }
    }

    private suspend fun searchNetease(query: String): Song? {
        return try {
            val apiPath = "/api/cloudsearch/pc"
            val data = JSONObject().apply {
                put("s", query)
                put("type", 1)
                put("limit", 5)
                put("total", true)
                put("offset", 0)
            }

            val encryptedParams = EApiCrypto.encrypt(apiPath, data)
            val response = RetrofitClient.neteaseApi.searchMusic(params = encryptedParams)

            if (response.code != 200) return null

            val songs = response.result?.songs
            if (songs.isNullOrEmpty()) return null

            val song = songs.first()
            val artistNames = song.ar?.map { it.name ?: "未知" }?.joinToString("。") ?: "未知"

            Song(
                index = 1,
                id = song.id.toString(),
                name = song.name ?: "未知",
                artists = artistNames,
                coverUrl = song.al?.picUrl ?: "",
                platform = "NETEASE"
            )
        } catch (e: Exception) {
            Log.e("MusicSearchService", "网易云搜索失败", e)
            null
        }
    }

    /**
     * 根据查询字符串搜索歌曲
     */
    suspend fun searchByQuery(query: String): SimpleSearchResult = withContext(Dispatchers.IO) {
        Log.d("MusicSearchService", "搜索关键词: $query")

        coroutineScope {
            val kuwoDeferred = async { searchKuwo(query) }
            val neteaseDeferred = async { searchNetease(query) }

            val kuwoResult = kuwoDeferred.await()
            val neteaseResult = neteaseDeferred.await()

            SimpleSearchResult(
                kuwoSong = kuwoResult,
                neteaseSong = neteaseResult
            )
        }
    }
}
