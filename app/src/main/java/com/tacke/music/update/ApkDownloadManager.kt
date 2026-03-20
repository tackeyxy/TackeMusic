package com.tacke.music.update

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tacke.music.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * APK下载管理器
 * 负责处理应用更新的下载和安装
 */
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

    private val _downloadSpeed = MutableStateFlow(0L)
    val downloadSpeed: StateFlow<Long> = _downloadSpeed

    private var downloadCompleteReceiver: BroadcastReceiver? = null
    private var progressQueryThread: Thread? = null

    // 速度计算
    private var lastDownloadedBytes: Long = 0
    private var lastSpeedUpdateTime: Long = 0

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
            _downloadSpeed.value = 0L
            lastDownloadedBytes = 0
            lastSpeedUpdateTime = System.currentTimeMillis()

            AppLogger.d(TAG, "Started download: $downloadUrl, id: $currentDownloadId")

            // 注册下载完成监听
            registerDownloadReceiver(onComplete)

            // 启动进度查询
            startProgressQuery()

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start download", e)
            Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            _isDownloading.value = false
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
                            _downloadSpeed.value = 0L
                            onComplete(true)
                        } else {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            AppLogger.e(TAG, "Download failed, reason: $reason")
                            Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show()
                            _isDownloading.value = false
                            _downloadSpeed.value = 0L
                            onComplete(false)
                        }
                    }
                    cursor.close()
                    unregisterReceiver()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.registerReceiver(
                downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(
                downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    /**
     * 启动进度查询
     */
    private fun startProgressQuery() {
        progressQueryThread = Thread {
            while (_isDownloading.value && currentDownloadId != -1L) {
                try {
                    val query = DownloadManager.Query().setFilterById(currentDownloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                        if (bytesTotal > 0) {
                            val progress = (bytesDownloaded * 100 / bytesTotal).toInt()
                            _downloadProgress.value = progress

                            // 计算下载速度
                            val currentTime = System.currentTimeMillis()
                            val timeDiff = currentTime - lastSpeedUpdateTime
                            if (timeDiff >= 1000) { // 每秒更新一次速度
                                val bytesDiff = bytesDownloaded - lastDownloadedBytes
                                val speed = (bytesDiff * 1000) / timeDiff // bytes per second
                                _downloadSpeed.value = speed
                                lastDownloadedBytes = bytesDownloaded
                                lastSpeedUpdateTime = currentTime
                            }
                        }
                    }
                    cursor.close()
                    Thread.sleep(500)
                } catch (e: Exception) {
                    break
                }
            }
        }.apply { start() }
    }

    /**
     * 安装APK
     */
    fun installApk(activity: Activity? = null): Boolean {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILE_NAME)
            if (!file.exists()) {
                Toast.makeText(context, "安装文件不存在", Toast.LENGTH_SHORT).show()
                return false
            }

            val canInstall = canRequestPackageInstalls()
            if (!canInstall) {
                AppLogger.w(TAG, "Install blocked: canRequestPackageInstalls=false")
                Toast.makeText(context, "请先允许“安装未知应用”权限", Toast.LENGTH_SHORT).show()
                return false
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val installIntents = buildInstallIntents(uri, activity == null)
            for (intent in installIntents) {
                if (intent.resolveActivity(context.packageManager) != null) {
                    if (activity != null) {
                        activity.startActivity(intent)
                    } else {
                        context.startActivity(intent)
                    }
                    AppLogger.d(TAG, "Started APK installation via intent: action=${intent.action}, package=${intent.`package`}")
                    return true
                }
            }

            AppLogger.e(TAG, "No activity can handle APK install intent")
            Toast.makeText(context, "未找到可用安装器，请手动安装", Toast.LENGTH_LONG).show()
            return false

        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to install APK", e)
            Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun openUnknownSourcesSettings(activity: Activity): Boolean {
        val intents = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intents += Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        intents += Intent(Settings.ACTION_SECURITY_SETTINGS)
        intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

        for (intent in intents) {
            try {
                activity.startActivity(intent)
                AppLogger.d(TAG, "Opened install permission settings: action=${intent.action}")
                return true
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to open settings action=${intent.action}: ${e.message}")
            }
        }
        return false
    }

    private fun buildInstallIntents(apkUri: Uri, addNewTask: Boolean): List<Intent> {
        val baseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (addNewTask) Intent.FLAG_ACTIVITY_NEW_TASK else 0
        val mimeType = "application/vnd.android.package-archive"

        val genericInstall = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            addFlags(baseFlags)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }

        val genericView = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, mimeType)
            addFlags(baseFlags)
        }

        val knownInstallerPackages = listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.oplus.packageinstaller",
            "com.coloros.safecenter",
            "com.oneplus.security",
            "com.huawei.systemmanager"
        )

        val explicit = knownInstallerPackages.flatMap { pkg ->
            listOf(
                Intent(genericInstall).setPackage(pkg),
                Intent(genericView).setPackage(pkg)
            )
        }

        return listOf(genericInstall, genericView) + explicit
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
        _downloadProgress.value = 0
        _downloadSpeed.value = 0L
        unregisterReceiver()
        progressQueryThread?.interrupt()
        progressQueryThread = null
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
