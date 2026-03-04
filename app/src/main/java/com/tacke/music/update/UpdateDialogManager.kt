package com.tacke.music.update

import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.tacke.music.R
import com.tacke.music.data.model.VersionInfo
import com.tacke.music.data.repository.VersionRepository
import com.tacke.music.databinding.DialogUpdateBinding
import com.tacke.music.util.AppLogger
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UpdateDialogManager(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope
) {

    companion object {
        private const val TAG = "UpdateDialogManager"
    }

    private val versionRepository = VersionRepository.getInstance(activity)
    private val apkDownloadManager = ApkDownloadManager.getInstance(activity)
    private var currentDialog: AlertDialog? = null

    /**
     * 显示更新对话框
     * @param versionInfo 版本信息
     * @param isManualCheck 是否为手动检查（手动检查时忽略忽略记录）
     */
    fun showUpdateDialog(versionInfo: VersionInfo, isManualCheck: Boolean = false) {
        // 关闭之前的对话框
        currentDialog?.dismiss()

        val binding = DialogUpdateBinding.inflate(LayoutInflater.from(activity))

        // 设置版本信息
        binding.tvVersion.text = "v${versionInfo.versionName}"
        binding.tvFileSize.text = versionInfo.fileSize
        binding.tvPublishTime.text = versionInfo.publishTime
        binding.tvReleaseNotes.text = versionInfo.releaseNotes

        val dialog = AlertDialog.Builder(activity, R.style.DialogTheme)
            .setView(binding.root)
            .setCancelable(!isManualCheck) // 手动检查时不可取消
            .create()

        currentDialog = dialog

        // 忽略更新按钮
        binding.btnIgnore.setOnClickListener {
            if (!isManualCheck) {
                // 记录忽略的版本号
                versionRepository.setIgnoredVersionCode(versionInfo.versionCode)
                Toast.makeText(activity, "已忽略此版本更新", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        // 下载更新按钮
        binding.btnDownload.setOnClickListener {
            binding.btnIgnore.isEnabled = false
            binding.btnDownload.isEnabled = false
            binding.layoutProgress.visibility = android.view.View.VISIBLE

            // 开始下载
            apkDownloadManager.startDownload(versionInfo.downloadUrl) { success ->
                activity.runOnUiThread {
                    if (success) {
                        Toast.makeText(activity, "下载完成，开始安装", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "下载失败，请重试", Toast.LENGTH_SHORT).show()
                        binding.btnIgnore.isEnabled = true
                        binding.btnDownload.isEnabled = true
                        binding.layoutProgress.visibility = android.view.View.GONE
                    }
                }
            }
        }

        // 监听下载进度
        lifecycleScope.launch {
            apkDownloadManager.downloadProgress.collectLatest { progress ->
                activity.runOnUiThread {
                    binding.progressBar.progress = progress
                    binding.tvProgressText.text = "下载中... $progress%"
                }
            }
        }

        // 监听下载状态
        lifecycleScope.launch {
            apkDownloadManager.isDownloading.collectLatest { isDownloading ->
                activity.runOnUiThread {
                    if (isDownloading) {
                        binding.btnDownload.text = "下载中..."
                    } else {
                        binding.btnDownload.text = "下载更新"
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            // 如果正在下载，取消下载
            if (apkDownloadManager.isDownloading.value) {
                apkDownloadManager.cancelDownload()
            }
        }

        dialog.show()
    }

    /**
     * 检查更新
     * @param currentVersionCode 当前版本号
     * @param isManualCheck 是否为手动检查
     * @param onCheckComplete 检查完成回调（返回是否有更新）
     */
    fun checkForUpdate(
        currentVersionCode: Int,
        isManualCheck: Boolean = false,
        onCheckComplete: (Boolean) -> Unit = {}
    ) {
        lifecycleScope.launch {
            try {
                // 手动检查时清除忽略记录
                if (isManualCheck) {
                    versionRepository.clearIgnoredVersion()
                }

                val result = versionRepository.checkForUpdate(currentVersionCode)

                result.fold(
                    onSuccess = { versionInfo ->
                        if (versionInfo != null) {
                            // 有新版本
                            AppLogger.d(TAG, "New version available: ${versionInfo.versionName}")
                            showUpdateDialog(versionInfo, isManualCheck)
                            onCheckComplete(true)
                        } else {
                            // 没有新版本或已忽略
                            if (isManualCheck) {
                                Toast.makeText(activity, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                            }
                            AppLogger.d(TAG, "No new version or ignored")
                            onCheckComplete(false)
                        }
                    },
                    onFailure = { error ->
                        AppLogger.e(TAG, "Failed to check update", error)
                        if (isManualCheck) {
                            Toast.makeText(activity, "检查更新失败: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                        onCheckComplete(false)
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error checking update", e)
                if (isManualCheck) {
                    Toast.makeText(activity, "检查更新失败", Toast.LENGTH_SHORT).show()
                }
                onCheckComplete(false)
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        currentDialog?.dismiss()
        currentDialog = null
    }
}