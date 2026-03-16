package com.tacke.music.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tacke.music.R
import com.tacke.music.databinding.ActivityLocalMusicBinding
import com.tacke.music.databinding.DialogScanOptionsBinding
import com.tacke.music.ui.adapter.LocalMusicAdapter
import com.tacke.music.data.repository.LocalMusicInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 带在线信息的本地音乐数据类
 */
data class LocalMusicWithInfo(
    val localMusic: LocalMusic,
    val coverUrl: String? = null,
    val lyrics: String? = null
)

data class LocalMusic(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val coverUri: String? = null
)

class LocalMusicActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocalMusicBinding
    private lateinit var adapter: LocalMusicAdapter
    private var localMusicList = mutableListOf<LocalMusic>()
    private var filteredMusicList = mutableListOf<LocalMusic>()
    private lateinit var localMusicInfoRepository: LocalMusicInfoRepository

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val REQUEST_MANAGE_STORAGE = 101
        private const val REQUEST_FOLDER_PICKER = 102

        private val MUSIC_EXTENSIONS = listOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "ape")

        fun start(activity: AppCompatActivity) {
            val intent = Intent(activity, LocalMusicActivity::class.java)
            activity.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalMusicBinding.inflate(layoutInflater)
        setContentView(binding.root)

        localMusicInfoRepository = LocalMusicInfoRepository(this)

        setupEdgeToEdge()
        setupRecyclerView()
        setupClickListeners()
        setupSearch()

        checkPermissionAndLoad()
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.statusBarPlaceholder.layoutParams.height = insets.top
            binding.statusBarPlaceholder.requestLayout()
            view.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    private fun setupRecyclerView() {
        adapter = LocalMusicAdapter(
            onItemClick = { music ->
                playMusic(music)
            },
            onItemLongClick = { music ->
                showMusicOptionsDialog(music)
                true
            }
        )
        binding.rvLocalMusic.layoutManager = LinearLayoutManager(this)
        binding.rvLocalMusic.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnScan.setOnClickListener {
            showScanOptionsDialog()
        }
    }

    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                filterMusic(s?.toString() ?: "")
            }
        })
    }

    private fun performSearch() {
        val keyword = binding.etSearch.text.toString().trim()
        filterMusic(keyword)
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun filterMusic(keyword: String) {
        if (keyword.isEmpty()) {
            filteredMusicList.clear()
            filteredMusicList.addAll(localMusicList)
        } else {
            filteredMusicList.clear()
            filteredMusicList.addAll(localMusicList.filter {
                it.title.contains(keyword, ignoreCase = true) ||
                it.artist.contains(keyword, ignoreCase = true)
            })
        }
        adapter.submitList(filteredMusicList.toList())
        updateSongCount()
    }

    private fun updateSongCount() {
        binding.tvSongCount.text = "${filteredMusicList.size} 首"
    }

    private fun showScanOptionsDialog() {
        val dialogBinding = DialogScanOptionsBinding.inflate(LayoutInflater.from(this))

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnFullScan.setOnClickListener {
            dialog.dismiss()
            performFullScan()
        }

        dialogBinding.btnFolderScan.setOnClickListener {
            dialog.dismiss()
            openFolderPicker()
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun performFullScan() {
        if (!checkStoragePermission()) {
            return
        }

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.GONE

            try {
                // 1. 扫描所有音乐文件
                val scannedFiles = withContext(Dispatchers.IO) {
                    scanAllMusicFiles()
                }

                if (scannedFiles.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.tvEmptyState.text = "未找到音乐文件"
                    binding.progressBar.visibility = View.GONE
                    return@launch
                }

                // 2. 过滤掉已存在的歌曲（根据文件路径去重）
                val existingPaths = localMusicList.map { it.path }.toSet()
                val newFiles = scannedFiles.filter { it.path !in existingPaths }

                if (newFiles.isEmpty()) {
                    Toast.makeText(
                        this@LocalMusicActivity,
                        "扫描完成，没有新歌曲需要添加",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.progressBar.visibility = View.GONE
                    return@launch
                }

                // 3. 处理并保存新歌曲到数据库（优先使用元数据，无元数据时解析文件名）
                val savedMusic = withContext(Dispatchers.IO) {
                    localMusicInfoRepository.scanAndSaveMusic(newFiles) { completed, total ->
                        runOnUiThread {
                            binding.tvEmptyState.text = "正在处理: $completed / $total"
                        }
                    }
                }

                // 4. 增量更新UI（不清空原有列表）
                localMusicList.addAll(savedMusic)
                filteredMusicList.clear()
                filteredMusicList.addAll(localMusicList)

                adapter.submitList(filteredMusicList.toList())
                updateSongCount()

                Toast.makeText(
                    this@LocalMusicActivity,
                    "扫描完成，新增 ${savedMusic.size} 首歌曲，共 ${localMusicList.size} 首",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Toast.makeText(this@LocalMusicActivity, "扫描失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun openFolderPicker() {
        if (!checkStoragePermission()) {
            return
        }

        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, REQUEST_FOLDER_PICKER)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件夹选择器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER_PICKER && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                scanFolder(uri)
            }
        }
    }

    private fun scanFolder(folderUri: Uri) {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.GONE

            try {
                // 1. 扫描文件夹中的音乐文件
                val scannedFiles = withContext(Dispatchers.IO) {
                    scanMusicInFolder(folderUri)
                }

                if (scannedFiles.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.tvEmptyState.text = "未找到音乐文件"
                    binding.progressBar.visibility = View.GONE
                    return@launch
                }

                // 2. 过滤掉已存在的歌曲（根据文件路径去重）
                val existingPaths = localMusicList.map { it.path }.toSet()
                val newFiles = scannedFiles.filter { it.path !in existingPaths }

                if (newFiles.isEmpty()) {
                    Toast.makeText(
                        this@LocalMusicActivity,
                        "扫描完成，没有新歌曲需要添加",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.progressBar.visibility = View.GONE
                    return@launch
                }

                // 3. 处理并保存新歌曲到数据库（优先使用元数据，无元数据时解析文件名）
                val savedMusic = withContext(Dispatchers.IO) {
                    localMusicInfoRepository.scanAndSaveMusic(newFiles) { completed, total ->
                        runOnUiThread {
                            binding.tvEmptyState.text = "正在处理: $completed / $total"
                        }
                    }
                }

                // 4. 增量更新UI（不清空原有列表）
                localMusicList.addAll(savedMusic)
                filteredMusicList.clear()
                filteredMusicList.addAll(localMusicList)

                adapter.submitList(filteredMusicList.toList())
                updateSongCount()

                Toast.makeText(
                    this@LocalMusicActivity,
                    "扫描完成，新增 ${savedMusic.size} 首歌曲，共 ${localMusicList.size} 首",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Toast.makeText(this@LocalMusicActivity, "扫描失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun scanAllMusicFiles(): List<LocalMusic> {
        val musicList = mutableListOf<LocalMusic>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.MIME_TYPE
        )

        // 扩展音乐文件类型支持
        val musicExtensions = listOf(
            "audio/mpeg", "audio/mp3",      // MP3
            "audio/flac",                    // FLAC
            "audio/wav", "audio/x-wav",     // WAV
            "audio/aac",                     // AAC
            "audio/mp4", "audio/m4a",       // M4A
            "audio/ogg", "audio/vorbis",    // OGG
            "audio/x-ms-wma",                // WMA
            "audio/ape",                     // APE
            "audio/x-ape"                    // APE alternative
        )

        // 构建MIME类型筛选条件
        val mimeTypeSelection = musicExtensions.joinToString(" OR ") {
            "${MediaStore.Audio.Media.MIME_TYPE} = ?"
        }

        // 组合筛选条件：IS_MUSIC标记 或 支持的MIME类型
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0) OR ($mimeTypeSelection)"
        val selectionArgs = musicExtensions.toTypedArray()

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            MediaStore.Audio.Media.DATE_ADDED + " DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "未知歌曲"
                val artist = cursor.getString(artistColumn) ?: "未知艺人"
                val album = cursor.getString(albumColumn) ?: "未知专辑"
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(dataColumn) ?: ""
                val albumId = cursor.getLong(albumIdColumn)

                // 过滤掉无效路径
                if (path.isBlank()) continue

                val coverUri = getAlbumCoverUri(albumId)

                musicList.add(LocalMusic(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    path = path,
                    coverUri = coverUri
                ))
            }
        }

        return musicList
    }

    private fun scanMusicInFolder(folderUri: Uri): List<LocalMusic> {
        val musicList = mutableListOf<LocalMusic>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.MIME_TYPE
        )

        // 获取文件夹路径
        val folderPath = getFolderPathFromUri(folderUri)
        if (folderPath.isBlank()) {
            // 如果无法获取路径，尝试使用文档树遍历方式
            return scanFolderUsingTree(folderUri)
        }

        // 扩展音乐文件类型支持
        val musicExtensions = listOf(
            "audio/mpeg", "audio/mp3",
            "audio/flac",
            "audio/wav", "audio/x-wav",
            "audio/aac",
            "audio/mp4", "audio/m4a",
            "audio/ogg", "audio/vorbis",
            "audio/x-ms-wma",
            "audio/ape",
            "audio/x-ape"
        )

        // 构建筛选条件：路径匹配 AND (IS_MUSIC标记 OR 支持的MIME类型)
        val mimeTypeSelection = musicExtensions.joinToString(" OR ") {
            "${MediaStore.Audio.Media.MIME_TYPE} = ?"
        }

        val selection = "${MediaStore.Audio.Media.DATA} LIKE ? AND ((${MediaStore.Audio.Media.IS_MUSIC} != 0) OR ($mimeTypeSelection))"
        val selectionArgs = arrayOf("$folderPath%") + musicExtensions.toTypedArray()

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            MediaStore.Audio.Media.DATE_ADDED + " DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "未知歌曲"
                val artist = cursor.getString(artistColumn) ?: "未知艺人"
                val album = cursor.getString(albumColumn) ?: "未知专辑"
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(dataColumn) ?: ""
                val albumId = cursor.getLong(albumIdColumn)

                // 过滤掉无效路径
                if (path.isBlank()) continue

                val coverUri = getAlbumCoverUri(albumId)

                musicList.add(LocalMusic(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    path = path,
                    coverUri = coverUri
                ))
            }
        }

        return musicList
    }

    /**
     * 使用文档树遍历方式扫描文件夹（备用方案）
     */
    private fun scanFolderUsingTree(folderUri: Uri): List<LocalMusic> {
        val musicList = mutableListOf<LocalMusic>()
        val musicExtensions = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "ape")

        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
            )
            scanDocumentTree(docUri, musicExtensions, musicList)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return musicList
    }

    private fun scanDocumentTree(
        uri: Uri,
        musicExtensions: Set<String>,
        musicList: MutableList<LocalMusic>
    ) {
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                uri,
                DocumentsContract.getDocumentId(uri)
            )

            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn)
                    val mimeType = cursor.getString(mimeColumn)

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // 递归扫描子文件夹
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                        scanDocumentTree(childUri, musicExtensions, musicList)
                    } else if (musicExtensions.any { name.lowercase().endsWith(".$it") }) {
                        // 是音乐文件，尝试获取信息
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                        getMusicInfoFromUri(fileUri, name)?.let { musicList.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getMusicInfoFromUri(uri: Uri, fileName: String): LocalMusic? {
        return try {
            // 尝试从MediaStore查询文件信息
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID
            )

            // 通过文件路径查询
            val path = uri.toString()
            val selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("%$fileName")

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn) ?: fileName.substringBeforeLast(".")
                    val artist = cursor.getString(artistColumn) ?: "未知艺人"
                    val album = cursor.getString(albumColumn) ?: "未知专辑"
                    val duration = cursor.getLong(durationColumn)
                    val filePath = cursor.getString(dataColumn) ?: path
                    val albumId = cursor.getLong(albumIdColumn)

                    val coverUri = getAlbumCoverUri(albumId)

                    return LocalMusic(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        path = filePath,
                        coverUri = coverUri
                    )
                }
            }

            // 如果MediaStore中没有，创建一个基本信息对象
            LocalMusic(
                id = uri.hashCode().toLong(),
                title = fileName.substringBeforeLast("."),
                artist = "未知艺人",
                album = "未知专辑",
                duration = 0,
                path = uri.toString(),
                coverUri = null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getFolderPathFromUri(uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            if (split.size < 2) {
                return "" // 无法解析路径格式
            }
            val type = split[0]
            val path = split[1]

            when {
                "primary".equals(type, ignoreCase = true) -> {
                    Environment.getExternalStorageDirectory().toString() + "/" + path
                }
                type.startsWith("raw:") -> {
                    // 直接路径格式
                    type.removePrefix("raw:")
                }
                else -> {
                    // 外部SD卡或其他存储
                    "/storage/$type/$path"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "" // 返回空字符串表示无法获取路径
        }
    }

    private fun getAlbumCoverUri(albumId: Long): String? {
        return try {
            val uri = Uri.parse("content://media/external/audio/albumart")
            Uri.withAppendedPath(uri, albumId.toString()).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun checkPermissionAndLoad() {
        if (checkStoragePermission()) {
            loadCachedMusic()
        }
    }

    /**
     * 从数据库加载缓存的本地音乐列表
     */
    private fun loadCachedMusic() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.GONE

            try {
                val cachedMusic = withContext(Dispatchers.IO) {
                    localMusicInfoRepository.getAllCachedMusic()
                }

                localMusicList.clear()
                localMusicList.addAll(cachedMusic)
                filteredMusicList.clear()
                filteredMusicList.addAll(localMusicList)

                adapter.submitList(filteredMusicList.toList())
                updateSongCount()

                if (localMusicList.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.tvEmptyState.text = "暂无本地音乐，点击右上角扫描按钮添加"
                }
            } catch (e: Exception) {
                Toast.makeText(this@LocalMusicActivity, "加载本地音乐失败: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.tvEmptyState.text = "加载失败，点击右上角扫描按钮重试"
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun checkStoragePermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val hasAudioPermission = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasAudioPermission) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                        PERMISSION_REQUEST_CODE
                    )
                }
                hasAudioPermission
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val hasManageStorage = Environment.isExternalStorageManager()
                if (!hasManageStorage) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                }
                hasManageStorage
            }
            else -> {
                val hasReadPermission = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasReadPermission) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        PERMISSION_REQUEST_CODE
                    )
                }
                hasReadPermission
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadCachedMusic()
            } else {
                Toast.makeText(this, "需要存储权限才能扫描音乐文件", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playMusic(music: LocalMusic) {
        lifecycleScope.launch {
            // 显示加载提示
            binding.progressBar.visibility = View.VISIBLE

            try {
                // 获取歌曲信息（带缓存）
                val musicInfo = withContext(Dispatchers.IO) {
                    localMusicInfoRepository.getLocalMusicInfo(music)
                }

                if (musicInfo != null && musicInfo.coverUrl != null) {
                    Toast.makeText(
                        this@LocalMusicActivity,
                        "播放: ${music.title}\n已加载封面和歌词",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@LocalMusicActivity,
                        "播放: ${music.title}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // TODO: 这里可以启动播放器播放本地音乐
                // 传入 music.path 作为播放路径
                // 传入 musicInfo?.coverUrl 作为封面
                // 传入 musicInfo?.lyrics 作为歌词

            } catch (e: Exception) {
                Toast.makeText(
                    this@LocalMusicActivity,
                    "播放: ${music.title}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun showMusicOptionsDialog(music: LocalMusic) {
        val options = arrayOf("添加到播放列表", "查看详情")

        MaterialAlertDialogBuilder(this)
            .setTitle(music.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addToPlaylist(music)
                    1 -> showMusicDetails(music)
                }
            }
            .show()
    }

    private fun addToPlaylist(music: LocalMusic) {
        Toast.makeText(this, "已添加到播放列表", Toast.LENGTH_SHORT).show()
    }

    private fun showMusicDetails(music: LocalMusic) {
        val message = """
            歌曲: ${music.title}
            艺人: ${music.artist}
            专辑: ${music.album}
            时长: ${formatDuration(music.duration)}
            路径: ${music.path}
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("歌曲详情")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun formatDuration(durationMs: Long): String {
        val minutes = durationMs / 1000 / 60
        val seconds = durationMs / 1000 % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
