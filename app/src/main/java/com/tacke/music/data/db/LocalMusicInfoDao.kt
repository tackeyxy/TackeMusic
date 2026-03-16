package com.tacke.music.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 本地音乐信息缓存DAO
 */
@Dao
interface LocalMusicInfoDao {

    /**
     * 根据ID获取本地音乐信息
     */
    @Query("SELECT * FROM local_music_info WHERE id = :id")
    suspend fun getById(id: Long): LocalMusicInfoEntity?

    /**
     * 根据文件路径获取本地音乐信息
     */
    @Query("SELECT * FROM local_music_info WHERE path = :path")
    suspend fun getByPath(path: String): LocalMusicInfoEntity?

    /**
     * 获取所有本地音乐信息
     */
    @Query("SELECT * FROM local_music_info ORDER BY title ASC")
    fun getAll(): Flow<List<LocalMusicInfoEntity>>

    /**
     * 同步获取所有本地音乐信息
     */
    @Query("SELECT * FROM local_music_info ORDER BY title ASC")
    suspend fun getAllSync(): List<LocalMusicInfoEntity>

    /**
     * 插入或更新本地音乐信息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalMusicInfoEntity)

    /**
     * 插入或更新本地音乐信息并返回ID
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAndReturnId(entity: LocalMusicInfoEntity): Long

    /**
     * 批量插入或更新
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<LocalMusicInfoEntity>)

    /**
     * 删除指定ID的本地音乐信息
     */
    @Query("DELETE FROM local_music_info WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 根据文件路径删除
     */
    @Query("DELETE FROM local_music_info WHERE path = :path")
    suspend fun deleteByPath(path: String)

    /**
     * 清空所有本地音乐信息
     */
    @Query("DELETE FROM local_music_info")
    suspend fun deleteAll()

    /**
     * 检查是否存在
     */
    @Query("SELECT COUNT(*) FROM local_music_info WHERE id = :id")
    suspend fun exists(id: Long): Int

    /**
     * 获取缓存数量
     */
    @Query("SELECT COUNT(*) FROM local_music_info")
    suspend fun getCount(): Int

    /**
     * 删除过期的缓存（超过指定天数）
     */
    @Query("DELETE FROM local_music_info WHERE updatedAt < :expiryTime")
    suspend fun deleteExpired(expiryTime: Long)
}
