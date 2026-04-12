package com.tacke.music.data.api

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

data class KuwoSearchResponse(
    val abslist: List<KuwoSongItem>?
)

data class KuwoSongItem(
    @SerializedName("DC_TARGETID") val dcTargetId: String?,
    @SerializedName("ARTIST") val artist: String?,
    @SerializedName("SONGNAME") val songName: String?,
    @SerializedName("MUSICRID") val musicRid: String?,
    @SerializedName("web_albumpic_short") val webAlbumPicShort: String?
)

// 酷我评论数据类
data class KuwoCommentResponse(
    val code: Int?,
    val msg: String?,
    val result: String?,  // API返回result字段表示状态
    val total: Int?,      // 有些接口total在根级别
    val totalPage: Int?,
    val currentPage: Int?,
    val pageSize: Int?,
    val rows: List<KuwoCommentItem>?  // 有些接口rows在根级别
)

data class KuwoCommentData(
    val total: Int?,
    val rows: List<KuwoCommentItem>?
)

data class KuwoCommentItem(
    val id: String?,
    @SerializedName("msg") val content: String?,  // 酷我使用msg作为评论内容字段
    @SerializedName("u_name") val userName: String?,
    @SerializedName("u_pic") val userAvatar: String?,
    val time: String?,
    @SerializedName("like_num") val likeCount: String?  // 酷我返回的是字符串
)

interface KuwoApi {
    
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Host: search.kuwo.cn"
    )
    @GET("r.s")
    suspend fun searchMusic(
        @Query("client") client: String = "kt",
        @Query("all") keywords: String,
        @Query("pn") pageIndex: Int = 0,
        @Query("rn") pageSize: Int = 30,
        @Query("uid") uid: String = "794762570",
        @Query("ver") ver: String = "kwplayer_ar_9.2.2.1",
        @Query("vipver") vipVer: String = "1",
        @Query("show_copyright_off") showCopyrightOff: Int = 1,
        @Query("newver") newVer: Int = 1,
        @Query("ft") ft: String = "music",
        @Query("cluster") cluster: Int = 0,
        @Query("strategy") strategy: String = "2012",
        @Query("encoding") encoding: String = "utf8",
        @Query("rformat") rFormat: String = "json",
        @Query("vermerge") verMerge: Int = 1,
        @Query("mobi") mobi: Int = 1,
        @Query("issubtitle") isSubtitle: Int = 1
    ): ResponseBody

    // 酷我歌曲评论接口
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        "Host: ncomment.kuwo.cn"
    )
    @GET("com.s")
    suspend fun getComments(
        @Query("type") type: String,
        @Query("f") f: String = "web",
        @Query("page") page: Int = 1,
        @Query("rows") rows: Int = 20,
        @Query("digest") digest: Int = 15,
        @Query("sid") songId: String,
        @Query("uid") uid: String = "0",
        @Query("prod") prod: String = "newWeb",
        @Query("httpsStatus") httpsStatus: Int = 1
    ): KuwoCommentResponse
}
