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
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.databinding.ActivitySettingsBinding
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    companion object {
        const val PREFS_NAME = "music_settings"
        const val KEY_DEFAULT_SOURCE = "default_source"
        const val KEY_DOWNLOAD_PATH = "download_path"

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

        fun getDefaultDownloadPath(context: Context): String {
            return File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                "TackeMusic"
            ).absolutePath
        }
    }

    private val platformNames = mapOf(
        MusicRepository.Platform.KUWO to "酷我",
        MusicRepository.Platform.NETEASE to "网易"
    )

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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        updateDefaultSourceText()
        updateDownloadPathText()
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

    private fun showDownloadPathDialog() {
        val currentPath = getDownloadPath(this)
        val defaultPath = getDefaultDownloadPath(this)
        val options = arrayOf("使用默认位置", "自定义位置")
        val checkedItem = if (currentPath == null) 0 else 1

        AlertDialog.Builder(this)
            .setTitle("选择下载位置")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                when (which) {
                    0 -> {
                        setDownloadPath(this, null)
                        updateDownloadPathText()
                        Toast.makeText(this, "已恢复默认下载位置", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    1 -> {
                        dialog.dismiss()
                        openFolderPicker()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
