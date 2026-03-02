package com.tacke.music.data.model

data class Song(
    val index: Int,
    val id: String,
    val name: String,
    val artists: String,
    var coverUrl: String? = null
)
