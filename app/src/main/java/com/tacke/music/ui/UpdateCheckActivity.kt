package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tacke.music.BuildConfig
import com.tacke.music.R
import com.tacke.music.data.model.VersionInfo
import com.tacke.music.data.repository.VersionRepository
import com.tacke.music.databinding.ActivityUpdateCheckBinding
import com.tacke.music.update.ApkDownloadManager
import com.tacke.music.util.AppLogger
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UpdateCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateCheckBinding
    private lateinit var versionRepository: VersionRepository
    private lateinit var apkDownloadManager: ApkDownloadManager

    companion object {
        private const val TAG = "UpdateCheckActivity"
        private const val PREFS_NAME = "update_settings"
        private const val KEY_UPDATE_CHANNEL = "update_channel"

        // 版本通道
        const val CHANNEL_STABLE = "stable"
        const val CHANNEL_DEV = "dev"

        // 稳定版 URL
        private const val URL_STABLE = "https://raw.githubusercontent.com/tackeyxy/TackeMusic/main/version.json"
        // 开发版 URL
        private const val URL_DEV = "https://raw.githubusercontent.com/tackeyxy/TackeMusic/dev/version.json"

        fun getUpdateChannel(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_UPDATE_CHANNEL, CHANNEL_STABLE) ?: CHANNEL_STABLE
        }

        fun setUpdateChannel(context: Context, channel: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_UPDATE_CHANNEL, channel).apply()
        }

        fun getVersionUrl(context: Context): String {
            return when (getUpdateChannel(context)) {
                CHANNEL_DEV -> URL_DEV
                else -> URL_STABLE
            }
        }
    }

    private var currentVersionInfo: VersionInfo? = null
    private var isChecking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 先初始化 Repository 和 DownloadManager
        versionRepository = VersionRepository.getInstance(this)
        apkDownloadManager = ApkDownloadManager.getInstance(this)

        setupToolbar()
        setupViews()

        // 显示当前版本信息
        displayCurrentVersion()

        // 自动检查更新
        checkForUpdate()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "检测更新"
        }
    }

    private fun setupViews() {
        // 检查更新按钮
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdate()
        }

        // 下载更新按钮
        binding.btnDownloadUpdate.setOnClickListener {
            currentVersionInfo?.let { info ->
                startDownload(info.downloadUrl)
            }
        }

        // 监听下载进度
        lifecycleScope.launch {
            apkDownloadManager.downloadProgress.collectLatest { progress ->
                binding.progressBar.progress = progress
                binding.tvProgressText.text = "$progress%"
            }
        }

        // 监听下载状态
        lifecycleScope.launch {
            apkDownloadManager.isDownloading.collectLatest { isDownloading ->
                binding.btnDownloadUpdate.isEnabled = !isDownloading
                if (isDownloading) {
                    binding.btnDownloadUpdate.text = "下载中..."
                    binding.layoutProgress.visibility = View.VISIBLE
                } else {
                    binding.btnDownloadUpdate.text = "下载更新"
                    if (binding.progressBar.progress == 100) {
                        binding.layoutProgress.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun displayCurrentVersion() {
        binding.tvCurrentVersion.text = "v${BuildConfig.VERSION_NAME}"
        binding.tvCurrentChannel.text = getChannelDisplayName(getUpdateChannel(this))
        binding.chipChannelIndicator.text = getChannelDisplayName(getUpdateChannel(this))
    }

    private fun getChannelDisplayName(channel: String): String {
        return when (channel) {
            CHANNEL_DEV -> "开发版"
            else -> "稳定版"
        }
    }

    private fun checkForUpdate() {
        if (isChecking) return

        isChecking = true
        binding.layoutUpdateInfo.visibility = View.GONE
        binding.layoutNoUpdate.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.progressBarChecking.visibility = View.VISIBLE
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = ""

        lifecycleScope.launch {
            try {
                // 设置版本检查 URL
                versionRepository.setVersionUrl(getVersionUrl(this@UpdateCheckActivity))

                val result = versionRepository.checkForUpdate(BuildConfig.VERSION_CODE)

                result.fold(
                    onSuccess = { versionInfo ->
                        binding.progressBarChecking.visibility = View.GONE
                        binding.btnCheckUpdate.isEnabled = true
                        binding.btnCheckUpdate.text = "检查更新"
                        isChecking = false

                        if (versionInfo != null) {
                            // 有新版本
                            currentVersionInfo = versionInfo
                            showUpdateInfo(versionInfo)
                        } else {
                            // 没有新版本
                            showNoUpdate()
                        }
                    },
                    onFailure = { error ->
                        binding.progressBarChecking.visibility = View.GONE
                        binding.btnCheckUpdate.isEnabled = true
                        binding.btnCheckUpdate.text = "检查更新"
                        isChecking = false
                        showError(error.message ?: "检查更新失败")
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error checking update", e)
                binding.progressBarChecking.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = "检查更新"
                isChecking = false
                showError("检查更新失败: ${e.message}")
            }
        }
    }

    private fun showUpdateInfo(versionInfo: VersionInfo) {
        binding.layoutUpdateInfo.visibility = View.VISIBLE
        binding.layoutNoUpdate.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        binding.tvNewVersion.text = "v${versionInfo.versionName}"
        binding.chipFileSize.text = versionInfo.fileSize
        binding.chipPublishTime.text = versionInfo.publishTime
        binding.tvReleaseNotes.text = versionInfo.releaseNotes

        binding.btnDownloadUpdate.visibility = View.VISIBLE
    }

    private fun showNoUpdate() {
        binding.layoutUpdateInfo.visibility = View.GONE
        binding.layoutNoUpdate.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE
        binding.btnDownloadUpdate.visibility = View.GONE
        currentVersionInfo = null
    }

    private fun showError(message: String) {
        binding.layoutUpdateInfo.visibility = View.GONE
        binding.layoutNoUpdate.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE
        binding.tvErrorMessage.text = message
        binding.btnDownloadUpdate.visibility = View.GONE
        currentVersionInfo = null
    }

    private fun startDownload(downloadUrl: String) {
        apkDownloadManager.startDownload(downloadUrl) { success ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "下载完成，开始安装", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "下载失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_update_check, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_switch_channel -> {
                showChannelSwitchDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showChannelSwitchDialog() {
        val currentChannel = getUpdateChannel(this)
        val channels = arrayOf("稳定版", "开发版")
        val channelValues = arrayOf(CHANNEL_STABLE, CHANNEL_DEV)
        val currentIndex = channelValues.indexOf(currentChannel)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择版本通道")
            .setSingleChoiceItems(channels, currentIndex) { dialog, which ->
                val selectedChannel = channelValues[which]
                if (selectedChannel != currentChannel) {
                    setUpdateChannel(this, selectedChannel)
                    displayCurrentVersion()
                    Toast.makeText(this, "已切换到${channels[which]}", Toast.LENGTH_SHORT).show()
                    // 重新检查更新
                    checkForUpdate()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        apkDownloadManager.cancelDownload()
    }
}
