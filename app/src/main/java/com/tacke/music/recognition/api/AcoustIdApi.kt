package com.tacke.music.recognition.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface AcoustIdApi {

    /**
     * AcoustID lookup API
     * 使用 POST 方法，因为指纹数据很长
     * 文档: https://acoustid.org/webservice
     *
     * @param client API Key
     * @param duration 音频时长（秒）
     * @param fingerprint Chromaprint 指纹（Base64 编码）
     * @param meta 返回的元数据类型
     * @param format 响应格式
     */
    @FormUrlEncoded
    @POST("v2/lookup")
    suspend fun lookup(
        @Field("client") client: String,
        @Field("duration") duration: Int,
        @Field("fingerprint") fingerprint: String,
        @Field("meta") meta: String = "recordings+releaseids+releasegroups+artists+releases+tracks+compress+sources",
        @Field("format") format: String = "json"
    ): AcoustIdResponse
}

/**
 * AcoustID API 响应
 */
data class AcoustIdResponse(
    val status: String,
    val results: List<AcoustIdResult>?,
    val error: AcoustIdError? = null
)

/**
 * API 错误信息
 */
data class AcoustIdError(
    val code: Int,
    val message: String
)

/**
 * 识别结果
 */
data class AcoustIdResult(
    val id: String?,
    val score: Double?,
    val recordings: List<AcoustIdRecording>?
)

/**
 * 录音信息
 */
data class AcoustIdRecording(
    val id: String?,
    val title: String?,
    @SerializedName("artist-credit")
    val artistCredit: List<AcoustIdArtistCredit>?,
    @SerializedName("releasegroups")
    val releaseGroups: List<AcoustIdReleaseGroup>?,
    val releases: List<AcoustIdRelease>?
)

/**
 * 艺术家信息
 */
data class AcoustIdArtistCredit(
    val artist: AcoustIdArtist?,
    val name: String?,
    val joinphrase: String?
)

data class AcoustIdArtist(
    val id: String?,
    val name: String?
)

/**
 * 发行组信息（专辑）
 */
data class AcoustIdReleaseGroup(
    val id: String?,
    val title: String?,
    @SerializedName("primary-type")
    val primaryType: String?,
    @SerializedName("secondary-types")
    val secondaryTypes: List<String>?
)

/**
 * 发行信息
 */
data class AcoustIdRelease(
    val id: String?,
    val title: String?,
    val date: AcoustIdDate?,
    val country: String?,
    val tracks: List<AcoustIdReleaseTrack>?
)

data class AcoustIdDate(
    val year: Int?,
    val month: Int?,
    val day: Int?
)

data class AcoustIdReleaseTrack(
    val id: String?,
    val title: String?,
    val position: Int?
)

/**
 * 识别成功的歌曲信息（简化的结果类）
 */
data class AcoustIdTrack(
    val title: String?,
    val artist: String?,
    val album: String?,
    val coverUrl: String?,
    val score: Double?
)
