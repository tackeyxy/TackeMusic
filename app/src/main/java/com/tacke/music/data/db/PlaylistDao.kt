package com.tacke.music.data.db

import androidx.room.*
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.PlaylistSongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    suspend fun insertPlaylistSongEntity(song: PlaylistSongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongEntities(songs: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylistRef(playlistId: String, songId: String)

    @Transaction
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) {
        removeSongFromPlaylistRef(playlistId, songId)
        updatePlaylistSongCount(playlistId)
        // 更新歌单封面为最晚添加的歌曲封面
        val latestCover = getLatestValidSongCover(playlistId)
        updatePlaylistCover(playlistId, latestCover)
    }

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun removeAllSongsFromPlaylist(playlistId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId)")
    suspend fun isSongInPlaylist(playlistId: String, songId: String): Boolean

    @Query("SELECT COUNT(*) FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun getPlaylistSongCount(playlistId: String): Int

    @Transaction
    suspend fun addSongToPlaylist(playlistId: String, song: PlaylistSong, songEntityDao: PlaylistSongEntityDao) {
        val songEntity = PlaylistSongEntity(
            id = song.id,
            name = song.name,
            artists = song.artists,
            coverUrl = song.coverUrl,
            platform = song.platform,
            addedTime = song.addedTime
        )
        songEntityDao.insertSong(songEntity)

        val orderIndex = getPlaylistSongCount(playlistId)
        insertPlaylistSongCrossRef(
            PlaylistSongCrossRef(
                playlistId = playlistId,
                songId = song.id,
                orderIndex = orderIndex
            )
        )
        updatePlaylistSongCount(playlistId)
        // 更新歌单封面为最晚添加的歌曲封面（即当前添加的这首歌）
        if (!song.coverUrl.isNullOrEmpty()) {
            updatePlaylistCover(playlistId, song.coverUrl)
        }
    }

    @Transaction
    suspend fun addSongsToPlaylist(playlistId: String, songs: List<PlaylistSong>, songEntityDao: PlaylistSongEntityDao) {
        val songEntities = songs.map { song ->
            PlaylistSongEntity(
                id = song.id,
                name = song.name,
                artists = song.artists,
                coverUrl = song.coverUrl,
                platform = song.platform,
                addedTime = song.addedTime
            )
        }
        songEntityDao.insertSongs(songEntities)

        val startIndex = getPlaylistSongCount(playlistId)
        var orderIndex = startIndex
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
        // 更新歌单封面为最晚添加的歌曲封面（即批量添加的最后一首）
        songs.lastOrNull()?.coverUrl?.let { coverUrl ->
            if (coverUrl.isNotEmpty()) {
                updatePlaylistCover(playlistId, coverUrl)
            }
        }
    }

    @Query("UPDATE playlists SET songCount = (SELECT COUNT(*) FROM playlist_song_cross_ref WHERE playlistId = :playlistId), updateTime = :updateTime WHERE id = :playlistId")
    suspend fun updatePlaylistSongCount(playlistId: String, updateTime: Long = System.currentTimeMillis())

    @Query("""
        SELECT e.* FROM playlist_song_entities e
        INNER JOIN playlist_song_cross_ref ref ON e.id = ref.songId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.orderIndex ASC
    """)
    fun getSongsInPlaylistEntities(playlistId: String): Flow<List<PlaylistSongEntity>>

    @Query("""
        SELECT e.* FROM playlist_song_entities e
        INNER JOIN playlist_song_cross_ref ref ON e.id = ref.songId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.orderIndex ASC
    """)
    suspend fun getSongsInPlaylistEntitiesSync(playlistId: String): List<PlaylistSongEntity>

    fun getSongsInPlaylist(playlistId: String): Flow<List<PlaylistSong>> {
        return getSongsInPlaylistEntities(playlistId).map { entities ->
            entities.mapIndexed { index, entity ->
                PlaylistSong(
                    id = entity.id,
                    name = entity.name,
                    artists = entity.artists,
                    coverUrl = entity.coverUrl,
                    platform = entity.platform,
                    addedTime = entity.addedTime,
                    orderIndex = index
                )
            }
        }
    }

    suspend fun getSongsInPlaylistSync(playlistId: String): List<PlaylistSong> {
        return getSongsInPlaylistEntitiesSync(playlistId).mapIndexed { index, entity ->
            PlaylistSong(
                id = entity.id,
                name = entity.name,
                artists = entity.artists,
                coverUrl = entity.coverUrl,
                platform = entity.platform,
                addedTime = entity.addedTime,
                orderIndex = index
            )
        }
    }

    @Query("""
        SELECT e.coverUrl FROM playlist_song_entities e
        INNER JOIN playlist_song_cross_ref ref ON e.id = ref.songId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.addedTime DESC LIMIT 1
    """)
    suspend fun getLatestAddedSongCover(playlistId: String): String?

    @Query("""
        SELECT e.coverUrl FROM playlist_song_entities e
        INNER JOIN playlist_song_cross_ref ref ON e.id = ref.songId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.orderIndex DESC LIMIT 1
    """)
    suspend fun getLatestSongCover(playlistId: String): String?

    @Query("""
        SELECT e.coverUrl FROM playlist_song_entities e
        INNER JOIN playlist_song_cross_ref ref ON e.id = ref.songId
        WHERE ref.playlistId = :playlistId AND e.coverUrl IS NOT NULL AND e.coverUrl != ''
        ORDER BY ref.addedTime DESC LIMIT 1
    """)
    suspend fun getLatestValidSongCover(playlistId: String): String?

    @Query("""
        SELECT e.coverUrl FROM playlist_song_entities e
        INNER JOIN playlist_song_cross_ref ref ON e.id = ref.songId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.orderIndex ASC LIMIT 1
    """)
    suspend fun getFirstSongCover(playlistId: String): String?

    @Query("UPDATE playlists SET coverUrl = :coverUrl, updateTime = :updateTime WHERE id = :playlistId")
    suspend fun updatePlaylistCover(playlistId: String, coverUrl: String?, updateTime: Long = System.currentTimeMillis())

    @Query("SELECT coverUrl FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistCover(playlistId: String): String?

    @Query("UPDATE playlist_song_entities SET coverUrl = :coverUrl WHERE id = :songId")
    suspend fun updateSongCoverUrlBySongId(songId: String, coverUrl: String?)

    @Transaction
    suspend fun updateSongCoverUrl(playlistId: String, songId: String, coverUrl: String?) {
        updateSongCoverUrlBySongId(songId, coverUrl)
    }
}
