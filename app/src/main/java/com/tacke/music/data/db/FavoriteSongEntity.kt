package com.tacke.music.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_songs")
data class FavoriteSongEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val artists: String,
    val coverUrl: String? = null,
    val platform: String,
    val addedTime: Long = System.currentTimeMillis()
)
