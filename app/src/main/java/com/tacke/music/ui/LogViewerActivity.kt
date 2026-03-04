package com.tacke.music.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.databinding.ActivityLogViewerBinding
import com.tacke.music.util.AppLogger
import com.tacke.music.util.NetworkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志查看器 Activity
 * 用于查看、管理和导出应用日志
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private lateinit var appLogger: AppLogger
    private lateinit var networkLogger: NetworkLogger

    private var currentLogType = LOG_TYPE_APP
    private var autoRefresh = false

    companion object {
        const val LOG_TYPE_APP = 0
        const val LOG_TYPE_NETWORK = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appLogger = AppLogger.getInstance(this)
        networkLogger = NetworkLogger.getInstance(this)

        setupToolbar()
        setupUI()
        loadLogs()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "日志管理"
    }

    private fun setupUI() {
        // 日志类型切换
        binding.radioGroupLogType.setOnCheckedChangeListener { _, checkedId ->
            currentLogType = when (checkedId) {
                R.id.radioAppLog -> LOG_TYPE_APP
                R.id.radioNetworkLog -> LOG_TYPE_NETWORK
                else -> LOG_TYPE_APP
            }
            loadLogs()
        }

        // 刷新按钮
        binding.btnRefresh.setOnClickListener {
            loadLogs()
            Toast.makeText(this, "日志已刷新", Toast.LENGTH_SHORT).show()
        }

        // 清除日志按钮
        binding.btnClear.setOnClickListener {
            showClearConfirmDialog()
        }

        // 导出日志按钮
        binding.btnExport.setOnClickListener {
            exportLogs()
        }

        // 日志级别选择
        binding.spinnerLogLevel.setSelection(appLogger.getLogLevel())
        binding.spinnerLogLevel.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    appLogger.setLogLevel(position)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) {
                when (currentLogType) {
                    LOG_TYPE_APP -> appLogger.readLogs(1000)
                    LOG_TYPE_NETWORK -> readNetworkLogs()
                    else -> "暂无日志"
                }
            }

            binding.tvLogs.text = logs.ifEmpty { "暂无日志" }
            updateLogInfo()
        }
    }

    private fun readNetworkLogs(): String {
        return try {
            val logFile = File(networkLogger.getLogFilePath())
            if (!logFile.exists()) {
                return "暂无网络日志"
            }
            val lines = logFile.readLines()
            if (lines.size > 1000) {
                lines.takeLast(1000).joinToString("\n")
            } else {
                lines.joinToString("\n")
            }
        } catch (e: Exception) {
            "读取网络日志失败: ${e.message}"
        }
    }

    private fun updateLogInfo() {
        lifecycleScope.launch {
            val (appSize, networkSize, totalSize) = withContext(Dispatchers.IO) {
                val appSize = appLogger.getTotalLogSize()
                val networkSize = getNetworkLogSize()
                val totalSize = appSize + networkSize
                Triple(appSize, networkSize, totalSize)
            }

            binding.tvLogInfo.text = buildString {
                appendLine("应用日志: ${formatFileSize(appSize)}")
                appendLine("网络日志: ${formatFileSize(networkSize)}")
                appendLine("总大小: ${formatFileSize(totalSize)}")
                appendLine("日志路径: ${appLogger.getLogDirectoryPath()}")
            }
        }
    }

    private fun getNetworkLogSize(): Long {
        return try {
            val logFile = File(networkLogger.getLogFilePath())
            if (logFile.exists()) logFile.length() else 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.2f MB", size / (1024.0 * 1024.0))
        }
    }

    private fun showClearConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清除日志")
            .setMessage("确定要清除所有${if (currentLogType == LOG_TYPE_APP) "应用" else "网络"}日志吗？")
            .setPositiveButton("清除") { _, _ ->
                clearCurrentLogs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearCurrentLogs() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                when (currentLogType) {
                    LOG_TYPE_APP -> appLogger.clearLogs()
                    LOG_TYPE_NETWORK -> networkLogger.clearLogs()
                }
            }
            loadLogs()
            Toast.makeText(this@LogViewerActivity, "日志已清除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportLogs() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val exportDir = File(getExternalFilesDir(null), "exports")
        val exportFile = File(exportDir, "tacke_music_logs_$timestamp.txt")

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    exportDir.mkdirs()
                    val content = StringBuilder()

                    content.appendLine("========== Tacke Music 日志导出 ==========")
                    content.appendLine("导出时间: ${Date()}")
                    content.appendLine("==========================================")
                    content.appendLine()

                    // 导出应用日志
                    content.appendLine("========== 应用日志 ==========")
                    content.appendLine(appLogger.readLogs(5000))
                    content.appendLine()

                    // 导出网络日志
                    content.appendLine("========== 网络日志 ==========")
                    content.appendLine(readNetworkLogs())

                    exportFile.writeText(content.toString())
                    true
                } catch (e: Exception) {
                    false
                }
            }

            if (success) {
                Toast.makeText(this@LogViewerActivity, "日志已导出到: ${exportFile.absolutePath}", Toast.LENGTH_LONG).show()
                shareLogFile(exportFile)
            } else {
                Toast.makeText(this@LogViewerActivity, "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareLogFile(file: File) {
        try {
            val uri = Uri.fromFile(file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Tacke Music 日志")
            }
            startActivity(Intent.createChooser(intent, "分享日志"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_log_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_auto_refresh -> {
                autoRefresh = !autoRefresh
                item.isChecked = autoRefresh
                Toast.makeText(this, if (autoRefresh) "自动刷新已开启" else "自动刷新已关闭", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_clear_all_logs -> {
                showClearAllLogsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showClearAllLogsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清除所有日志")
            .setMessage("确定要清除应用日志和网络日志吗？此操作不可恢复。")
            .setPositiveButton("全部清除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        appLogger.clearLogs()
                        networkLogger.clearLogs()
                    }
                    loadLogs()
                    Toast.makeText(this@LogViewerActivity, "所有日志已清除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (autoRefresh) {
            loadLogs()
        }
    }
}
