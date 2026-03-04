package com.tacke.music.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tacke.music.R
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.databinding.ActivitySettingsBinding
import com.tacke.music.databinding.DialogDownloadPathBinding
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    companion object {
        const val PREFS_NAME = "music_settings"
        const val KEY_DEFAULT_SOURCE = "default_source"
        const val KEY_DOWNLOAD_PATH = "download_path"
        const val KEY_LYRIC_COLOR = "lyric_color"
        const val KEY_CONCURRENT_DOWNLOADS = "concurrent_downloads"

        // 默认歌词颜色（青色）
        const val DEFAULT_LYRIC_COLOR = 0xFF00CED1.toInt()

        // 默认同时下载个数
        const val DEFAULT_CONCURRENT_DOWNLOADS = 3
        const val MIN_CONCURRENT_DOWNLOADS = 1
        const val MAX_CONCURRENT_DOWNLOADS = 5

        // 预设歌词颜色列表
        val PRESET_LYRIC_COLORS = listOf(
            0xFF00CED1.toInt(), // 深青色 (默认)
            0xFFFF4081.toInt(), // 粉红色
            0xFF4CAF50.toInt(), // 绿色
            0xFFFF9800.toInt(), // 橙色
            0xFF9C27B0.toInt(), // 紫色
            0xFF2196F3.toInt(), // 蓝色
            0xFFFFEB3B.toInt(), // 黄色
            0xFFF44336.toInt(), // 红色
            0xFFFFFFFF.toInt(), // 白色
            0xFF00BCD4.toInt(), // 青色
            0xFFE91E63.toInt(), // 玫红
            0xFF8BC34A.toInt()  // 浅绿
        )

        fun getDefaultSource(context: Context): MusicRepository.Platform {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val sourceName = prefs.getString(KEY_DEFAULT_SOURCE, MusicRepository.Platform.KUWO.name)
            return try {
                MusicRepository.Platform.valueOf(sourceName!!)
            } catch (e: Exception) {
                MusicRepository.Platform.KUWO
            }
        }

        fun setDefaultSource(context: Context, platform: MusicRepository.Platform) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_DEFAULT_SOURCE, platform.name).apply()
        }

        fun getDownloadPath(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_DOWNLOAD_PATH, null)
        }

        fun setDownloadPath(context: Context, path: String?) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_DOWNLOAD_PATH, path).apply()
        }

        fun getLyricColor(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_LYRIC_COLOR, DEFAULT_LYRIC_COLOR)
        }

        fun setLyricColor(context: Context, color: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_LYRIC_COLOR, color).apply()
        }

        fun getConcurrentDownloads(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_CONCURRENT_DOWNLOADS, DEFAULT_CONCURRENT_DOWNLOADS)
                .coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
        }

        fun setConcurrentDownloads(context: Context, count: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val validCount = count.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
            prefs.edit().putInt(KEY_CONCURRENT_DOWNLOADS, validCount).apply()
        }

        fun getDefaultDownloadPath(context: Context): String {
            return File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "TackeMusic"
            ).absolutePath
        }
    }

    private val platformNames = mapOf(
        MusicRepository.Platform.KUWO to "酷我",
        MusicRepository.Platform.NETEASE to "网易"
    )

    private var pendingCustomPath = false

    private val openDocumentTree = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                val path = uri.path?.let { getPathFromUri(uri) }
                if (path != null) {
                    setDownloadPath(this, path)
                    updateDownloadPathText()
                    Toast.makeText(this, "下载位置已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "无法获取路径", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // 用户取消了选择，如果之前是选择自定义路径，则回退到默认位置
            if (pendingCustomPath) {
                setDownloadPath(this, null)
                updateDownloadPathText()
                Toast.makeText(this, "已取消选择，使用默认下载位置", Toast.LENGTH_SHORT).show()
            }
        }
        pendingCustomPath = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        updateDefaultSourceText()
        updateDownloadPathText()
        updateLyricColorPreview()
        updateConcurrentDownloadsText()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.layoutDefaultSource.setOnClickListener {
            showSourceSelectorDialog()
        }

        binding.layoutDownloadPath.setOnClickListener {
            showDownloadPathDialog()
        }

        binding.layoutAbout.setOnClickListener {
            Toast.makeText(this, "TackeMusic - 音乐播放器", Toast.LENGTH_SHORT).show()
        }

        binding.layoutLyricColor.setOnClickListener {
            showLyricColorPickerDialog()
        }

        binding.layoutLogViewer.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        binding.layoutConcurrentDownloads.setOnClickListener {
            showConcurrentDownloadsDialog()
        }
    }

    private fun updateConcurrentDownloadsText() {
        val currentCount = getConcurrentDownloads(this)
        binding.tvConcurrentDownloadsValue.text = currentCount.toString()
    }

    private fun showConcurrentDownloadsDialog() {
        val currentCount = getConcurrentDownloads(this)
        val options = (MIN_CONCURRENT_DOWNLOADS..MAX_CONCURRENT_DOWNLOADS).map { "$it 个" }.toTypedArray()
        val currentIndex = currentCount - MIN_CONCURRENT_DOWNLOADS

        AlertDialog.Builder(this)
            .setTitle("选择同时下载个数")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selectedCount = which + MIN_CONCURRENT_DOWNLOADS
                setConcurrentDownloads(this, selectedCount)
                updateConcurrentDownloadsText()

                // 通知 DownloadManager 更新并发限制
                val downloadManager = com.tacke.music.download.DownloadManager.getInstance(this)
                downloadManager.updateConcurrentLimit(selectedCount)

                Toast.makeText(this, "同时下载个数已设置为: $selectedCount", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateLyricColorPreview() {
        val currentColor = getLyricColor(this)
        binding.viewLyricColorPreview.setBackgroundColor(currentColor)
    }

    private fun showLyricColorPickerDialog() {
        val currentColor = getLyricColor(this)
        val dialogView: android.view.View = layoutInflater.inflate(R.layout.dialog_lyric_color_picker, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择歌词颜色")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .create()

        // 获取颜色网格容器
        val colorGrid = dialogView.findViewById<android.widget.GridLayout>(R.id.colorGrid)
        val tvPreview = dialogView.findViewById<android.widget.TextView>(R.id.tvColorPreview)

        var selectedColor = currentColor

        // 更新预览文本颜色
        tvPreview?.setTextColor(selectedColor)

        // 创建颜色选择项
        PRESET_LYRIC_COLORS.forEach { color ->
            val colorView = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(8, 8, 8, 8)
                }

                // 颜色圆点
                val circleView = android.widget.ImageView(context).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(56, 56).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(color)
                    })
                }

                // 选中标记
                val checkView = android.widget.ImageView(context).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(24, 24).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(android.graphics.Color.WHITE)
                    visibility = if (color == currentColor) android.view.View.VISIBLE else android.view.View.GONE
                }

                addView(circleView)
                addView(checkView)

                setOnClickListener {
                    selectedColor = color
                    tvPreview?.setTextColor(selectedColor)

                    // 更新所有选中标记
                    for (i in 0 until colorGrid.childCount) {
                        val child = colorGrid.getChildAt(i) as? android.widget.FrameLayout
                        val check = child?.getChildAt(1) as? android.widget.ImageView
                        check?.visibility = if (i == PRESET_LYRIC_COLORS.indexOf(color))
                            android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
            }
            colorGrid?.addView(colorView)
        }

        // 确认按钮
        dialogView.findViewById<android.widget.Button>(R.id.btnConfirm)?.setOnClickListener {
            setLyricColor(this, selectedColor)
            updateLyricColorPreview()
            Toast.makeText(this, "歌词颜色已更新", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun getPathFromUri(uri: Uri): String? {
        return try {
            val docId = uri.path?.split(":")?.lastOrNull()
            if (docId != null) {
                File(Environment.getExternalStorageDirectory(), docId).absolutePath
            } else {
                uri.path
            }
        } catch (e: Exception) {
            uri.path
        }
    }

    private fun updateDefaultSourceText() {
        val currentSource = getDefaultSource(this)
        binding.tvDefaultSourceValue.text = platformNames[currentSource] ?: "酷我"
    }

    private fun updateDownloadPathText() {
        val customPath = getDownloadPath(this)
        val displayPath = customPath ?: getDefaultDownloadPath(this)
        binding.tvDownloadPathValue.text = displayPath
    }

    private var downloadPathDialog: AlertDialog? = null

    private fun showDownloadPathDialog() {
        val currentPath = getDownloadPath(this)
        val defaultPath = getDefaultDownloadPath(this)
        val displayPath = currentPath ?: defaultPath

        val dialogBinding = DialogDownloadPathBinding.inflate(layoutInflater)

        dialogBinding.tvCurrentPath.text = displayPath

        if (currentPath == null) {
            dialogBinding.radioDefault.isChecked = true
        } else {
            dialogBinding.radioCustom.isChecked = true
        }

        // 使用默认位置点击
        dialogBinding.layoutDefault.setOnClickListener {
            dialogBinding.radioDefault.isChecked = true
            dialogBinding.radioCustom.isChecked = false
            setDownloadPath(this, null)
            updateDownloadPathText()
            Toast.makeText(this, "已恢复默认下载位置", Toast.LENGTH_SHORT).show()
            downloadPathDialog?.dismiss()
        }

        // 自定义位置点击 - 立即弹出选择器
        dialogBinding.layoutCustom.setOnClickListener {
            dialogBinding.radioDefault.isChecked = false
            dialogBinding.radioCustom.isChecked = true
            pendingCustomPath = true
            downloadPathDialog?.dismiss()
            openFolderPicker()
        }

        downloadPathDialog = AlertDialog.Builder(this)
            .setTitle("选择下载位置")
            .setView(dialogBinding.root)
            .setNegativeButton("关闭", null)
            .create()

        downloadPathDialog?.show()
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        openDocumentTree.launch(intent)
    }

    private fun showSourceSelectorDialog() {
        val platforms = MusicRepository.Platform.values()
        val platformNamesArray = platforms.map { platformNames[it] ?: it.name }.toTypedArray()
        val currentSource = getDefaultSource(this)
        val currentIndex = platforms.indexOf(currentSource)

        AlertDialog.Builder(this)
            .setTitle("选择默认音源")
            .setSingleChoiceItems(platformNamesArray, currentIndex) { dialog, which ->
                val selectedPlatform = platforms[which]
                setDefaultSource(this, selectedPlatform)
                updateDefaultSourceText()
                Toast.makeText(this, "默认音源已设置为: ${platformNamesArray[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
