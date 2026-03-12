package com.tacke.music.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 歌曲详情实体类
 * 用于持久化存储歌曲的播放链接、歌词、封面等信息
 */
@Entity(tableName = "song_details")
data class SongDetailEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val artists: String,
    val platform: String,
    val playUrl: String,
    val coverUrl: String? = null,
    val lyrics: String? = null,
    val quality: String = "320k",
    val updatedAt: Long = System.currentTimeMillis()
)
