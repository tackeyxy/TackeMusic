package com.tacke.music.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 歌曲详情数据访问对象
 */
@Dao
interface SongDetailDao {

    @Query("SELECT * FROM song_details WHERE id = :songId LIMIT 1")
    suspend fun getSongDetailById(songId: String): SongDetailEntity?

    @Query("SELECT * FROM song_details WHERE id = :songId LIMIT 1")
    fun getSongDetailByIdFlow(songId: String): Flow<SongDetailEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongDetail(songDetail: SongDetailEntity)

    @Update
    suspend fun updateSongDetail(songDetail: SongDetailEntity)

    @Delete
    suspend fun deleteSongDetail(songDetail: SongDetailEntity)

    @Query("DELETE FROM song_details WHERE id = :songId")
    suspend fun deleteSongDetailById(songId: String)

    @Query("SELECT * FROM song_details ORDER BY updatedAt DESC")
    fun getAllSongDetails(): Flow<List<SongDetailEntity>>

    @Query("SELECT * FROM song_details ORDER BY updatedAt DESC")
    suspend fun getAllSongDetailsSync(): List<SongDetailEntity>

    @Query("DELETE FROM song_details WHERE updatedAt < :timestamp")
    suspend fun deleteOldRecords(timestamp: Long)

    @Query("SELECT COUNT(*) FROM song_details")
    suspend fun getCount(): Int

    @Query("DELETE FROM song_details")
    suspend fun deleteAll()
}
