package com.tacke.music.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface GdStudioApi {

    @GET("api.php")
    suspend fun getSongUrl(
        @Query("types") types: String = "url",
        @Query("source") source: String,
        @Query("id") id: String,
        @Query("br") br: String = "999"
    ): GdStudioUrlResponse

    @GET("api.php")
    suspend fun getAlbumPic(
        @Query("types") types: String = "pic",
        @Query("source") source: String,
        @Query("id") id: String,
        @Query("size") size: String = "500"
    ): GdStudioPicResponse

    @GET("api.php")
    suspend fun getLyric(
        @Query("types") types: String = "lyric",
        @Query("source") source: String,
        @Query("id") id: String
    ): GdStudioLyricResponse
}

data class GdStudioUrlResponse(
    val url: String?,
    @SerializedName("br") val bitRate: Int?,
    val size: Long?
)

data class GdStudioPicResponse(
    val url: String?
)

data class GdStudioLyricResponse(
    val lyric: String?,
    val tlyric: String?
)
