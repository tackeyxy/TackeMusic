package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.tacke.music.BuildConfig
import com.tacke.music.R
import com.tacke.music.databinding.ActivityNewVersionBinding
import com.tacke.music.update.ApkDownloadManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 新版本展示页面
 * 以现代化全屏设计展示新版本信息，替代原有的卡片覆盖形式
 */
class NewVersionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewVersionBinding
    private lateinit var apkDownloadManager: ApkDownloadManager

    companion object {
        private const val EXTRA_VERSION_NAME = "version_name"
        private const val EXTRA_VERSION_CODE = "version_code"
        private const val EXTRA_DOWNLOAD_URL = "download_url"
        private const val EXTRA_FILE_SIZE = "file_size"
        private const val EXTRA_PUBLISH_TIME = "publish_time"
        private const val EXTRA_RELEASE_NOTES = "release_notes"

        fun start(
            context: Context,
            versionName: String,
            versionCode: Int,
            downloadUrl: String,
            fileSize: String,
            publishTime: String,
            releaseNotes: String
        ) {
            val intent = Intent(context, NewVersionActivity::class.java).apply {
                putExtra(EXTRA_VERSION_NAME, versionName)
                putExtra(EXTRA_VERSION_CODE, versionCode)
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_FILE_SIZE, fileSize)
                putExtra(EXTRA_PUBLISH_TIME, publishTime)
                putExtra(EXTRA_RELEASE_NOTES, releaseNotes)
            }
            context.startActivity(intent)
        }
    }

    private var downloadUrl: String = ""
    private var versionCode: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewVersionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 16: 适配 Edge-to-Edge 模式
        setupEdgeToEdge()

        apkDownloadManager = ApkDownloadManager.getInstance(this)

        setupViews()
        displayVersionInfo()
        observeDownloadProgress()
    }

    private fun setupEdgeToEdge() {
        // 设置沉浸式状态栏 - 内容延伸到状态栏
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                bottom = insets.bottom
            )
            windowInsets
        }
    }

    private fun setupViews() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 下载更新按钮
        binding.btnDownloadUpdate.setOnClickListener {
            if (downloadUrl.isNotEmpty()) {
                startDownload(downloadUrl)
            }
        }

        // 忽略此版本按钮
        binding.btnIgnoreUpdate.setOnClickListener {
            if (versionCode > 0) {
                val prefs = getSharedPreferences("update_settings", Context.MODE_PRIVATE)
                prefs.edit().putInt("ignored_version_code", versionCode).apply()
                Toast.makeText(this, "已忽略此版本更新", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun displayVersionInfo() {
        // 获取传递的数据
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME) ?: ""
        versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
        downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: ""
        val fileSize = intent.getStringExtra(EXTRA_FILE_SIZE) ?: ""
        val publishTime = intent.getStringExtra(EXTRA_PUBLISH_TIME) ?: ""
        val releaseNotes = intent.getStringExtra(EXTRA_RELEASE_NOTES) ?: ""

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

    private fun observeDownloadProgress() {
        lifecycleScope.launch {
            apkDownloadManager.downloadProgress.collectLatest { progress ->
                binding.progressBar.progress = progress
                binding.tvProgressText.text = "$progress%"
                when {
                    progress == 0 -> binding.tvProgressDetail.text = "准备下载..."
                    progress < 100 -> binding.tvProgressDetail.text = "正在下载更新包..."
                    else -> binding.tvProgressDetail.text = "下载完成，准备安装..."
                }
            }
        }

        lifecycleScope.launch {
            apkDownloadManager.isDownloading.collectLatest { isDownloading ->
                binding.btnDownloadUpdate.isEnabled = !isDownloading
                binding.btnIgnoreUpdate.isEnabled = !isDownloading
                if (isDownloading) {
                    binding.btnDownloadUpdate.text = "下载中..."
                    binding.layoutProgress.visibility = View.VISIBLE
                } else {
                    binding.btnDownloadUpdate.text = "立即更新"
                    if (binding.progressBar.progress == 100) {
                        binding.layoutProgress.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun startDownload(downloadUrl: String) {
        apkDownloadManager.startDownload(downloadUrl) { success ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "下载完成，开始安装", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "下载失败，请重试", Toast.LENGTH_SHORT).show()
                    binding.btnDownloadUpdate.isEnabled = true
                    binding.btnIgnoreUpdate.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        apkDownloadManager.cancelDownload()
    }
}
