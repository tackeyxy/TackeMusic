package com.tacke.music.data.db

import androidx.room.*
import com.tacke.music.data.model.RecentPlay
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentPlayDao {

    @Query("SELECT * FROM recent_plays ORDER BY playedAt DESC LIMIT :limit")
    fun getRecentPlays(limit: Int = 100): Flow<List<RecentPlay>>

    @Query("SELECT * FROM recent_plays ORDER BY playedAt DESC LIMIT :limit")
    suspend fun getRecentPlaysSync(limit: Int = 100): List<RecentPlay>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentPlay(recentPlay: RecentPlay)

    @Query("DELETE FROM recent_plays WHERE id NOT IN (SELECT id FROM recent_plays ORDER BY playedAt DESC LIMIT :keepCount)")
    suspend fun trimOldRecords(keepCount: Int = 100)

    @Query("DELETE FROM recent_plays WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM recent_plays")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM recent_plays")
    suspend fun getCount(): Int

    @Query("UPDATE recent_plays SET coverUrl = :coverUrl WHERE id = :songId")
    suspend fun updateCoverUrl(songId: String, coverUrl: String?)
}
