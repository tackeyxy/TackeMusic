package com.tacke.music.util

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用日志管理器
 * 支持记录应用运行日志，包括不同级别的日志（DEBUG、INFO、WARN、ERROR）
 * 自动捕获系统日志并写入本地文件
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
    private val appContext: Context = context.applicationContext
    private val logDir: File = File(context.getExternalFilesDir(null), "logs")
    private val logFile: File = File(logDir, LOG_FILE_NAME)

    // 当前日志级别，低于此级别的日志不会记录到文件
    private var currentLogLevel: Int = LEVEL_DEBUG

    // 系统日志捕获相关
    private var logcatProcess: Process? = null
    private var logcatReader: BufferedReader? = null
    private val isCapturingLogcat = AtomicBoolean(false)

    init {
        createLogDirectory()
        startLogcatCapture()
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

    /**
     * 启动系统日志捕获
     * 自动捕获当前应用的所有日志输出并写入文件
     */
    private fun startLogcatCapture() {
        if (isCapturingLogcat.get()) return

        logExecutor.execute {
            try {
                isCapturingLogcat.set(true)

                // 获取当前应用包名和进程ID
                val packageName = try {
                    appContext.packageName
                } catch (e: Exception) {
                    "com.tacke.music"
                }
                val myPid = android.os.Process.myPid()

                // 清除旧日志，避免重复记录
                Runtime.getRuntime().exec("logcat -c")

                // 启动 logcat 进程，捕获所有日志
                val processBuilder = ProcessBuilder(
                    "logcat",
                    "-v", "threadtime",  // 显示线程时间和进程信息
                    "*:D"                 // 捕获所有 DEBUG 及以上级别的日志
                )
                logcatProcess = processBuilder.start()
                logcatReader = BufferedReader(InputStreamReader(logcatProcess?.inputStream))

                // 读取并记录日志
                var line: String? = null
                while (isCapturingLogcat.get() && logcatReader?.readLine().also { line = it } != null) {
                    line?.let { logLine ->
                        // 只记录当前应用的日志
                        // 通过进程ID或包名来判断是否属于当前应用
                        if (isCurrentAppLog(logLine, myPid, packageName)) {
                            writeSystemLogToFile(logLine)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start logcat capture", e)
            } finally {
                isCapturingLogcat.set(false)
            }
        }
    }

    /**
     * 判断日志是否属于当前应用
     * 通过检查进程ID或包名来过滤
     */
    private fun isCurrentAppLog(logLine: String, myPid: Int, packageName: String): Boolean {
        // logcat -v threadtime 格式: "MM-DD HH:MM:SS.mmm PID TID L TAG: message"
        // 例如: "03-10 14:23:45.678  1234  5678 D MyTag: log message"
        return try {
            // 检查是否包含当前进程的 PID
            val pidStr = myPid.toString()
            // 匹配 PID 在日志中的位置（通常是第3个字段）
            val parts = logLine.trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                // 检查第3个字段是否是当前 PID
                val logPid = parts[2]
                if (logPid == pidStr) {
                    return true
                }
            }
            // 如果不匹配 PID，检查是否包含包名
            logLine.contains(packageName) ||
            // 或者检查是否是常见的应用相关 TAG
            logLine.contains("AndroidRuntime") ||  // 崩溃日志
            logLine.contains("System.err")         // 错误输出
        } catch (e: Exception) {
            // 如果解析失败，默认记录该日志（避免遗漏）
            true
        }
    }

    /**
     * 将系统日志直接写入文件（不打印到 Logcat）
     */
    private fun writeSystemLogToFile(logMessage: String) {
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
            Log.e(TAG, "Failed to write system log to file", e)
        }
    }

    /**
     * 停止系统日志捕获
     */
    fun stopLogcatCapture() {
        isCapturingLogcat.set(false)
        try {
            logcatReader?.close()
            logcatProcess?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop logcat capture", e)
        }
    }
}
