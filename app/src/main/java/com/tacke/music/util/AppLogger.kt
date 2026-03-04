package com.tacke.music.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 应用日志管理器
 * 支持记录应用运行日志，包括不同级别的日志（DEBUG、INFO、WARN、ERROR）
 */
class AppLogger private constructor(context: Context) {

    companion object {
        private const val TAG = "AppLogger"
        private const val LOG_FILE_NAME = "app_logs.txt"
        private const val MAX_LOG_FILE_SIZE = 5 * 1024 * 1024 // 5MB
        private const val MAX_LOG_FILES = 3 // 保留的日志文件数量

        // 日志级别
        const val LEVEL_VERBOSE = 0
        const val LEVEL_DEBUG = 1
        const val LEVEL_INFO = 2
        const val LEVEL_WARN = 3
        const val LEVEL_ERROR = 4

        @Volatile
        private var instance: AppLogger? = null

        fun getInstance(context: Context): AppLogger {
            return instance ?: synchronized(this) {
                instance ?: AppLogger(context.applicationContext).also {
                    instance = it
                }
            }
        }

        // 全局快捷方法
        fun v(tag: String, message: String) = instance?.log(LEVEL_VERBOSE, tag, message) ?: Unit
        fun d(tag: String, message: String) = instance?.log(LEVEL_DEBUG, tag, message) ?: Unit
        fun i(tag: String, message: String) = instance?.log(LEVEL_INFO, tag, message) ?: Unit
        fun w(tag: String, message: String) = instance?.log(LEVEL_WARN, tag, message) ?: Unit
        fun e(tag: String, message: String) = instance?.log(LEVEL_ERROR, tag, message) ?: Unit
        fun e(tag: String, message: String, throwable: Throwable) = instance?.log(LEVEL_ERROR, tag, "$message\n${throwable.stackTraceToString()}") ?: Unit
    }

    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logDir: File = File(context.getExternalFilesDir(null), "logs")
    private val logFile: File = File(logDir, LOG_FILE_NAME)

    // 当前日志级别，低于此级别的日志不会记录到文件
    private var currentLogLevel: Int = LEVEL_DEBUG

    init {
        createLogDirectory()
        i(TAG, "AppLogger initialized")
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

    /**
     * 设置日志级别
     */
    fun setLogLevel(level: Int) {
        currentLogLevel = level
        i(TAG, "Log level set to: ${getLevelName(level)}")
    }

    /**
     * 获取当前日志级别
     */
    fun getLogLevel(): Int = currentLogLevel

    /**
     * 记录日志
     */
    fun log(level: Int, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val levelName = getLevelName(level)
        val logMessage = "[$timestamp] [$levelName] [$tag] $message"

        // 打印到 Logcat
        when (level) {
            LEVEL_VERBOSE -> Log.v(tag, message)
            LEVEL_DEBUG -> Log.d(tag, message)
            LEVEL_INFO -> Log.i(tag, message)
            LEVEL_WARN -> Log.w(tag, message)
            LEVEL_ERROR -> Log.e(tag, message)
        }

        // 保存到文件（只保存 INFO 及以上级别，或设置的级别）
        if (level >= currentLogLevel) {
            saveLogToFile(logMessage)
        }
    }

    /**
     * 获取日志级别名称
     */
    private fun getLevelName(level: Int): String {
        return when (level) {
            LEVEL_VERBOSE -> "V"
            LEVEL_DEBUG -> "D"
            LEVEL_INFO -> "I"
            LEVEL_WARN -> "W"
            LEVEL_ERROR -> "E"
            else -> "?"
        }
    }

    /**
     * 保存日志到文件
     */
    private fun saveLogToFile(logMessage: String) {
        logExecutor.execute {
            try {
                // 检查文件大小，如果超过限制则轮转日志
                if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE) {
                    rotateLogFiles()
                }

                // 追加写入日志
                FileWriter(logFile, true).use { writer ->
                    writer.write(logMessage)
                    writer.write("\n")
                    writer.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to save log to file", e)
            }
        }
    }

    /**
     * 日志文件轮转
     */
    private fun rotateLogFiles() {
        try {
            // 删除最旧的日志文件
            val oldestFile = File(logDir, "${LOG_FILE_NAME}.${MAX_LOG_FILES - 1}")
            if (oldestFile.exists()) {
                oldestFile.delete()
            }

            // 重命名其他日志文件
            for (i in MAX_LOG_FILES - 2 downTo 1) {
                val oldFile = File(logDir, "${LOG_FILE_NAME}.$i")
                val newFile = File(logDir, "${LOG_FILE_NAME}.${i + 1}")
                if (oldFile.exists()) {
                    oldFile.renameTo(newFile)
                }
            }

            // 重命名当前日志文件
            val backupFile = File(logDir, "${LOG_FILE_NAME}.1")
            logFile.renameTo(backupFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log files", e)
        }
    }

    /**
     * 获取日志文件路径
     */
    fun getLogFilePath(): String = logFile.absolutePath

    /**
     * 获取日志目录路径
     */
    fun getLogDirectoryPath(): String = logDir.absolutePath

    /**
     * 获取所有日志文件
     */
    fun getAllLogFiles(): List<File> {
        val files = mutableListOf<File>()
        if (logFile.exists()) {
            files.add(logFile)
        }
        for (i in 1 until MAX_LOG_FILES) {
            val backupFile = File(logDir, "${LOG_FILE_NAME}.$i")
            if (backupFile.exists()) {
                files.add(backupFile)
            }
        }
        return files
    }

    /**
     * 读取日志内容
     */
    fun readLogs(maxLines: Int = 500): String {
        return try {
            if (!logFile.exists()) {
                return "暂无日志"
            }
            val lines = logFile.readLines()
            if (lines.size > maxLines) {
                lines.takeLast(maxLines).joinToString("\n")
            } else {
                lines.joinToString("\n")
            }
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    /**
     * 清除所有日志
     */
    fun clearLogs() {
        logExecutor.execute {
            try {
                logDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith(LOG_FILE_NAME)) {
                        file.delete()
                    }
                }
                i(TAG, "All logs cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear logs", e)
            }
        }
    }

    /**
     * 获取日志文件总大小
     */
    fun getTotalLogSize(): Long {
        var totalSize = 0L
        logDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(LOG_FILE_NAME)) {
                totalSize += file.length()
            }
        }
        return totalSize
    }

    /**
     * 导出日志到指定路径
     */
    fun exportLogs(exportFile: File, callback: (Boolean) -> Unit) {
        logExecutor.execute {
            try {
                val allLogs = StringBuilder()
                getAllLogFiles().sortedBy { it.name }.forEach { file ->
                    allLogs.appendLine("=== ${file.name} ===")
                    allLogs.appendLine(file.readText())
                    allLogs.appendLine()
                }

                exportFile.parentFile?.mkdirs()
                exportFile.writeText(allLogs.toString())
                callback(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export logs", e)
                callback(false)
            }
        }
    }
}
