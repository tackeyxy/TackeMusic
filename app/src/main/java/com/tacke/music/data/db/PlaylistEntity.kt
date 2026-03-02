package com.tacke.music.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    val coverUrl: String? = null,
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
    val songCount: Int = 0
)
