package com.tacke.music.download

import android.content.Context
import android.os.Environment
import android.util.Log
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.db.DownloadTaskEntity
import com.tacke.music.data.model.DownloadStatus
import com.tacke.music.data.model.DownloadTask
import com.tacke.music.data.model.Song
import com.tacke.music.data.model.SongDetail
import com.tacke.music.ui.SettingsActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

class DownloadManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DownloadManager"
        private const val DOWNLOAD_DIR = "TackeMusic"
        private const val BUFFER_SIZE = 8192

        @Volatile
        private var instance: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return instance ?: synchronized(this) {
                instance ?: DownloadManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val database = AppDatabase.getDatabase(context)
    private val downloadTaskDao = database.downloadTaskDao()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadJobs = ConcurrentHashMap<String, Job>()

    private val _downloadingTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadingTasks: StateFlow<List<DownloadTask>> = _downloadingTasks.asStateFlow()

    private val _completedTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val completedTasks: StateFlow<List<DownloadTask>> = _completedTasks.asStateFlow()

    private val _downloadSpeeds = MutableStateFlow<Map<String, Long>>(emptyMap())
    val downloadSpeeds: StateFlow<Map<String, Long>> = _downloadSpeeds.asStateFlow()

    init {
        loadDownloadHistory()
    }

    private fun loadDownloadHistory() {
        scope.launch {
            // 加载正在下载的任务
            downloadTaskDao.getAllDownloadingTasks()
                .map { entities -> entities.map { it.toDownloadTask() } }
                .collect { tasks ->
                    _downloadingTasks.value = tasks
                }
        }

        scope.launch {
            // 加载已完成的任务
            downloadTaskDao.getAllCompletedTasks()
                .map { entities -> entities.map { it.toDownloadTask() } }
                .collect { tasks ->
                    _completedTasks.value = tasks
                }
        }
    }

    fun createDownloadTask(song: Song, detail: SongDetail, quality: String): DownloadTask {
        val extension = getFileExtensionFromUrl(detail.url)
        val fileName = "${detail.info.name}-${detail.info.artist}$extension"
        val sanitizedFileName = sanitizeFileName(fileName)
        val downloadDir = getDownloadDirectory()
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        val filePath = File(downloadDir, sanitizedFileName).absolutePath

        return DownloadTask(
            id = System.currentTimeMillis().toString(),
            songId = song.id,
            songName = detail.info.name,
            artist = detail.info.artist,
            coverUrl = song.coverUrl ?: detail.cover,
            url = detail.url,
            fileName = sanitizedFileName,
            filePath = filePath
        )
    }

    private fun getFileExtensionFromUrl(url: String): String {
        val cleanUrl = url.split('?')[0]
        val lastDotIndex = cleanUrl.lastIndexOf('.')
        return if (lastDotIndex != -1 && lastDotIndex < cleanUrl.length - 1) {
            ".${cleanUrl.substring(lastDotIndex + 1)}"
        } else {
            ".mp3"
        }
    }

    private fun getDownloadDirectory(): File {
        val customPath = SettingsActivity.getDownloadPath(context)
        return if (customPath != null) {
            File(customPath)
        } else {
            File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                DOWNLOAD_DIR
            )
        }
    }

    fun startDownload(task: DownloadTask) {
        if (downloadJobs.containsKey(task.id)) {
            Log.d(TAG, "Download already exists: ${task.id}")
            return
        }

        scope.launch {
            // 保存任务到数据库
            downloadTaskDao.insertTask(DownloadTaskEntity.fromDownloadTask(task))
        }

        addToDownloading(task)

        val job = scope.launch {
            downloadFile(task)
        }

        downloadJobs[task.id] = job
    }

    fun pauseDownload(taskId: String) {
        downloadJobs[taskId]?.cancel()
        downloadJobs.remove(taskId)
        updateTaskStatus(taskId, DownloadStatus.PAUSED)
    }

    fun resumeDownload(task: DownloadTask) {
        if (task.isPaused || task.isFailed) {
            startDownload(task.copy(status = DownloadStatus.PENDING))
        }
    }

    fun deleteDownload(task: DownloadTask, deleteFile: Boolean = true) {
        pauseDownload(task.id)

        scope.launch {
            downloadTaskDao.deleteTaskById(task.id)
        }

        if (deleteFile) {
            try {
                File(task.filePath).delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file: ${e.message}")
            }
        }
    }

    fun deleteDownloads(tasks: List<DownloadTask>, deleteFile: Boolean = true) {
        val taskIds = tasks.map { it.id }
        
        taskIds.forEach { taskId ->
            pauseDownload(taskId)
        }

        scope.launch {
            downloadTaskDao.deleteTasksByIds(taskIds)
        }

        if (deleteFile) {
            tasks.forEach { task ->
                try {
                    File(task.filePath).delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete file: ${e.message}")
                }
            }
        }
    }

    fun pauseDownloads(taskIds: List<String>) {
        taskIds.forEach { taskId ->
            pauseDownload(taskId)
        }
    }

    fun resumeDownloads(tasks: List<DownloadTask>) {
        tasks.forEach { task ->
            if (task.isPaused || task.isFailed) {
                startDownload(task.copy(status = DownloadStatus.PENDING))
            }
        }
    }

    private suspend fun downloadFile(task: DownloadTask) {
        try {
            updateTaskStatus(task.id, DownloadStatus.DOWNLOADING)

            val file = File(task.filePath)
            val downloadedBytes = if (file.exists()) file.length() else 0

            val requestBuilder = Request.Builder()
                .url(task.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

            if (downloadedBytes > 0) {
                requestBuilder.header("Range", "bytes=$downloadedBytes-")
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful && response.code != 206) {
                throw Exception("HTTP ${response.code}")
            }

            val totalBytes = response.header("Content-Length")?.toLongOrNull()?.plus(downloadedBytes)
                ?: task.totalBytes

            updateTaskTotalBytes(task.id, totalBytes)

            val inputStream = response.body?.byteStream()
                ?: throw Exception("Empty response body")

            val randomAccessFile = RandomAccessFile(file, "rw")
            randomAccessFile.seek(downloadedBytes)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var totalRead = downloadedBytes
            var lastUpdateTime = System.currentTimeMillis()
            var lastReadBytes = downloadedBytes

            val job = downloadJobs[task.id]

            inputStream.use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (job?.isActive == false) {
                        throw CancellationException()
                    }

                    randomAccessFile.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime >= 500) {
                        val speed = ((totalRead - lastReadBytes) * 1000) / (currentTime - lastUpdateTime)
                        updateTaskProgress(task.id, totalRead)
                        updateDownloadSpeed(task.id, speed)
                        lastUpdateTime = currentTime
                        lastReadBytes = totalRead
                    }
                }
            }

            randomAccessFile.close()
            updateTaskProgress(task.id, totalRead)
            completeDownload(task.id)

        } catch (e: CancellationException) {
            Log.d(TAG, "Download cancelled: ${task.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            updateTaskStatus(task.id, DownloadStatus.FAILED)
        } finally {
            downloadJobs.remove(task.id)
            _downloadSpeeds.value = _downloadSpeeds.value - task.id
        }
    }

    private fun addToDownloading(task: DownloadTask) {
        val currentList = _downloadingTasks.value.toMutableList()
        if (!currentList.any { it.id == task.id }) {
            currentList.add(0, task)
            _downloadingTasks.value = currentList
        }
    }

    private fun updateTaskStatus(taskId: String, status: DownloadStatus) {
        _downloadingTasks.value = _downloadingTasks.value.map {
            if (it.id == taskId) it.copy(status = status) else it
        }
        scope.launch {
            downloadTaskDao.updateTaskStatus(taskId, status)
        }
    }

    private fun updateTaskTotalBytes(taskId: String, totalBytes: Long) {
        _downloadingTasks.value = _downloadingTasks.value.map {
            if (it.id == taskId) it.copy(totalBytes = totalBytes) else it
        }
        scope.launch {
            downloadTaskDao.updateTaskTotalBytes(taskId, totalBytes)
        }
    }

    private fun updateTaskProgress(taskId: String, downloadedBytes: Long) {
        _downloadingTasks.value = _downloadingTasks.value.map {
            if (it.id == taskId) it.copy(downloadedBytes = downloadedBytes) else it
        }
        scope.launch {
            downloadTaskDao.updateTaskProgress(taskId, downloadedBytes)
        }
    }

    private fun updateDownloadSpeed(taskId: String, speed: Long) {
        _downloadSpeeds.value = _downloadSpeeds.value + (taskId to speed)
    }

    private fun completeDownload(taskId: String) {
        scope.launch {
            val completeTime = System.currentTimeMillis()
            downloadTaskDao.completeTask(taskId, completeTime)
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace("[\\\\/*?:\"<>|]".toRegex(), "")
    }

    fun cleanup() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        scope.cancel()
    }

    /**
     * 检查歌曲是否已下载到本地
     * @param songId 歌曲ID
     * @return 如果文件存在且有效，返回文件路径；否则返回 null
     */
    suspend fun getLocalSongPath(songId: String): String? {
        val completedTasks = downloadTaskDao.getCompletedTasksOnce()
        val task = completedTasks.find { it.songId == songId }
        return if (task != null) {
            val file = File(task.filePath)
            if (file.exists() && file.length() > 0) {
                task.filePath
            } else {
                null
            }
        } else {
            null
        }
    }
}
