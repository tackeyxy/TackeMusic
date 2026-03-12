package com.tacke.music.data.repository

import android.content.Context
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.db.SongDetailEntity
import com.tacke.music.data.model.SongDetail
import com.tacke.music.data.model.SongInfo
import kotlinx.coroutines.flow.Flow

/**
 * 歌曲详情仓库
 * 管理歌曲详情的本地持久化存储
 */
class SongDetailRepository(context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val songDetailDao = database.songDetailDao()

    /**
     * 保存歌曲详情到本地数据库
     */
    suspend fun saveSongDetail(
        songId: String,
        name: String,
        artists: String,
        platform: String,
        songDetail: SongDetail,
        quality: String = "320k"
    ) {
        val entity = SongDetailEntity(
            id = songId,
            name = name,
            artists = artists,
            platform = platform,
            playUrl = songDetail.url,
            coverUrl = songDetail.cover,
            lyrics = songDetail.lyrics,
            quality = quality,
            updatedAt = System.currentTimeMillis()
        )
        songDetailDao.insertSongDetail(entity)
    }

    /**
     * 从本地数据库获取歌曲详情
     */
    suspend fun getSongDetail(songId: String): SongDetail? {
        val entity = songDetailDao.getSongDetailById(songId)
        return entity?.toSongDetail()
    }

    /**
     * 从本地数据库获取歌曲详情（Flow版本）
     */
    fun getSongDetailFlow(songId: String): Flow<SongDetailEntity?> {
        return songDetailDao.getSongDetailByIdFlow(songId)
    }

    /**
     * 检查本地是否有歌曲详情缓存
     */
    suspend fun hasSongDetail(songId: String): Boolean {
        return songDetailDao.getSongDetailById(songId) != null
    }

    /**
     * 获取缓存的歌曲音质
     */
    suspend fun getSongQuality(songId: String): String? {
        return songDetailDao.getSongDetailById(songId)?.quality
    }

    /**
     * 删除指定歌曲的详情缓存
     */
    suspend fun deleteSongDetail(songId: String) {
        songDetailDao.deleteSongDetailById(songId)
    }

    /**
     * 清理过期的缓存记录（默认保留30天）
     */
    suspend fun cleanOldCache(daysToKeep: Int = 30) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000)
        songDetailDao.deleteOldRecords(cutoffTime)
    }

    /**
     * 获取所有缓存的歌曲详情
     */
    suspend fun getAllCachedDetails(): List<SongDetailEntity> {
        return songDetailDao.getAllSongDetailsSync()
    }

    /**
     * 清空所有缓存
     */
    suspend fun clearAllCache() {
        songDetailDao.deleteAll()
    }

    /**
     * 获取缓存数量
     */
    suspend fun getCacheCount(): Int {
        return songDetailDao.getCount()
    }

    /**
     * 将实体转换为SongDetail对象
     */
    private fun SongDetailEntity.toSongDetail(): SongDetail {
        return SongDetail(
            url = playUrl,
            info = SongInfo(name = name, artist = artists),
            cover = coverUrl,
            lyrics = lyrics
        )
    }
}
