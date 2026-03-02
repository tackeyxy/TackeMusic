package com.tacke.music.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

// 歌单风格标签响应
data class PlaylistTagsResponse(
    val code: Int,
    val tags: List<PlaylistTag>?
)

// 歌单风格标签
data class PlaylistTag(
    val id: Int,
    val name: String,
    val category: Int,
    val usedCount: Int? = null
)

// 精品歌单列表响应
data class HighQualityPlaylistsResponse(
    val code: Int,
    val playlists: List<HighQualityPlaylist>?,
    val total: Int,
    val more: Boolean,
    val lasttime: Long
)

// 精品歌单
data class HighQualityPlaylist(
    val id: Long,
    val name: String,
    val coverImgUrl: String?,
    val creator: PlaylistCreator?,
    val playCount: Long,
    val trackCount: Int,
    val description: String?,
    val tags: List<String>?,
    val updateTime: Long
)

// 歌单创建者
data class PlaylistCreator(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String?
)

// 歌单详情响应
data class PlaylistDetailResponse(
    val code: Int,
    val playlist: PlaylistDetail?
)

// 歌单trackId
data class TrackId(
    val id: Long
)

// 歌单详情
data class PlaylistDetail(
    val id: Long,
    val name: String,
    val coverImgUrl: String?,
    val description: String?,
    val playCount: Long,
    val trackCount: Int,
    val tracks: List<PlaylistTrack>?,
    val trackIds: List<TrackId>?,
    val creator: PlaylistCreator?,
    val tags: List<String>?,
    val createTime: Long,
    val updateTime: Long
)

// 歌单中的歌曲
data class PlaylistTrack(
    val id: Long,
    val name: String,
    val ar: List<TrackArtist>?,
    val al: TrackAlbum?,
    val dt: Int? // 时长
)

// 歌曲艺术家
data class TrackArtist(
    val id: Long,
    val name: String
)

// 歌曲专辑
data class TrackAlbum(
    val id: Long,
    val name: String,
    val picUrl: String?
)

// 歌曲详情批量响应
data class SongDetailBatchResponse(
    val code: Int,
    val songs: List<SongDetailInfo>?
)

// 歌曲详情（用于 api/song/detail 接口）
data class SongDetailInfo(
    val id: Long,
    val name: String,
    val artists: List<SongDetailArtist>?,
    val album: SongDetailAlbum?,
    val duration: Int?
)

// 歌曲详情中的艺术家
data class SongDetailArtist(
    val id: Long,
    val name: String
)

// 歌曲详情中的专辑
data class SongDetailAlbum(
    val id: Long,
    val name: String,
    val picUrl: String?
)

interface PlaylistApi {

    // 获取歌单风格标签
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer: https://music.163.com/",
        "Cookie: NMTID=00ObZwOVqETChK-4E29vcA0XOWecx8AAAGcnNelcA"
    )
    @GET("api/playlist/highquality/tags")
    suspend fun getPlaylistTags(): PlaylistTagsResponse

    // 获取精品歌单列表
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer: https://music.163.com/",
        "Cookie: NMTID=00ObZwOVqETChK-4E29vcA0XOWecx8AAAGcnNelcA"
    )
    @GET("api/playlist/highquality/list")
    suspend fun getHighQualityPlaylists(
        @Query("cat") category: String = "全部",
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long = 0
    ): HighQualityPlaylistsResponse

    // 获取歌单详情
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer: https://music.163.com/",
        "Cookie: NMTID=00ObZwOVqETChK-4E29vcA0XOWecx8AAAGcnNelcA"
    )
    @GET("api/v6/playlist/detail")
    suspend fun getPlaylistDetail(
        @Query("id") playlistId: Long
    ): PlaylistDetailResponse

    // 批量获取歌曲详情
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Cookie: NMTID=00ObZwOVqETChK-4E29vcA0XOWecx8AAAGcnNelcA"
    )
    @GET("api/song/detail")
    suspend fun getSongDetails(
        @Query("ids") songIds: String
    ): SongDetailBatchResponse
}
