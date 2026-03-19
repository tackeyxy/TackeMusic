package com.tacke.music.data.repository

import android.content.Context
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.model.RecentPlay
import com.tacke.music.data.model.PlaylistSong
import kotlinx.coroutines.flow.Flow

class RecentPlayRepository(context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val recentPlayDao = database.recentPlayDao()

    fun getRecentPlays(limit: Int = 100): Flow<List<RecentPlay>> {
        return recentPlayDao.getRecentPlays(limit)
    }

    suspend fun getRecentPlaysSync(limit: Int = 100): List<RecentPlay> {
        return recentPlayDao.getRecentPlaysSync(limit)
    }

    suspend fun addRecentPlay(recentPlay: RecentPlay) {
        recentPlayDao.insertRecentPlay(recentPlay)
        // 保持最多100条记录
        recentPlayDao.trimOldRecords(100)
    }

    suspend fun addRecentPlayFromPlaylistSong(song: PlaylistSong) {
        val recentPlay = RecentPlay.fromPlaylistSong(song)
        addRecentPlay(recentPlay)
    }

    suspend fun deleteById(id: String) {
        recentPlayDao.deleteById(id)
    }

    suspend fun clearAll() {
        recentPlayDao.clearAll()
    }

    suspend fun getCount(): Int {
        return recentPlayDao.getCount()
    }

    suspend fun updateCoverUrl(songId: String, coverUrl: String?) {
        recentPlayDao.updateCoverUrl(songId, coverUrl)
    }
}
