package com.tacke.music.update

import android.app.Activity
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.tacke.music.data.model.VersionInfo
import com.tacke.music.data.repository.VersionRepository
import com.tacke.music.ui.NewVersionActivity
import com.tacke.music.util.AppLogger
import kotlinx.coroutines.launch

/**
 * 更新检查管理器
 * 负责检查更新并跳转到新版本页面展示更新信息
 */
class UpdateDialogManager(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope
) {

    companion object {
        private const val TAG = "UpdateDialogManager"
    }

    private val versionRepository = VersionRepository.getInstance(activity)
    private val apkDownloadManager = ApkDownloadManager.getInstance(activity)

    /**
     * 显示更新信息 - 跳转到新版本页面
     * @param versionInfo 版本信息
     * @param isManualCheck 是否为手动检查（手动检查时忽略忽略记录）
     */
    fun showUpdateDialog(versionInfo: VersionInfo, isManualCheck: Boolean = false) {
        AppLogger.d(TAG, "Showing update dialog for version: ${versionInfo.versionName}")

        // 跳转到新版本页面
        NewVersionActivity.start(
            context = activity,
            versionName = versionInfo.versionName,
            versionCode = versionInfo.versionCode,
            downloadUrl = versionInfo.downloadUrl,
            fileSize = versionInfo.fileSize,
            publishTime = versionInfo.publishTime,
            releaseNotes = versionInfo.releaseNotes,
            isForceUpdate = false
        )
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
        // 清理资源
        apkDownloadManager.cancelDownload()
    }
}
