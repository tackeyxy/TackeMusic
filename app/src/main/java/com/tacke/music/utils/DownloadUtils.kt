package com.tacke.music.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.tacke.music.R

object DownloadUtils {
    
    private const val TAG = "DownloadUtils"
    
    fun downloadSong(context: Context, url: String, fileName: String): Long {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription(context.getString(R.string.downloading))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_MUSIC,
                    "TackeMusic/$fileName"
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            return downloadManager.enqueue(request)
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}")
            return -1
        }
    }
    
    fun getFileExtension(url: String): String {
        val parts = url.split('?')[0].split('.')
        return if (parts.size > 1) {
            ".${parts.last()}"
        } else {
            ".mp3"
        }
    }
    
    fun sanitizeFileName(fileName: String): String {
        return fileName.replace("[\\/*?:\"<>|]", "")
    }
}
