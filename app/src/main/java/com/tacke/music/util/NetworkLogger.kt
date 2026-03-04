package com.tacke.music.util

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NetworkLogger private constructor(context: Context) : Interceptor {

    companion object {
        private const val TAG = "NetworkLogger"
        private const val LOG_FILE_NAME = "network_logs.txt"
        private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB

        @Volatile
        private var instance: NetworkLogger? = null

        fun getInstance(context: Context): NetworkLogger {
            return instance ?: synchronized(this) {
                instance ?: NetworkLogger(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logDir: File = File(context.getExternalFilesDir(null), "logs")
    private val logFile: File = File(logDir, LOG_FILE_NAME)

    init {
        createLogDirectory()
    }

    private fun createLogDirectory() {
        try {
            if (!logDir.exists()) {
                val created = logDir.mkdirs()
                Log.d(TAG, "Log directory created: $created, path: ${logDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create log directory", e)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestTime = System.currentTimeMillis()
        val timestamp = dateFormat.format(Date(requestTime))

        // 构建请求日志
        val requestLog = buildString {
            appendLine("╔═══════════════════════════════════════════════════════════════════")
            appendLine("║ 网络请求 [$timestamp]")
            appendLine("╠═══════════════════════════════════════════════════════════════════")
            appendLine("║ URL: ${request.url}")
            appendLine("║ Method: ${request.method}")
            appendLine("║ Headers:")
            request.headers.forEach { (name, value) ->
                appendLine("║   $name: $value")
            }

            // 请求体
            request.body?.let { body ->
                appendLine("║ Body:")
                val buffer = Buffer()
                body.writeTo(buffer)
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val bodyString = buffer.readString(charset)
                // 限制请求体长度，避免日志过大
                val maxBodyLength = 2000
                val displayBody = if (bodyString.length > maxBodyLength) {
                    bodyString.substring(0, maxBodyLength) + "... (${bodyString.length} chars total)"
                } else {
                    bodyString
                }
                displayBody.lines().forEach { line ->
                    appendLine("║   $line")
                }
            }
        }

        // 打印到 Logcat
        Log.d(TAG, requestLog)

        // 执行请求
        val response: Response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            // 记录异常
            val errorLog = buildString {
                appendLine("║ ERROR: ${e.message}")
                appendLine("╚═══════════════════════════════════════════════════════════════════")
                appendLine()
            }
            Log.e(TAG, requestLog + errorLog)
            saveLogToFile(requestLog + errorLog)
            throw e
        }

        val responseTime = System.currentTimeMillis()
        val duration = responseTime - requestTime

        // 构建响应日志
        val responseLog = buildString {
            appendLine("║ Response:")
            appendLine("║   Status Code: ${response.code}")
            appendLine("║   Duration: ${duration}ms")
            appendLine("║   Headers:")
            response.headers.forEach { (name, value) ->
                appendLine("║     $name: $value")
            }

            // 响应体
            response.body?.let { body ->
                val source = body.source()
                source.request(Long.MAX_VALUE)
                val buffer = source.buffer
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val bodyString = buffer.clone().readString(charset)

                appendLine("║   Body:")
                // 限制响应体长度
                val maxBodyLength = 3000
                val displayBody = if (bodyString.length > maxBodyLength) {
                    bodyString.substring(0, maxBodyLength) + "... (${bodyString.length} chars total)"
                } else {
                    bodyString
                }
                displayBody.lines().forEach { line ->
                    appendLine("║     $line")
                }
            }
            appendLine("╚═══════════════════════════════════════════════════════════════════")
            appendLine()
        }

        // 打印到 Logcat
        Log.d(TAG, responseLog)

        // 保存到文件
        saveLogToFile(requestLog + responseLog)

        return response
    }

    private fun saveLogToFile(log: String) {
        logExecutor.execute {
            try {
                // 检查文件大小，如果超过限制则重命名旧文件
                if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE) {
                    val backupFile = File(logDir, "${LOG_FILE_NAME}.old")
                    if (backupFile.exists()) {
                        backupFile.delete()
                    }
                    logFile.renameTo(backupFile)
                }

                // 追加写入日志
                FileWriter(logFile, true).use { writer ->
                    writer.write(log)
                    writer.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to save log to file", e)
            }
        }
    }

    /**
     * 获取日志文件路径
     */
    fun getLogFilePath(): String {
        return logFile.absolutePath
    }

    /**
     * 获取日志目录路径
     */
    fun getLogDirectoryPath(): String {
        return logDir.absolutePath
    }

    /**
     * 清除所有日志文件
     */
    fun clearLogs() {
        logExecutor.execute {
            try {
                logDir.listFiles()?.forEach { file ->
                    file.delete()
                }
                Log.d(TAG, "All logs cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear logs", e)
            }
        }
    }

    /**
     * 获取日志文件大小（字节）
     */
    fun getLogFileSize(): Long {
        return if (logFile.exists()) logFile.length() else 0
    }

    /**
     * 读取日志内容
     */
    fun readLogs(): String {
        return try {
            if (logFile.exists()) {
                logFile.readText(Charsets.UTF_8)
            } else {
                ""
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read logs", e)
            ""
        }
    }
}
