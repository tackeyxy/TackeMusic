package com.tacke.music.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    val coverUrl: String? = null, // 歌曲封面（最早加入的歌曲封面）
    val iconColor: String? = null, // 歌单图标随机颜色
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
    val songCount: Int = 0
)
