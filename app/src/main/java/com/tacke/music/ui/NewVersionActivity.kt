package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tacke.music.BuildConfig
import com.tacke.music.R
import com.tacke.music.databinding.ActivityNewVersionBinding
import com.tacke.music.update.ApkDownloadManager
import com.tacke.music.util.AppLogger
import com.tacke.music.util.ImmersiveStatusBarHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DecimalFormat

/**
 * 新版本展示页面
 * 以现代化全屏设计展示新版本信息，替代原有的对话框形式
 * 功能：
 * 1. 展示新版本信息（版本号、文件大小、发布时间、更新日志）
 * 2. 忽略此版本（记录版本号，后续不再提示此版本，但更新的版本不受影响）
 * 3. 立即下载（显示下载进度条和速度，下载完成后自动安装）
 * 4. 沉浸式状态栏
 */
class NewVersionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewVersionBinding
    private lateinit var apkDownloadManager: ApkDownloadManager

    companion object {
        private const val TAG = "NewVersionActivity"
        private const val EXTRA_VERSION_NAME = "version_name"
        private const val EXTRA_VERSION_CODE = "version_code"
        private const val EXTRA_DOWNLOAD_URL = "download_url"
        private const val EXTRA_FILE_SIZE = "file_size"
        private const val EXTRA_PUBLISH_TIME = "publish_time"
        private const val EXTRA_RELEASE_NOTES = "release_notes"
        private const val EXTRA_IS_FORCE_UPDATE = "is_force_update"
        private const val PREFS_NAME = "update_settings"
        private const val KEY_IGNORED_VERSION_CODE = "ignored_version_code"

        /**
         * 启动新版本页面
         *
         * @param context 上下文
         * @param versionName 版本名称
         * @param versionCode 版本号
         * @param downloadUrl 下载链接
         * @param fileSize 文件大小
         * @param publishTime 发布时间
         * @param releaseNotes 更新日志
         * @param isForceUpdate 是否强制更新
         */
        fun start(
            context: Context,
            versionName: String,
            versionCode: Int,
            downloadUrl: String,
            fileSize: String,
            publishTime: String,
            releaseNotes: String,
            isForceUpdate: Boolean = false
        ) {
            val intent = Intent(context, NewVersionActivity::class.java).apply {
                putExtra(EXTRA_VERSION_NAME, versionName)
                putExtra(EXTRA_VERSION_CODE, versionCode)
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_FILE_SIZE, fileSize)
                putExtra(EXTRA_PUBLISH_TIME, publishTime)
                putExtra(EXTRA_RELEASE_NOTES, releaseNotes)
                putExtra(EXTRA_IS_FORCE_UPDATE, isForceUpdate)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }

        /**
         * 获取已忽略的版本号
         */
        fun getIgnoredVersionCode(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_IGNORED_VERSION_CODE, 0)
        }

        /**
         * 设置忽略的版本号
         */
        fun setIgnoredVersionCode(context: Context, versionCode: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_IGNORED_VERSION_CODE, versionCode).apply()
            AppLogger.d(TAG, "Ignored version code set to: $versionCode")
        }

        /**
         * 清除忽略的版本记录
         */
        fun clearIgnoredVersion(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_IGNORED_VERSION_CODE).apply()
            AppLogger.d(TAG, "Cleared ignored version")
        }
    }

    private var downloadUrl: String = ""
    private var versionCode: Int = 0
    private var isForceUpdate: Boolean = false

    // 下载速度计算
    private var lastDownloadedBytes: Long = 0
    private var lastSpeedUpdateTime: Long = 0

    private val unknownSourcesSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            tryInstallDownloadedApk()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewVersionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置沉浸式状态栏 - 使用渐变背景模式
        ImmersiveStatusBarHelper.setupWithGradientBackground(
            activity = this,
            headerViewId = R.id.toolbar,
            contentViewId = R.id.scrollContent
        )

        apkDownloadManager = ApkDownloadManager.getInstance(this)

        // 获取传递的数据
        parseIntentData()

        setupViews()
        displayVersionInfo()
        observeDownloadProgress()
    }

    private fun parseIntentData() {
        intent?.let {
            downloadUrl = it.getStringExtra(EXTRA_DOWNLOAD_URL) ?: ""
            versionCode = it.getIntExtra(EXTRA_VERSION_CODE, 0)
            isForceUpdate = it.getBooleanExtra(EXTRA_IS_FORCE_UPDATE, false)
        }
    }

    private fun setupViews() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            if (!isForceUpdate) {
                finish()
            } else {
                Toast.makeText(this, "请更新到最新版本", Toast.LENGTH_SHORT).show()
            }
        }

        // 立即下载按钮
        binding.btnDownloadUpdate.setOnClickListener {
            if (downloadUrl.isNotEmpty()) {
                startDownload(downloadUrl)
            } else {
                Toast.makeText(this, "下载链接无效", Toast.LENGTH_SHORT).show()
            }
        }

        // 忽略此版本按钮
        binding.btnIgnoreUpdate.setOnClickListener {
            if (versionCode > 0) {
                setIgnoredVersionCode(this, versionCode)
                Toast.makeText(this, "已忽略此版本更新", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // 如果是强制更新，隐藏忽略按钮和返回按钮
        if (isForceUpdate) {
            binding.btnIgnoreUpdate.visibility = View.GONE
            binding.btnBack.visibility = View.GONE
        }
    }

    private fun displayVersionInfo() {
        intent?.let {
            val versionName = it.getStringExtra(EXTRA_VERSION_NAME) ?: ""
            val fileSize = it.getStringExtra(EXTRA_FILE_SIZE) ?: ""
            val publishTime = it.getStringExtra(EXTRA_PUBLISH_TIME) ?: ""
            val releaseNotes = it.getStringExtra(EXTRA_RELEASE_NOTES) ?: ""

            // 新版本号
            binding.tvNewVersion.text = "v$versionName"

            // 文件大小和发布时间
            binding.chipFileSize.text = fileSize
            binding.chipPublishTime.text = publishTime

            // 当前版本
            binding.tvCurrentVersion.text = "v${BuildConfig.VERSION_NAME}"
            binding.tvCurrentVersionNew.text = "v$versionName"

            // 更新日志
            binding.tvReleaseNotes.text = releaseNotes
        }
    }

    private fun observeDownloadProgress() {
        lifecycleScope.launch {
            apkDownloadManager.downloadProgress.collectLatest { progress ->
                updateProgressUI(progress)
            }
        }

        lifecycleScope.launch {
            apkDownloadManager.isDownloading.collectLatest { isDownloading ->
                updateDownloadStateUI(isDownloading)
            }
        }

        lifecycleScope.launch {
            apkDownloadManager.downloadSpeed.collectLatest { speed ->
                updateSpeedUI(speed)
            }
        }
    }

    private fun updateProgressUI(progress: Int) {
        binding.progressBar.progress = progress
        binding.tvProgressText.text = "$progress%"

        when {
            progress == 0 -> binding.tvProgressDetail.text = "准备下载..."
            progress < 100 -> binding.tvProgressDetail.text = "正在下载更新包..."
            else -> binding.tvProgressDetail.text = "下载完成，准备安装..."
        }
    }

    private fun updateDownloadStateUI(isDownloading: Boolean) {
        binding.btnDownloadUpdate.isEnabled = !isDownloading
        binding.btnIgnoreUpdate.isEnabled = !isDownloading

        if (isDownloading) {
            binding.btnDownloadUpdate.text = "下载中..."
            binding.layoutProgress.visibility = View.VISIBLE
            lastDownloadedBytes = 0
            lastSpeedUpdateTime = System.currentTimeMillis()
        } else {
            binding.btnDownloadUpdate.text = "立即更新"
            if (binding.progressBar.progress == 100) {
                binding.tvProgressDetail.text = "下载完成"
            }
        }
    }

    private fun updateSpeedUI(speed: Long) {
        val speedText = formatSpeed(speed)
        val progress = binding.progressBar.progress
        if (progress in 1..99) {
            binding.tvProgressDetail.text = "正在下载更新包... $speedText"
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        val df = DecimalFormat("0.0")
        return when {
            bytesPerSecond < 1024 -> "${bytesPerSecond}B/s"
            bytesPerSecond < 1024 * 1024 -> "${df.format(bytesPerSecond / 1024.0)}KB/s"
            else -> "${df.format(bytesPerSecond / (1024.0 * 1024.0))}MB/s"
        }
    }

    private fun startDownload(downloadUrl: String) {
        apkDownloadManager.startDownload(downloadUrl) { success ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "下载完成", Toast.LENGTH_SHORT).show()
                    tryInstallDownloadedApk()
                } else {
                    Toast.makeText(this, "下载失败，请重试", Toast.LENGTH_SHORT).show()
                    binding.btnDownloadUpdate.isEnabled = true
                    binding.btnIgnoreUpdate.isEnabled = true
                    binding.btnDownloadUpdate.text = "立即更新"
                }
            }
        }
    }

    private fun tryInstallDownloadedApk() {
        if (apkDownloadManager.canRequestPackageInstalls()) {
            val started = apkDownloadManager.installApk(this)
            if (!started) {
                Toast.makeText(this, "无法启动安装器，请手动安装", Toast.LENGTH_LONG).show()
            }
            return
        }

        Toast.makeText(this, "请先允许“安装未知应用”权限", Toast.LENGTH_LONG).show()
        val opened = openUnknownAppSourcesSettingsWithResult()
        if (!opened) {
            Toast.makeText(this, "无法打开安装权限设置，请手动前往系统设置开启", Toast.LENGTH_LONG).show()
        }
    }

    private fun openUnknownAppSourcesSettingsWithResult(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                unknownSourcesSettingsLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                )
                return true
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to open ACTION_MANAGE_UNKNOWN_APP_SOURCES: ${e.message}")
            }
        }

        // 厂商ROM兜底：交由管理器依次尝试多个设置入口
        return try {
            apkDownloadManager.openUnknownSourcesSettings(this)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to open fallback settings: ${e.message}")
            false
        }
    }

    override fun onBackPressed() {
        if (isForceUpdate) {
            Toast.makeText(this, "请更新到最新版本", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 如果正在下载，不取消，让用户可以在后台继续下载
        // 只有在页面销毁且下载完成或失败时才清理
        if (!apkDownloadManager.isDownloading.value) {
            apkDownloadManager.cancelDownload()
        }
    }
}
