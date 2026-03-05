package com.tacke.music.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteSongDao {

    @Query("SELECT * FROM favorite_songs ORDER BY addedTime DESC")
    fun getAllFavoriteSongs(): Flow<List<FavoriteSongEntity>>

    @Query("SELECT * FROM favorite_songs ORDER BY addedTime DESC")
    suspend fun getAllFavoriteSongsSync(): List<FavoriteSongEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_songs WHERE id = :songId)")
    suspend fun isFavorite(songId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoriteSong(song: FavoriteSongEntity)

    @Delete
    suspend fun deleteFavoriteSong(song: FavoriteSongEntity)

    @Query("DELETE FROM favorite_songs WHERE id = :songId")
    suspend fun deleteFavoriteSongById(songId: String)

    @Query("SELECT COUNT(*) FROM favorite_songs")
    fun getFavoriteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM favorite_songs")
    suspend fun getFavoriteCountSync(): Int

    @Query("SELECT * FROM favorite_songs WHERE id = :songId LIMIT 1")
    suspend fun getFavoriteSongById(songId: String): FavoriteSongEntity?

    @Query("UPDATE favorite_songs SET coverUrl = :coverUrl WHERE id = :songId")
    suspend fun updateSongCoverUrl(songId: String, coverUrl: String?)
}
