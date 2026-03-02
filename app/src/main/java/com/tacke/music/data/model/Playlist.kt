package com.tacke.music.data.model

data class Playlist(
    val id: String,
    val name: String,
    val description: String = "",
    val coverUrl: String? = null,
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
    val songCount: Int = 0
)
