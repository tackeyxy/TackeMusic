package com.tacke.music.data.db

import androidx.room.*
import com.tacke.music.data.model.PlaylistSong
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistSongDao {

    @Query("SELECT * FROM playlist_songs ORDER BY orderIndex ASC")
    fun getAllSongs(): Flow<List<PlaylistSong>>

    @Query("SELECT * FROM playlist_songs ORDER BY orderIndex ASC")
    suspend fun getAllSongsSync(): List<PlaylistSong>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: PlaylistSong)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<PlaylistSong>)

    @Delete
    suspend fun deleteSong(song: PlaylistSong)

    @Query("DELETE FROM playlist_songs")
    suspend fun deleteAllSongs()

    @Query("SELECT COUNT(*) FROM playlist_songs")
    suspend fun getSongCount(): Int

    @Query("SELECT * FROM playlist_songs WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: String): PlaylistSong?
}
