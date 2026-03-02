package com.tacke.music.data.db

import androidx.room.*
import com.tacke.music.data.model.PlaylistSong
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updateTime DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY updateTime DESC")
    suspend fun getAllPlaylistsSync(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistById(playlistId: String): PlaylistEntity?

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    fun getPlaylistWithSongs(playlistId: String): Flow<PlaylistWithSongs?>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistWithSongsSync(playlistId: String): PlaylistWithSongs?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Update
    suspend fun updatePlaylist(playlist: PlaylistEntity)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: String)

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun getPlaylistCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSongCrossRef(crossRef: PlaylistSongCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(song: PlaylistSong)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongs(songs: List<PlaylistSong>)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun removeAllSongsFromPlaylist(playlistId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId)")
    suspend fun isSongInPlaylist(playlistId: String, songId: String): Boolean

    @Query("SELECT COUNT(*) FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun getPlaylistSongCount(playlistId: String): Int

    @Transaction
    suspend fun addSongToPlaylist(playlistId: String, song: PlaylistSong) {
        insertPlaylistSong(song)
        val orderIndex = getPlaylistSongCount(playlistId)
        insertPlaylistSongCrossRef(
            PlaylistSongCrossRef(
                playlistId = playlistId,
                songId = song.id,
                orderIndex = orderIndex
            )
        )
        updatePlaylistSongCount(playlistId)
    }

    @Transaction
    suspend fun addSongsToPlaylist(playlistId: String, songs: List<PlaylistSong>) {
        insertPlaylistSongs(songs)
        var orderIndex = getPlaylistSongCount(playlistId)
        songs.forEach { song ->
            insertPlaylistSongCrossRef(
                PlaylistSongCrossRef(
                    playlistId = playlistId,
                    songId = song.id,
                    orderIndex = orderIndex++
                )
            )
        }
        updatePlaylistSongCount(playlistId)
    }

    @Query("UPDATE playlists SET songCount = (SELECT COUNT(*) FROM playlist_song_cross_ref WHERE playlistId = :playlistId), updateTime = :updateTime WHERE id = :playlistId")
    suspend fun updatePlaylistSongCount(playlistId: String, updateTime: Long = System.currentTimeMillis())

    @Query("SELECT s.* FROM playlist_songs s INNER JOIN playlist_song_cross_ref ref ON s.id = ref.songId WHERE ref.playlistId = :playlistId ORDER BY ref.orderIndex ASC")
    fun getSongsInPlaylist(playlistId: String): Flow<List<PlaylistSong>>

    @Query("SELECT s.* FROM playlist_songs s INNER JOIN playlist_song_cross_ref ref ON s.id = ref.songId WHERE ref.playlistId = :playlistId ORDER BY ref.orderIndex ASC")
    suspend fun getSongsInPlaylistSync(playlistId: String): List<PlaylistSong>
}
