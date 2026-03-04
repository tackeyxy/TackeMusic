package com.tacke.music.data.model

data class Song(
    val index: Int,
    val id: String,
    val name: String,
    val artists: String,
    var coverUrl: String? = null,
    val platform: String = "KUWO"  // 歌曲所属平台，默认为酷我
)
