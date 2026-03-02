package com.tacke.music.data.model

import com.google.gson.annotations.SerializedName

data class ChartSong(
    val id: String,
    val name: String,
    val artist: String,
    @SerializedName("artist_id")
    val artistId: String? = null,
    val album: String? = null,
    @SerializedName("album_id")
    val albumId: String? = null,
    val cover: String? = null,
    val duration: Int = 0,
    val source: String,
    val url: String? = null
)

data class ChartResponse(
    val code: Int,
    val data: List<ChartSong>? = null,
    val msg: String? = null
)
