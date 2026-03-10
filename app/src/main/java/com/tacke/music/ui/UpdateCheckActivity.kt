package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.tacke.music.BuildConfig
import com.tacke.music.R
import com.tacke.music.data.model.VersionInfo
import com.tacke.music.data.repository.VersionRepository
import com.tacke.music.databinding.ActivityUpdateCheckBinding
import com.tacke.music.util.AppLogger
import com.tacke.music.util.ImmersiveStatusBarHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class UpdateCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateCheckBinding
    private lateinit var versionRepository: VersionRepository

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
    private var checkTimeoutHandler: Handler? = null
    private val CHECK_TIMEOUT_MS = 15000L // 15秒超时

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置沉浸式状态栏 - 使用基础模式（浅色背景，深色状态栏图标）
        ImmersiveStatusBarHelper.setup(
            activity = this,
            lightStatusBar = true,
            lightNavigationBar = true
        )

        // 为 Toolbar 添加状态栏高度 padding
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = insets.top)
            windowInsets
        }

        // 先初始化 Repository
        versionRepository = VersionRepository.getInstance(this)

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
            // 如果有已检测到的版本信息，点击跳转到新版本页面
            if (currentVersionInfo != null) {
                goToNewVersionPage(currentVersionInfo!!)
            } else {
                // 否则执行检测
                checkForUpdate()
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
        // 隐藏之前的所有状态
        binding.layoutNoUpdate.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.tvNewBadge.visibility = View.GONE
        // 清除之前保存的版本信息
        currentVersionInfo = null

        binding.progressBarChecking.visibility = View.VISIBLE
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = ""

        // 设置超时处理
        checkTimeoutHandler?.removeCallbacksAndMessages(null)
        checkTimeoutHandler = Handler(Looper.getMainLooper())
        checkTimeoutHandler?.postDelayed({
            if (isChecking) {
                isChecking = false
                binding.progressBarChecking.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = "检查更新"
                showError("检测超时，请检查网络连接后重试")
            }
        }, CHECK_TIMEOUT_MS)

        lifecycleScope.launch {
            try {
                // 设置版本检查 URL
                versionRepository.setVersionUrl(getVersionUrl(this@UpdateCheckActivity))

                // 使用超时机制
                val result = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                    versionRepository.checkForUpdate(BuildConfig.VERSION_CODE)
                }

                // 取消超时处理
                checkTimeoutHandler?.removeCallbacksAndMessages(null)

                if (result == null) {
                    // 超时
                    isChecking = false
                    binding.progressBarChecking.visibility = View.GONE
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.text = "检查更新"
                    showError("检测超时，请检查网络连接后重试")
                    return@launch
                }

                result.fold(
                    onSuccess = { versionInfo ->
                        binding.progressBarChecking.visibility = View.GONE
                        binding.btnCheckUpdate.isEnabled = true
                        isChecking = false

                        if (versionInfo != null) {
                            // 检查远程版本是否低于当前版本（异常情况）
                            if (versionInfo.versionCode < BuildConfig.VERSION_CODE) {
                                // 远程版本低于当前版本，按无更新处理
                                AppLogger.w(TAG, "Remote version ${versionInfo.versionCode} is lower than current ${BuildConfig.VERSION_CODE}")
                                binding.btnCheckUpdate.text = "检查更新"
                                showNoUpdate()
                            } else {
                                // 有新版本
                                currentVersionInfo = versionInfo
                                binding.btnCheckUpdate.text = "查看更新"
                                showUpdateAvailable(versionInfo)
                            }
                        } else {
                            // 没有新版本
                            binding.btnCheckUpdate.text = "检查更新"
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
                // 取消超时处理
                checkTimeoutHandler?.removeCallbacksAndMessages(null)
                AppLogger.e(TAG, "Error checking update", e)
                binding.progressBarChecking.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = "检查更新"
                isChecking = false
                showError("检查更新失败: ${e.message}")
            }
        }
    }

    /**
     * 显示有新版本可用（在当前页面显示，不自动跳转）
     */
    private fun showUpdateAvailable(versionInfo: VersionInfo) {
        // 显示 NEW 标识
        binding.tvNewBadge.visibility = View.VISIBLE

        // 隐藏其他状态
        binding.layoutNoUpdate.visibility = View.GONE
        binding.layoutError.visibility = View.GONE

        // 显示透明背景提示
        showTransparentToast("检测到新版本")
    }

    /**
     * 显示透明背景提示
     */
    private fun showTransparentToast(message: String) {
        // 创建透明背景的 Toast
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.show()
    }

    /**
     * 跳转到新版本页面（点击按钮时调用）
     */
    private fun goToNewVersionPage(versionInfo: VersionInfo) {
        NewVersionActivity.start(
            context = this,
            versionName = versionInfo.versionName,
            versionCode = versionInfo.versionCode,
            downloadUrl = versionInfo.downloadUrl,
            fileSize = versionInfo.fileSize,
            publishTime = versionInfo.publishTime,
            releaseNotes = versionInfo.releaseNotes,
            isForceUpdate = false
        )
    }

    private fun showNoUpdate() {
        binding.layoutNoUpdate.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE
        currentVersionInfo = null
    }

    private fun showError(message: String) {
        binding.layoutNoUpdate.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE
        binding.tvErrorMessage.text = message
        currentVersionInfo = null
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
        // 清理超时 Handler
        checkTimeoutHandler?.removeCallbacksAndMessages(null)
    }
}
