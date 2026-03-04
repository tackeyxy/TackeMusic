package com.tacke.music.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.tacke.music.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class ApkDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ApkDownloadManager"
        private const val APK_FILE_NAME = "TackeMusic_update.apk"

        @Volatile
        private var instance: ApkDownloadManager? = null

        fun getInstance(context: Context): ApkDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: ApkDownloadManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var currentDownloadId: Long = -1

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading

    private var downloadCompleteReceiver: BroadcastReceiver? = null

    /**
     * 开始下载APK
     * @param downloadUrl APK下载链接
     * @param onComplete 下载完成回调
     */
    fun startDownload(downloadUrl: String, onComplete: (Boolean) -> Unit = {}) {
        try {
            // 如果正在下载，先取消
            if (currentDownloadId != -1L) {
                downloadManager.remove(currentDownloadId)
            }

            // 删除旧文件
            val oldFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
            if (oldFile.exists()) {
                oldFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("TackeMusic 更新")
                setDescription("正在下载新版本...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            currentDownloadId = downloadManager.enqueue(request)
            _isDownloading.value = true
            _downloadProgress.value = 0

            AppLogger.d(TAG, "Started download: $downloadUrl, id: $currentDownloadId")

            // 注册下载完成监听
            registerDownloadReceiver(onComplete)

            // 启动进度查询
            startProgressQuery()

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start download", e)
            Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            onComplete(false)
        }
    }

    /**
     * 注册下载完成广播接收器
     */
    private fun registerDownloadReceiver(onComplete: (Boolean) -> Unit) {
        downloadCompleteReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == currentDownloadId) {
                    val query = DownloadManager.Query().setFilterById(id)
                    val cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            AppLogger.d(TAG, "Download completed successfully")
                            _isDownloading.value = false
                            _downloadProgress.value = 100
                            installApk()
                            onComplete(true)
                        } else {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            AppLogger.e(TAG, "Download failed, reason: $reason")
                            Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
                            _isDownloading.value = false
                            onComplete(false)
                        }
                    }
                    cursor.close()
                    unregisterReceiver()
                }
            }
        }

        context.registerReceiver(
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
    }

    /**
     * 启动进度查询
     */
    private fun startProgressQuery() {
        Thread {
            while (_isDownloading.value && currentDownloadId != -1L) {
                try {
                    val query = DownloadManager.Query().setFilterById(currentDownloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val bytesTotal = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (bytesTotal > 0) {
                            val progress = (bytesDownloaded * 100 / bytesTotal)
                            _downloadProgress.value = progress
                        }
                    }
                    cursor.close()
                    Thread.sleep(500)
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
    }

    /**
     * 安装APK
     */
    fun installApk() {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
            if (!file.exists()) {
                Toast.makeText(context, "安装文件不存在", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
            AppLogger.d(TAG, "Started APK installation")

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to install APK", e)
            Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 取消下载
     */
    fun cancelDownload() {
        if (currentDownloadId != -1L) {
            downloadManager.remove(currentDownloadId)
            currentDownloadId = -1
        }
        _isDownloading.value = false
        unregisterReceiver()
        AppLogger.d(TAG, "Download cancelled")
    }

    /**
     * 注销广播接收器
     */
    private fun unregisterReceiver() {
        try {
            downloadCompleteReceiver?.let {
                context.unregisterReceiver(it)
                downloadCompleteReceiver = null
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * 清理下载的APK文件
     */
    fun cleanup() {
        cancelDownload()
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }
}