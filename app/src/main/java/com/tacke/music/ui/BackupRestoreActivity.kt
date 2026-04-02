package com.tacke.music.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.tacke.music.MusicApplication
import com.tacke.music.data.repository.UserDataBackupRepository
import com.tacke.music.databinding.ActivityBackupRestoreBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupRestoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupRestoreBinding
    private val userDataBackupRepository by lazy { UserDataBackupRepository(this) }

    private val createBackupDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportUserDataBackup(uri)
        }
    }

    private val openBackupDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importUserDataBackup(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupRestoreBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.layoutExportUserData.setOnClickListener {
            startExportBackupFlow()
        }

        binding.layoutImportUserData.setOnClickListener {
            showImportConfirmDialog()
        }
    }

    private fun startExportBackupFlow() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        createBackupDocument.launch("tacke_music_backup_$timestamp.json")
    }

    private fun exportUserDataBackup(uri: Uri) {
        lifecycleScope.launch {
            try {
                val backupJson = withContext(Dispatchers.IO) {
                    userDataBackupRepository.exportBackupJson()
                }

                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.bufferedWriter().use { writer ->
                            writer.write(backupJson)
                        }
                    } ?: throw IllegalStateException("无法写入备份文件")
                }

                Toast.makeText(this@BackupRestoreActivity, "备份导出成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@BackupRestoreActivity, "备份导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showImportConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("导入备份")
            .setMessage("导入会覆盖当前“我喜欢”和“自建歌单”数据，是否继续？")
            .setPositiveButton("继续导入") { _, _ ->
                openBackupDocument.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importUserDataBackup(uri: Uri) {
        lifecycleScope.launch {
            try {
                val backupJson = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().use { reader ->
                            reader.readText()
                        }
                    } ?: throw IllegalStateException("无法读取备份文件")
                }

                val result = withContext(Dispatchers.IO) {
                    userDataBackupRepository.importBackupJson(backupJson)
                }

                val message = "导入成功：我喜欢 ${result.favoriteCount} 首，歌单 ${result.playlistCount} 个，歌单歌曲 ${result.playlistSongCount} 首"
                Toast.makeText(this@BackupRestoreActivity, message, Toast.LENGTH_LONG).show()

                val appScope = (application as? MusicApplication)?.applicationScope
                    ?: CoroutineScope(Dispatchers.IO)
                appScope.launch {
                    try {
                        userDataBackupRepository.syncCoversAfterImport()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, "后台补图失败: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@BackupRestoreActivity, "备份导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.statusBarPlaceholder?.layoutParams?.height = insets.top
            binding.statusBarPlaceholder?.requestLayout()
            view.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }
}
