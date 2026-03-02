package com.tacke.music.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tacke.music.data.model.DownloadStatus
import com.tacke.music.data.model.DownloadTask

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey
    val id: String,
    val songId: String,
    val songName: String,
    val artist: String,
    val coverUrl: String?,
    val url: String,
    val fileName: String,
    val filePath: String,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val createTime: Long = System.currentTimeMillis(),
    val completeTime: Long = 0
) {
    fun toDownloadTask(): DownloadTask {
        return DownloadTask(
            id = id,
            songId = songId,
            songName = songName,
            artist = artist,
            coverUrl = coverUrl,
            url = url,
            fileName = fileName,
            filePath = filePath,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            status = status,
            createTime = createTime,
            completeTime = completeTime
        )
    }

    companion object {
        fun fromDownloadTask(task: DownloadTask): DownloadTaskEntity {
            return DownloadTaskEntity(
                id = task.id,
                songId = task.songId,
                songName = task.songName,
                artist = task.artist,
                coverUrl = task.coverUrl,
                url = task.url,
                fileName = task.fileName,
                filePath = task.filePath,
                totalBytes = task.totalBytes,
                downloadedBytes = task.downloadedBytes,
                status = task.status,
                createTime = task.createTime,
                completeTime = task.completeTime
            )
        }
    }
}
