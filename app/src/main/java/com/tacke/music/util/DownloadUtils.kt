package com.tacke.music.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

object DownloadUtils {

    fun getFileExtension(url: String): String {
        val extension = url.substringAfterLast('.', "")
        return if (extension.isNotEmpty()) ".$extension" else ""
    }

    fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[\\/:*?\"<>|]"), "_")
    }

    fun downloadSong(context: Context, url: String, fileName: String): Long {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("Downloading")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, fileName)

        return try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            e.printStackTrace()
            -1L
        }
    }
}
