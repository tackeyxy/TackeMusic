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
}
