package com.tacke.music.data.db

import androidx.room.*
import com.tacke.music.data.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_tasks WHERE status != 'COMPLETED' ORDER BY createTime DESC")
    fun getAllDownloadingTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED' ORDER BY completeTime DESC")
    fun getAllCompletedTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status != 'COMPLETED' ORDER BY createTime DESC")
    suspend fun getDownloadingTasksOnce(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED' ORDER BY completeTime DESC")
    suspend fun getCompletedTasksOnce(): List<DownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<DownloadTaskEntity>)

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query("UPDATE download_tasks SET status = :status WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: String, status: DownloadStatus)

    @Query("UPDATE download_tasks SET downloadedBytes = :downloadedBytes WHERE id = :taskId")
    suspend fun updateTaskProgress(taskId: String, downloadedBytes: Long)

    @Query("UPDATE download_tasks SET totalBytes = :totalBytes WHERE id = :taskId")
    suspend fun updateTaskTotalBytes(taskId: String, totalBytes: Long)

    @Query("UPDATE download_tasks SET status = 'COMPLETED', completeTime = :completeTime WHERE id = :taskId")
    suspend fun completeTask(taskId: String, completeTime: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteTask(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: String)

    @Query("DELETE FROM download_tasks WHERE id IN (:taskIds)")
    suspend fun deleteTasksByIds(taskIds: List<String>)

    @Query("SELECT * FROM download_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): DownloadTaskEntity?

    @Query("UPDATE download_tasks SET coverUrl = :coverUrl WHERE songId = :songId")
    suspend fun updateCoverUrlBySongId(songId: String, coverUrl: String?)
}
