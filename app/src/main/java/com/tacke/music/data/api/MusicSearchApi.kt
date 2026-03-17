package com.tacke.music.data.api

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * 音乐搜索API接口
 * 用于搜索匹配本地歌曲的在线信息
 */
interface MusicSearchApi {

    /**
     * 搜索歌曲
     * @param types 类型：search
     * @param name 搜索关键词（歌曲名）
     * @param pages 页码
     */
    @GET("api.php")
    suspend fun searchSongs(
        @Query("types") types: String = "search",
        @Query("name") name: String,
        @Query("pages") pages: Int = 1
    ): List<SearchResultItem>

    /**
     * 获取歌曲封面
     * @param types 类型：pic
     * @param source 音乐源
     * @param id 封面ID (pic_id)
     * @param size 图片尺寸
     */
    @GET("api.php")
    suspend fun getSongPic(
        @Query("types") types: String = "pic",
        @Query("source") source: String,
        @Query("id") id: String,
        @Query("size") size: String = "500"
    ): PicResponse

    /**
     * 获取歌词
     * @param types 类型：lyric
     * @param source 音乐源
     * @param id 歌词ID (lyric_id)
     */
    @GET("api.php")
    suspend fun getSongLyric(
        @Query("types") types: String = "lyric",
        @Query("source") source: String,
        @Query("id") id: String
    ): LyricResponse
}

/**
 * 搜索结果项
 */
data class SearchResultItem(
    @SerializedName("id") val id: String?,  // 歌曲ID
    @SerializedName("name") val name: String?,  // 歌曲名
    @SerializedName("artist") private val artistRaw: JsonElement?,  // 艺人（字符串或数组）
    @SerializedName("album") val album: String?,  // 专辑
    @SerializedName("pic_id") val picId: String?,  // 封面ID
    @SerializedName("lyric_id") val lyricId: String?,  // 歌词ID
    @SerializedName("source") val source: String?,  // 音乐源
    @SerializedName("url_id") val urlId: String?,  // URL ID
    @SerializedName("duration") val duration: Int?,  // 时长(秒)
    @SerializedName("pic_url") val picUrl: String?  // 封面URL（如果有）
) {
    val artist: String
        get() {
            val raw = artistRaw ?: return ""
            if (raw.isJsonNull) return ""
            if (raw.isJsonPrimitive) return raw.asString
            if (raw.isJsonArray) {
                return raw.asJsonArray.mapNotNull { item ->
                    when {
                        item.isJsonPrimitive -> item.asString
                        item.isJsonObject && item.asJsonObject.has("name") -> item.asJsonObject.get("name")?.asString
                        else -> null
                    }
                }.filter { it.isNotBlank() }
                    .joinToString("、")
            }
            return ""
        }
}

/**
 * 封面响应
 */
data class PicResponse(
    @SerializedName("code") val code: Int?,
    @SerializedName("msg") val msg: String?,
    @SerializedName("url") val url: String?  // 封面URL
)

/**
 * 歌词响应
 */
data class LyricResponse(
    @SerializedName("code") val code: Int?,
    @SerializedName("msg") val msg: String?,
    @SerializedName("lyric") val lyric: String?,  // 原歌词
    @SerializedName("tlyric") val tlyric: String?  // 翻译歌词
)
