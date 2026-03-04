package com.tacke.music.data.db

import androidx.room.*
import com.tacke.music.data.model.PlaylistSongEntity
import kotlinx.coroutines.flow.Flow

/**
 * 歌单歌曲实体的DAO
 * 与正在播放列表分开管理
 */
@Dao
interface PlaylistSongEntityDao {

    @Query("SELECT * FROM playlist_song_entities ORDER BY addedTime DESC")
    fun getAllSongs(): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlist_song_entities ORDER BY addedTime DESC")
    suspend fun getAllSongsSync(): List<PlaylistSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: PlaylistSongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<PlaylistSongEntity>)

    @Delete
    suspend fun deleteSong(song: PlaylistSongEntity)

    @Query("DELETE FROM playlist_song_entities WHERE id = :songId")
    suspend fun deleteSongById(songId: String)

    @Query("DELETE FROM playlist_song_entities")
    suspend fun deleteAllSongs()

    @Query("SELECT COUNT(*) FROM playlist_song_entities")
    suspend fun getSongCount(): Int

    @Query("SELECT * FROM playlist_song_entities WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: String): PlaylistSongEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_song_entities WHERE id = :songId)")
    suspend fun isSongExists(songId: String): Boolean
}
