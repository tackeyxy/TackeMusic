package com.tacke.music.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "recent_plays")
data class RecentPlay(
    @PrimaryKey
    val id: String,
    val name: String,
    val artists: String,
    val coverUrl: String? = null,
    val platform: String,
    val playedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromPlaylistSong(song: PlaylistSong): RecentPlay {
            return RecentPlay(
                id = song.id,
                name = song.name,
                artists = song.artists,
                coverUrl = song.coverUrl,
                platform = song.platform,
                playedAt = System.currentTimeMillis()
            )
        }
    }
}
