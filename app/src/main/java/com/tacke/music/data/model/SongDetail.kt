package com.tacke.music.data.model

data class SongDetail(
    val url: String,
    val info: SongInfo,
    val cover: String?,
    val lyrics: String?
)

data class SongInfo(
    val name: String,
    val artist: String
)

data class ParseResponse(
    val code: Int,
    val success: Boolean,
    val message: String?,
    val data: ParseData?
)

data class ParseData(
    val data: List<SongDetailData>?
)

data class SongDetailData(
    val url: String,
    val info: SongInfoData?,
    val cover: String?,
    val lyrics: String?
)

data class SongInfoData(
    val name: String,
    val artist: String
)
