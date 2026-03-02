package com.tacke.music.data.db

import androidx.room.TypeConverter
import com.tacke.music.data.model.DownloadStatus

class Converters {

    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus): String {
        return status.name
    }

    @TypeConverter
    fun toDownloadStatus(statusName: String): DownloadStatus {
        return try {
            DownloadStatus.valueOf(statusName)
        } catch (e: IllegalArgumentException) {
            DownloadStatus.PENDING
        }
    }
}
