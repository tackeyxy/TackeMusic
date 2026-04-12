package com.tacke.music.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

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
    val ar: List<NeteaseArtist>?,
    val al: NeteaseAlbum?
)

data class NeteaseAlbum(
    val id: Long?,
    val name: String?,
    val picUrl: String?
)

data class NeteaseArtist(
    val name: String?
)

// 网易云评论数据类
data class NeteaseCommentResponse(
    val code: Int?,
    val total: Int?,
    val comments: List<NeteaseCommentItem>?,
    val hotComments: List<NeteaseCommentItem>?
)

data class NeteaseCommentItem(
    val commentId: Long?,
    val content: String?,
    val time: Long?,
    val likedCount: Int?,
    val user: NeteaseCommentUser?
)

data class NeteaseCommentUser(
    val userId: Long?,
    val nickname: String?,
    val avatarUrl: String?
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

    // 网易云最新评论
    @Headers(
        "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36",
        "Referer: https://music.163.com"
    )
    @GET("api/v1/resource/comments/R_SO_4_{songId}")
    suspend fun getLatestComments(
        @retrofit2.http.Path("songId") songId: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("beforeTime") beforeTime: Long = System.currentTimeMillis()
    ): NeteaseCommentResponse

    // 网易云热门评论
    @Headers(
        "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36",
        "Referer: https://music.163.com"
    )
    @GET("api/v1/resource/hotcomments/R_SO_4_{songId}")
    suspend fun getHotComments(
        @retrofit2.http.Path("songId") songId: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): NeteaseCommentResponse
}
