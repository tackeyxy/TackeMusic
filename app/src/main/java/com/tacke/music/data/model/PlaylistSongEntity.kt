package com.tacke.music.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

/**
 * 歌单中的歌曲实体
 * 与正在播放列表(playlist_songs)分开存储
 */
@Serializable
@Entity(tableName = "playlist_song_entities")
data class PlaylistSongEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val artists: String,
    val coverUrl: String? = null,
    val platform: String,
    val addedTime: Long = System.currentTimeMillis()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(jsonStr: String): PlaylistSongEntity? {
            return try {
                json.decodeFromString(serializer(), jsonStr)
            } catch (e: Exception) {
                null
            }
        }

        fun listFromJson(jsonStr: String): List<PlaylistSongEntity> {
            return try {
                json.decodeFromString(ListSerializer(serializer()), jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun toJson(song: PlaylistSongEntity): String {
            return json.encodeToString(serializer(), song)
        }

        fun listToJson(list: List<PlaylistSongEntity>): String {
            return json.encodeToString(ListSerializer(serializer()), list)
        }

        /**
         * 从播放列表歌曲创建
         */
        fun fromPlaylistSong(song: PlaylistSong): PlaylistSongEntity {
            return PlaylistSongEntity(
                id = song.id,
                name = song.name,
                artists = song.artists,
                coverUrl = song.coverUrl,
                platform = song.platform,
                addedTime = song.addedTime
            )
        }
    }

    /**
     * 转换为播放列表歌曲
     */
    fun toPlaylistSong(orderIndex: Int = 0): PlaylistSong {
        return PlaylistSong(
            id = id,
            name = name,
            artists = artists,
            coverUrl = coverUrl,
            platform = platform,
            addedTime = addedTime,
            orderIndex = orderIndex
        )
    }
}
