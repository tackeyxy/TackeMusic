package com.tacke.music.recognition.api

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * Doreso/aha-music API 接口
 * 用于听歌识曲功能
 */
interface DoresoApi {

    /**
     * 上传音频文件进行识别
     * @param file 音频文件
     * @return 上传响应，包含music_id
     */
    @Multipart
    @POST("https://api.doreso.com/upload")
    @Headers(
        "accept: */*",
        "accept-language: zh-CN,zh;q=0.9",
        "origin: https://aha-music.com",
        "priority: u=1, i",
        "referer: https://aha-music.com/",
        "sec-ch-ua: \"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"",
        "sec-ch-ua-mobile: ?0",
        "sec-ch-ua-platform: \"Windows\"",
        "sec-fetch-dest: empty",
        "sec-fetch-mode: cors",
        "sec-fetch-site: cross-site"
    )
    suspend fun uploadAudio(
        @Part file: MultipartBody.Part
    ): Response<DoresoUploadResponse>

    /**
     * 获取识别结果
     * @param musicId 上传后获取的音乐ID
     * @return 识别结果
     */
    @GET("https://aha-music.com/api/file/{musicId}")
    @Headers(
        "accept: */*",
        "accept-language: zh-CN,zh;q=0.9",
        "priority: u=1, i",
        "sec-ch-ua: \"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"",
        "sec-ch-ua-mobile: ?0",
        "sec-ch-ua-platform: \"Windows\"",
        "sec-fetch-dest: empty",
        "sec-fetch-mode: cors",
        "sec-fetch-site: same-origin",
        "user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
    )
    suspend fun getRecognitionResult(
        @Path("musicId") musicId: String,
        @Header("referer") referer: String
    ): Response<DoresoRecognitionResponse>
}

/**
 * 上传响应
 */
data class DoresoUploadResponse(
    val data: DoresoUploadData?,
    val message: String?,
    val code: Int?
)

data class DoresoUploadData(
    val id: String?
)

/**
 * 识别结果响应
 */
data class DoresoRecognitionResponse(
    val data: List<DoresoResultItem>?
)

data class DoresoResultItem(
    val results: DoresoResults?
)

data class DoresoResults(
    val music: List<DoresoMusicInfo>?
)

data class DoresoMusicInfo(
    val result: DoresoMusicResult?
)

data class DoresoMusicResult(
    val title: String?,
    val artists: List<DoresoArtist>?
)

data class DoresoArtist(
    val name: String?
)

/**
 * 识别结果数据类
 */
data class DoresoTrack(
    val title: String,
    val artist: String,
    val album: String = "未知专辑",
    val coverUrl: String? = null,
    val score: Double? = null
)
