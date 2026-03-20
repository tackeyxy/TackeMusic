package com.tacke.music.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地音乐信息缓存实体类
 * 用于缓存从API获取的本地歌曲封面和歌词信息
 */
@Entity(
    tableName = "local_music_info",
    indices = [
        Index(value = ["path"], unique = true)
    ]
)
data class LocalMusicInfoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,  // 自增ID
    val title: String,  // 歌曲标题
    val artist: String,  // 艺人
    val album: String,  // 专辑
    val path: String,  // 文件路径
    val picId: String? = null,  // API返回的封面ID
    val lyricId: String? = null,  // API返回的歌词ID
    val source: String? = null,  // 音乐源 (netease/kuwo等)
    val coverUrl: String? = null,  // 封面URL
    val lyrics: String? = null,  // 歌词内容
    val updatedAt: Long = System.currentTimeMillis()  // 更新时间
)
