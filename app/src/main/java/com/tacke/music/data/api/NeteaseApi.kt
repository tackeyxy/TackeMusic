package com.tacke.music.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Headers
import retrofit2.http.POST

data class NeteaseSearchResponse(
    val code: Int,
    val result: NeteaseSearchResult?
)

data class NeteaseSearchResult(
    val songs: List<NeteaseSongItem>?
)

data class NeteaseSongItem(
    val id: Long?,
    val name: String?,
    val ar: List<NeteaseArtist>?
)

data class NeteaseArtist(
    val name: String?
)

interface NeteaseApi {
    
    @Headers(
        "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36",
        "Origin: https://music.163.com",
        "Content-Type: application/x-www-form-urlencoded"
    )
    @FormUrlEncoded
    @POST("eapi/batch")
    suspend fun searchMusic(
        @Field("params") params: String
    ): NeteaseSearchResponse
}
