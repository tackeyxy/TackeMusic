package com.tacke.music.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

@Serializable
@Entity(tableName = "playlist_songs")
data class PlaylistSong(
    @PrimaryKey
    val id: String,
    val name: String,
    val artists: String,
    val coverUrl: String? = null,
    val platform: String,
    val addedTime: Long = System.currentTimeMillis(),
    val orderIndex: Int = 0
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(jsonStr: String): PlaylistSong? {
            return try {
                json.decodeFromString(serializer(), jsonStr)
            } catch (e: Exception) {
                null
            }
        }

        fun listFromJson(jsonStr: String): List<PlaylistSong> {
            return try {
                json.decodeFromString(ListSerializer(serializer()), jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun toJson(song: PlaylistSong): String {
            return json.encodeToString(serializer(), song)
        }

        fun listToJson(list: List<PlaylistSong>): String {
            return json.encodeToString(ListSerializer(serializer()), list)
        }
    }
}
