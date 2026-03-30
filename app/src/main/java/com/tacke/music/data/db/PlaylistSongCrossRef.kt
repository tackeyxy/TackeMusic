package com.tacke.music.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "playlist_song_cross_ref",
    primaryKeys = ["playlistId", "songId"],
    indices = [
        Index(value = ["songId"]),
        Index(value = ["playlistId"])
    ]
)
data class PlaylistSongCrossRef(
    val playlistId: String,
    val songId: String,
    val addedTime: Long = System.currentTimeMillis(),
    val orderIndex: Int = 0
)
