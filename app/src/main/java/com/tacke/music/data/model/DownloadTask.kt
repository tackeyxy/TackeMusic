package com.tacke.music.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DownloadTask(
    val id: String,
    val songId: String,
    val songName: String,
    val artist: String,
    val coverUrl: String?,
    val url: String,
    val fileName: String,
    val filePath: String,
    val platform: String = "KUWO",  // 音源平台
    val quality: String = "320k",    // 下载音质
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    var status: DownloadStatus = DownloadStatus.PENDING,
    val createTime: Long = System.currentTimeMillis(),
    var completeTime: Long = 0
) : Parcelable {

    val progress: Int
        get() = if (totalBytes > 0) {
            ((downloadedBytes * 100) / totalBytes).toInt()
        } else 0

    val isCompleted: Boolean
        get() = status == DownloadStatus.COMPLETED

    val isDownloading: Boolean
        get() = status == DownloadStatus.DOWNLOADING

    val isPaused: Boolean
        get() = status == DownloadStatus.PAUSED

    val isPending: Boolean
        get() = status == DownloadStatus.PENDING

    val isFailed: Boolean
        get() = status == DownloadStatus.FAILED

    fun getFormattedSize(): String {
        return formatFileSize(totalBytes)
    }

    fun getFormattedDownloadedSize(): String {
        return formatFileSize(downloadedBytes)
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var unitIndex = 0
        var sizeValue = size.toDouble()
        while (sizeValue >= 1024 && unitIndex < units.size - 1) {
            sizeValue /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", sizeValue, units[unitIndex])
    }
}

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}
