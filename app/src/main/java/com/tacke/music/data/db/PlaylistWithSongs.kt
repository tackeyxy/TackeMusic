package com.tacke.music.data.db

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.tacke.music.data.model.PlaylistSong

data class PlaylistWithSongs(
    @Embedded
    val playlist: PlaylistEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PlaylistSongCrossRef::class,
            parentColumn = "playlistId",
            entityColumn = "songId"
        )
    )
    val songs: List<PlaylistSong>
)
