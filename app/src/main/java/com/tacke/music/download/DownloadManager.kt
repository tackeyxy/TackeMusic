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

    // 等待队列，用于管理超出并发限制的任务
    private val pendingQueue = mutableListOf<DownloadTask>()
    private val queueLock = Object()

    private val _downloadingTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadingTasks: StateFlow<List<DownloadTask>> = _downloadingTasks.asStateFlow()

    private val _completedTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val completedTasks: StateFlow<List<DownloadTask>> = _completedTasks.asStateFlow()

    private val _downloadSpeeds = MutableStateFlow<Map<String, Long>>(emptyMap())
    val downloadSpeeds: StateFlow<Map<String, Long>> = _downloadSpeeds.asStateFlow()

    init {
        loadDownloadHistory()
    }

    /**
     * 获取当前允许的最大并发下载数
     */
    private fun getMaxConcurrentDownloads(): Int {
        return SettingsActivity.getConcurrentDownloads(context)
    }

    /**
     * 获取当前正在下载的任务数
     */
    private fun getActiveDownloadCount(): Int {
        return downloadJobs.count { (_, job) -> job.isActive }
    }

    /**
     * 检查并启动等待队列中的任务
     */
    private fun processPendingQueue() {
        synchronized(queueLock) {
            val maxConcurrent = getMaxConcurrentDownloads()
            val activeCount = getActiveDownloadCount()
            val availableSlots = maxConcurrent - activeCount

            if (availableSlots > 0 && pendingQueue.isNotEmpty()) {
                val tasksToStart = pendingQueue.take(availableSlots)
                pendingQueue.removeAll(tasksToStart)

                tasksToStart.forEach { task ->
                    startDownloadInternal(task)
                }
            }
        }
    }

    /**
     * 当任务完成或暂停时，尝试启动队列中的下一个任务
     */
    private fun onDownloadSlotFreed() {
        processPendingQueue()
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

    fun createDownloadTask(song: Song, detail: SongDetail, quality: String, platform: String = "KUWO"): DownloadTask {
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
            filePath = filePath,
            platform = platform
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
            // 使用应用私有目录，避免存储权限问题
            // Android 10+ 推荐使用应用私有目录
            val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            if (externalDir != null) {
                File(externalDir, DOWNLOAD_DIR)
            } else {
                // 回退到内部存储
                File(context.filesDir, DOWNLOAD_DIR)
            }
        }
    }

    fun startDownload(task: DownloadTask) {
        if (downloadJobs.containsKey(task.id)) {
            Log.d(TAG, "Download already exists: ${task.id}")
            return
        }

        // 检查是否已在等待队列中
        synchronized(queueLock) {
            if (pendingQueue.any { it.id == task.id }) {
                Log.d(TAG, "Download already in pending queue: ${task.id}")
                return
            }
        }

        scope.launch {
            // 保存任务到数据库
            downloadTaskDao.insertTask(DownloadTaskEntity.fromDownloadTask(task))
        }

        addToDownloading(task)

        // 检查并发限制
        val maxConcurrent = getMaxConcurrentDownloads()
        val activeCount = getActiveDownloadCount()

        if (activeCount >= maxConcurrent) {
            // 如果已达到并发限制，将任务加入等待队列
            synchronized(queueLock) {
                pendingQueue.add(task)
                Log.d(TAG, "Added to pending queue: ${task.songName}, queue size: ${pendingQueue.size}")
            }
            // 更新任务状态为等待中
            updateTaskStatus(task.id, DownloadStatus.PENDING)
        } else {
            // 否则直接开始下载
            startDownloadInternal(task)
        }
    }

    private fun startDownloadInternal(task: DownloadTask) {
        if (downloadJobs.containsKey(task.id)) {
            Log.d(TAG, "Download already exists: ${task.id}")
            return
        }

        val job = scope.launch {
            downloadFile(task)
        }

        downloadJobs[task.id] = job
    }

    fun pauseDownload(taskId: String) {
        // 先检查是否在等待队列中
        synchronized(queueLock) {
            val pendingTask = pendingQueue.find { it.id == taskId }
            if (pendingTask != null) {
                pendingQueue.remove(pendingTask)
                Log.d(TAG, "Removed from pending queue: $taskId")
                updateTaskStatus(taskId, DownloadStatus.PAUSED)
                return
            }
        }

        // 否则取消正在运行的任务
        downloadJobs[taskId]?.cancel()
        downloadJobs.remove(taskId)
        updateTaskStatus(taskId, DownloadStatus.PAUSED)

        // 暂停后释放一个槽位，尝试启动队列中的任务
        onDownloadSlotFreed()
    }

    fun resumeDownload(task: DownloadTask) {
        if (task.isPaused || task.isFailed) {
            // 重置任务状态为 PENDING，然后通过 startDownload 启动
            // startDownload 会根据当前并发限制决定是否立即下载或加入队列
            val resetTask = task.copy(status = DownloadStatus.PENDING)
            // 更新数据库中的状态
            scope.launch {
                downloadTaskDao.updateTaskStatus(resetTask.id, DownloadStatus.PENDING)
            }
            startDownload(resetTask)
        }
    }

    fun deleteDownload(task: DownloadTask, deleteFile: Boolean = true) {
        // 从等待队列中移除（如果存在）
        synchronized(queueLock) {
            pendingQueue.removeAll { it.id == task.id }
        }

        // 取消正在运行的任务
        downloadJobs[task.id]?.cancel()
        downloadJobs.remove(task.id)

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

        // 删除后释放一个槽位，尝试启动队列中的任务
        onDownloadSlotFreed()
    }

    fun deleteDownloads(tasks: List<DownloadTask>, deleteFile: Boolean = true) {
        val taskIds = tasks.map { it.id }

        // 从等待队列中移除
        synchronized(queueLock) {
            pendingQueue.removeAll { task -> taskIds.contains(task.id) }
        }

        // 取消正在运行的任务
        taskIds.forEach { taskId ->
            downloadJobs[taskId]?.cancel()
            downloadJobs.remove(taskId)
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

        // 删除后尝试启动队列中的任务
        onDownloadSlotFreed()
    }

    fun pauseDownloads(taskIds: List<String>) {
        taskIds.forEach { taskId ->
            pauseDownload(taskId)
        }
    }

    fun resumeDownloads(tasks: List<DownloadTask>) {
        tasks.forEach { task ->
            resumeDownload(task)
        }
    }

    private suspend fun downloadFile(task: DownloadTask) {
        try {
            updateTaskStatus(task.id, DownloadStatus.DOWNLOADING)

            val file = File(task.filePath)
            
            // 确保父目录存在
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                val created = parentDir.mkdirs()
                Log.d(TAG, "Created parent directory: $created, path: ${parentDir.absolutePath}")
            }
            
            // 检查文件是否可写
            if (file.exists() && !file.canWrite()) {
                Log.w(TAG, "File exists but not writable, attempting to delete: ${file.absolutePath}")
                val deleted = file.delete()
                Log.d(TAG, "Delete result: $deleted")
            }
            
            var downloadedBytes = if (file.exists()) file.length() else 0
            
            Log.d(TAG, "Starting download: ${task.songName}, file exists: ${file.exists()}, downloadedBytes: $downloadedBytes, canWrite: ${file.canWrite()}")

            val requestBuilder = Request.Builder()
                .url(task.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

            if (downloadedBytes > 0) {
                requestBuilder.header("Range", "bytes=$downloadedBytes-")
                Log.d(TAG, "Adding Range header: bytes=$downloadedBytes-")
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()
            
            Log.d(TAG, "Response code for ${task.songName}: ${response.code}")

            // 处理 HTTP 416 错误（范围不满足）- 删除文件重新下载
            if (response.code == 416) {
                Log.w(TAG, "HTTP 416: Range not satisfiable for ${task.songName}, restarting download")
                response.close()
                
                // 删除已下载的部分文件
                if (file.exists()) {
                    val deleted = file.delete()
                    Log.d(TAG, "Deleted partial file: $deleted")
                    if (!deleted) {
                        // 如果删除失败，尝试重命名
                        val tempFile = File(file.absolutePath + ".old")
                        val renamed = file.renameTo(tempFile)
                        Log.d(TAG, "Renamed file instead: $renamed")
                        if (renamed) {
                            tempFile.delete()
                        }
                    }
                }
                downloadedBytes = 0
                
                // 重新发起不带 Range 的请求
                val newRequest = Request.Builder()
                    .url(task.url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                
                Log.d(TAG, "Retrying download without Range header for ${task.songName}")
                val newResponse = client.newCall(newRequest).execute()
                
                Log.d(TAG, "Retry response code for ${task.songName}: ${newResponse.code}")
                
                if (!newResponse.isSuccessful) {
                    throw Exception("HTTP ${newResponse.code}")
                }
                
                processDownloadResponse(task, newResponse, file, downloadedBytes)
            } else if (!response.isSuccessful && response.code != 206) {
                throw Exception("HTTP ${response.code}")
            } else {
                processDownloadResponse(task, response, file, downloadedBytes)
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "Download cancelled: ${task.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${task.songName}: ${e.message}", e)
            updateTaskStatus(task.id, DownloadStatus.FAILED)
        } finally {
            downloadJobs.remove(task.id)
            _downloadSpeeds.value = _downloadSpeeds.value - task.id
            // 任务结束后（成功、失败或取消），尝试启动队列中的下一个任务
            onDownloadSlotFreed()
        }
    }

    private suspend fun processDownloadResponse(
        task: DownloadTask, 
        response: okhttp3.Response, 
        file: File, 
        downloadedBytes: Long
    ) {
        try {
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
                        randomAccessFile.close()
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
            Log.d(TAG, "Download completed successfully: ${task.songName}")
        } finally {
            // 确保 response 被关闭
            response.close()
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
        synchronized(queueLock) {
            pendingQueue.clear()
        }
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

    /**
     * 更新并发下载限制，并根据新限制调整正在运行的任务
     * @param newLimit 新的并发限制（1-5）
     */
    fun updateConcurrentLimit(newLimit: Int) {
        val validLimit = newLimit.coerceIn(1, 5)
        Log.d(TAG, "Updating concurrent limit to: $validLimit")

        // 如果新限制更大，尝试启动队列中的任务
        if (validLimit > getActiveDownloadCount()) {
            processPendingQueue()
        }
        // 如果新限制更小，正在运行的任务会继续直到完成，新任务会受限制
    }

    /**
     * 获取等待队列中的任务数量
     */
    fun getPendingQueueSize(): Int {
        return synchronized(queueLock) {
            pendingQueue.size
        }
    }
}
