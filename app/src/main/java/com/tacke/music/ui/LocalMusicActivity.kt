package com.tacke.music.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
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
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.FavoriteRepository
import com.tacke.music.data.repository.LocalMusicInfoRepository
import com.tacke.music.data.repository.PlaylistRepository
import com.tacke.music.databinding.ActivityLocalMusicBinding
import com.tacke.music.databinding.DialogScanOptionsBinding
import com.tacke.music.playback.PlaybackManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.adapter.LocalMusicAdapter
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
    val coverUri: String? = null,
    val contentUri: String? = null // MediaStore 的 content:// URI，用于 Android 10+ 访问文件
)

class LocalMusicActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocalMusicBinding
    private lateinit var adapter: LocalMusicAdapter
    private var localMusicList = mutableListOf<LocalMusic>()
    private var filteredMusicList = mutableListOf<LocalMusic>()
    private lateinit var localMusicInfoRepository: LocalMusicInfoRepository
    private lateinit var playbackManager: PlaybackManager
    private lateinit var playlistManager: PlaylistManager
    private lateinit var favoriteRepository: FavoriteRepository
    private lateinit var playlistRepository: PlaylistRepository
    private var isMultiSelectMode = false

    companion object {
        private const val TAG = "LocalMusicActivity"
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
        playbackManager = PlaybackManager.getInstance(this)
        playlistManager = PlaylistManager.getInstance(this)
        favoriteRepository = FavoriteRepository(this)
        playlistRepository = PlaylistRepository(this)

        setupEdgeToEdge()
        setupRecyclerView()
        setupClickListeners()
        setupSearch()
        setupBatchActions()

        checkPermissionAndLoad()
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

    private fun setupRecyclerView() {
        adapter = LocalMusicAdapter(
            onItemClick = { music ->
                if (isMultiSelectMode) {
                    adapter.toggleSelection(music.id)
                    updateSelectedCount(adapter.getSelectedItems().size)
                } else {
                    playMusic(music)
                }
            },
            onItemLongClick = { music ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode()
                    adapter.toggleSelection(music.id)
                    updateSelectedCount(1)
                    true
                } else {
                    false
                }
            },
            onMoreClick = { music ->
                if (!isMultiSelectMode) {
                    showMusicOptionsDialog(music)
                }
            }
        )
        binding.rvLocalMusic.layoutManager = LinearLayoutManager(this)
        binding.rvLocalMusic.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            if (isMultiSelectMode) {
                exitMultiSelectMode()
            } else {
                finish()
            }
        }

        binding.btnScan.setOnClickListener {
            if (!isMultiSelectMode) {
                showScanOptionsDialog()
            }
        }
    }

    private fun setupBatchActions() {
        // 关闭按钮
        findViewById<View>(R.id.btnCloseBatch)?.setOnClickListener {
            exitMultiSelectMode()
        }

        // 全选按钮
        findViewById<View>(R.id.btnSelectAll)?.setOnClickListener {
            adapter.selectAll()
            updateSelectedCount(adapter.getSelectedItems().size)
        }

        // 添加到喜欢按钮
        findViewById<View>(R.id.btnAddToFavorite)?.setOnClickListener {
            addSelectedToFavorites()
        }

        // 添加到播放按钮
        findViewById<View>(R.id.btnAddToNowPlaying)?.setOnClickListener {
            addSelectedToNowPlaying()
        }

        // 添加到歌单按钮
        findViewById<View>(R.id.btnAddToPlaylist)?.setOnClickListener {
            showBatchPlaylistSelectionDialog()
        }

        // 清空列表按钮
        findViewById<View>(R.id.btnClearAll)?.setOnClickListener {
            showClearAllConfirmDialog()
        }

        // 移除所选按钮 - 询问是否同时删除文件
        findViewById<View>(R.id.btnBatchRemove)?.setOnClickListener {
            showBatchRemoveDialog()
        }
    }

    private fun enterMultiSelectMode() {
        isMultiSelectMode = true
        findViewById<View>(R.id.batchActionBar)?.visibility = View.VISIBLE
        adapter.setMultiSelectMode(true)
        updateSelectedCount(0)

        // 本地音乐页面：隐藏下载按钮，显示移除按钮
        findViewById<View>(R.id.btnBatchDownload)?.visibility = View.GONE
        findViewById<View>(R.id.btnBatchRemove)?.visibility = View.VISIBLE
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        findViewById<View>(R.id.batchActionBar)?.visibility = View.GONE
        adapter.setMultiSelectMode(false)

        // 恢复按钮默认状态
        findViewById<View>(R.id.btnBatchDownload)?.visibility = View.VISIBLE
        findViewById<View>(R.id.btnBatchRemove)?.visibility = View.GONE
    }

    private fun updateSelectedCount(count: Int) {
        findViewById<TextView>(R.id.tvSelectedCount)?.text = count.toString()
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
        Log.d(TAG, "开始全盘扫描")
        if (!checkStoragePermission()) {
            Log.w(TAG, "没有存储权限，无法扫描")
            return
        }

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.GONE

            try {
                // 1. 扫描所有音乐文件
                Log.d(TAG, "在后台线程执行扫描...")
                val scannedFiles = withContext(Dispatchers.IO) {
                    scanAllMusicFiles()
                }
                Log.d(TAG, "扫描完成，共找到 ${scannedFiles.size} 首音乐文件")

                if (scannedFiles.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.tvEmptyState.text = "未找到音乐文件"
                    binding.progressBar.visibility = View.GONE
                    Log.w(TAG, "未找到任何音乐文件")
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
        when (requestCode) {
            REQUEST_FOLDER_PICKER -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        // 保留持久化权限，这对于访问 Download 目录至关重要
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            Log.d(TAG, "已获得持久化 URI 权限: $uri")
                        } catch (e: Exception) {
                            Log.w(TAG, "获取持久化 URI 权限失败: ${e.message}")
                        }
                        scanFolder(uri)
                    }
                }
            }
            REQUEST_MANAGE_STORAGE -> {
                // 检查是否已获得所有文件访问权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        Toast.makeText(this, "已获得所有文件访问权限", Toast.LENGTH_SHORT).show()
                        loadCachedMusic()
                    } else {
                        Toast.makeText(this, "需要所有文件访问权限才能扫描 Download 目录", Toast.LENGTH_LONG).show()
                    }
                }
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
        val processedPaths = mutableSetOf<String>() // 用于去重

        // 1. 首先尝试从 MediaStore 查询
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        // 扩展音乐文件类型支持
        val musicMimeTypes = listOf(
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

        // 构建MIME类型筛选条件 - 使用OR连接所有支持的MIME类型
        val mimeTypeSelection = musicMimeTypes.joinToString(" OR ") {
            "${MediaStore.Audio.Media.MIME_TYPE} = ?"
        }

        // 筛选条件：支持的MIME类型（MediaStore的IS_MUSIC标记在某些设备上不可靠）
        val selection = mimeTypeSelection
        val selectionArgs = musicMimeTypes.toTypedArray()

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
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "未知歌曲"
                val artist = cursor.getString(artistColumn) ?: "未知艺人"
                val album = cursor.getString(albumColumn) ?: "未知专辑"
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(dataColumn) ?: ""
                val albumId = cursor.getLong(albumIdColumn)
                val displayName = cursor.getString(displayNameColumn) ?: ""

                // 过滤掉无效路径
                if (path.isBlank()) continue

                // 额外检查文件扩展名，确保只包含支持的音乐格式
                val extension = path.substringAfterLast('.', "").lowercase()
                if (extension !in MUSIC_EXTENSIONS) continue

                // 去重检查
                if (path in processedPaths) continue
                processedPaths.add(path)

                // 检查文件是否存在且可读 - 这是关键检查，防止添加已删除的文件
                val file = File(path)
                if (!file.exists()) {
                    Log.w(TAG, "跳过已删除的文件: $path")
                    continue
                }
                if (!file.canRead()) {
                    Log.w(TAG, "跳过无法读取的文件: $path")
                    continue
                }

                // 生成 MediaStore 的 content:// URI
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                ).toString()

                val coverUri = getAlbumCoverUri(albumId)

                musicList.add(LocalMusic(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    path = path,
                    coverUri = coverUri,
                    contentUri = contentUri
                ))
            }
        }

        // 2. 如果拥有 MANAGE_EXTERNAL_STORAGE 权限，直接扫描 Download 目录
        // 因为 MediaStore 可能没有及时索引 Download 目录中的文件
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Log.d(TAG, "拥有 MANAGE_EXTERNAL_STORAGE 权限，直接扫描 Download 目录")
            scanDownloadDirectory(musicList, processedPaths)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Android 10 以下直接扫描
            Log.d(TAG, "Android 10 以下，直接扫描 Download 目录")
            scanDownloadDirectory(musicList, processedPaths)
        }

        return musicList
    }

    /**
     * 直接扫描 Download 目录中的音乐文件
     * 用于补充 MediaStore 查询不到的音乐文件
     */
    private fun scanDownloadDirectory(musicList: MutableList<LocalMusic>, processedPaths: MutableSet<String>) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            Log.d(TAG, "开始扫描 Download 目录: ${downloadDir.absolutePath}")
            Log.d(TAG, "Download 目录是否存在: ${downloadDir.exists()}")
            Log.d(TAG, "Download 目录是否可读: ${downloadDir.canRead()}")
            Log.d(TAG, "Download 目录是否是目录: ${downloadDir.isDirectory}")

            if (!downloadDir.exists() || !downloadDir.isDirectory) {
                Log.w(TAG, "Download 目录不存在或不是目录")
                return
            }

            // 列出 Download 目录下的所有文件和子目录
            val files = downloadDir.listFiles()
            Log.d(TAG, "Download 目录下文件数量: ${files?.size ?: 0}")
            files?.forEach { file ->
                Log.d(TAG, "Download 目录内容: ${file.name} (isDirectory=${file.isDirectory})")
            }

            scanDirectoryRecursive(downloadDir, musicList, processedPaths)
            Log.d(TAG, "Download 目录扫描完成，共扫描到 ${musicList.size} 首音乐")
        } catch (e: Exception) {
            Log.e(TAG, "扫描 Download 目录失败", e)
        }
    }

    /**
     * 递归扫描目录中的音乐文件
     */
    private fun scanDirectoryRecursive(dir: File, musicList: MutableList<LocalMusic>, processedPaths: MutableSet<String>) {
        Log.d(TAG, "正在扫描目录: ${dir.absolutePath}")
        val files = dir.listFiles()
        if (files == null) {
            Log.w(TAG, "无法列出目录内容: ${dir.absolutePath}")
            return
        }
        Log.d(TAG, "目录 ${dir.name} 下文件数量: ${files.size}")

        for (file in files) {
            if (file.isDirectory) {
                // 递归扫描子目录
                scanDirectoryRecursive(file, musicList, processedPaths)
            } else {
                // 检查是否是音乐文件
                val extension = file.name.substringAfterLast('.', "").lowercase()
                Log.d(TAG, "检查文件: ${file.name}, 扩展名: $extension")
                if (extension !in MUSIC_EXTENSIONS) {
                    Log.d(TAG, "跳过非音乐文件: ${file.name}")
                    continue
                }

                // 去重检查
                val path = file.absolutePath
                if (path in processedPaths) {
                    Log.d(TAG, "跳过已处理文件: $path")
                    continue
                }
                processedPaths.add(path)

                Log.d(TAG, "从文件系统扫描到音乐文件: $path")

                // 尝试从 MediaStore 查询该文件的信息
                val musicInfo = queryMusicInfoFromMediaStore(path)
                if (musicInfo != null) {
                    musicList.add(musicInfo)
                    Log.d(TAG, "已添加音乐文件(来自MediaStore): ${file.name}")
                } else {
                    // MediaStore 中没有，创建基本信息
                    val id = path.hashCode().toLong()
                    val title = file.nameWithoutExtension
                    musicList.add(LocalMusic(
                        id = id,
                        title = title,
                        artist = "未知艺人",
                        album = "未知专辑",
                        duration = 0,
                        path = path,
                        coverUri = null,
                        contentUri = null
                    ))
                    Log.d(TAG, "已添加音乐文件(来自文件系统): ${file.name}")
                }
            }
        }
    }

    /**
     * 从 MediaStore 查询指定路径的音乐信息
     */
    private fun queryMusicInfoFromMediaStore(path: String): LocalMusic? {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.DATA} = ?"
        val selectionArgs = arrayOf(path)

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
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: File(path).nameWithoutExtension
                val artist = cursor.getString(artistColumn) ?: "未知艺人"
                val album = cursor.getString(albumColumn) ?: "未知专辑"
                val duration = cursor.getLong(durationColumn)
                val albumId = cursor.getLong(albumIdColumn)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                ).toString()

                val coverUri = getAlbumCoverUri(albumId)

                return LocalMusic(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    path = path,
                    coverUri = coverUri,
                    contentUri = contentUri
                )
            }
        }

        return null
    }

    private fun scanMusicInFolder(folderUri: Uri): List<LocalMusic> {
        Log.d(TAG, "开始扫描文件夹: $folderUri")

        // 对于 Android 10+，优先使用 SAF 文档树遍历方式
        // 因为直接文件路径访问在 Android 10+ 上受限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "Android 10+，使用 SAF 文档树遍历方式")
            return scanFolderUsingTree(folderUri)
        }

        // Android 9 及以下使用 MediaStore 查询
        val musicList = mutableListOf<LocalMusic>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        // 获取文件夹路径
        val folderPath = getFolderPathFromUri(folderUri)
        if (folderPath.isBlank()) {
            // 如果无法获取路径，尝试使用文档树遍历方式
            return scanFolderUsingTree(folderUri)
        }

        // 扩展音乐文件类型支持
        val musicMimeTypes = listOf(
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

        // 构建筛选条件：路径匹配 AND 支持的MIME类型
        val mimeTypeSelection = musicMimeTypes.joinToString(" OR ") {
            "${MediaStore.Audio.Media.MIME_TYPE} = ?"
        }

        val selection = "${MediaStore.Audio.Media.DATA} LIKE ? AND ($mimeTypeSelection)"
        val selectionArgs = arrayOf("$folderPath%") + musicMimeTypes.toTypedArray()

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
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "未知歌曲"
                val artist = cursor.getString(artistColumn) ?: "未知艺人"
                val album = cursor.getString(albumColumn) ?: "未知专辑"
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(dataColumn) ?: ""
                val albumId = cursor.getLong(albumIdColumn)
                val displayName = cursor.getString(displayNameColumn) ?: ""

                // 过滤掉无效路径
                if (path.isBlank()) continue

                // 额外检查文件扩展名，确保只包含支持的音乐格式
                val extension = path.substringAfterLast('.', "").lowercase()
                if (extension !in MUSIC_EXTENSIONS) continue

                // 检查文件是否存在且可读（在Android 10+上，应用可能无法访问其他应用的下载目录）
                val file = File(path)
                if (!file.exists() || !file.canRead()) {
                    Log.w(TAG, "跳过无法访问的文件: $path")
                    continue
                }

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
     * 使用文档树遍历方式扫描文件夹（Android 10+ 的主要方案）
     */
    private fun scanFolderUsingTree(folderUri: Uri): List<LocalMusic> {
        val musicList = mutableListOf<LocalMusic>()
        val processedPaths = mutableSetOf<String>() // 用于去重

        Log.d(TAG, "开始文档树遍历扫描: $folderUri")

        try {
            val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
            Log.d(TAG, "Tree Document ID: $treeDocId")

            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                treeDocId
            )
            Log.d(TAG, "Document URI: $docUri")

            scanDocumentTree(docUri, MUSIC_EXTENSIONS.toSet(), musicList, processedPaths, "")
            Log.d(TAG, "文档树遍历完成，共找到 ${musicList.size} 首音乐")
        } catch (e: Exception) {
            Log.e(TAG, "文档树遍历失败", e)
            e.printStackTrace()
        }

        return musicList
    }

    private fun scanDocumentTree(
        uri: Uri,
        musicExtensions: Set<String>,
        musicList: MutableList<LocalMusic>,
        processedPaths: MutableSet<String>,
        parentPath: String
    ) {
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                uri,
                DocumentsContract.getDocumentId(uri)
            )

            Log.d(TAG, "扫描目录: $parentPath, URI: $childrenUri")

            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                Log.d(TAG, "目录 $parentPath 包含 ${cursor.count} 个项目")

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn)
                    val mimeType = cursor.getString(mimeColumn)

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // 递归扫描子文件夹
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                        val currentPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                        scanDocumentTree(childUri, musicExtensions, musicList, processedPaths, currentPath)
                    } else if (musicExtensions.any { name.lowercase().endsWith(".$it") }) {
                        // 是音乐文件，尝试获取信息
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                        val currentPath = if (parentPath.isEmpty()) name else "$parentPath/$name"

                        // 去重检查
                        if (currentPath in processedPaths) {
                            Log.d(TAG, "跳过已处理文件: $currentPath")
                            continue
                        }
                        processedPaths.add(currentPath)

                        Log.d(TAG, "发现音乐文件: $name")
                        getMusicInfoFromUri(fileUri, name, currentPath)?.let {
                            musicList.add(it)
                            Log.d(TAG, "已添加音乐文件: $name")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描目录失败: $parentPath", e)
            e.printStackTrace()
        }
    }

    private fun getMusicInfoFromUri(uri: Uri, fileName: String, relativePath: String): LocalMusic? {
        return try {
            Log.d(TAG, "获取音乐信息: $fileName, URI: $uri")

            // 首先尝试通过文件路径从MediaStore查询
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID
            )

            // 尝试通过精确的文件名匹配查询（MediaStore中存储的是完整路径）
            val displayNameSelection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
            val displayNameArgs = arrayOf(fileName)

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                displayNameSelection,
                displayNameArgs,
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
                    val filePath = cursor.getString(dataColumn) ?: ""
                    val albumId = cursor.getLong(albumIdColumn)

                    // 生成 MediaStore 的 content:// URI
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    ).toString()

                    val coverUri = getAlbumCoverUri(albumId)

                    Log.d(TAG, "从 MediaStore 获取到音乐信息: $title")

                    return LocalMusic(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        path = filePath.ifEmpty { uri.toString() },
                        coverUri = coverUri,
                        contentUri = contentUri
                    )
                }
            }

            // MediaStore 中没有，使用 SAF URI 创建基本信息
            Log.d(TAG, "MediaStore 中未找到，使用 SAF URI: $fileName")

            // 对于 Android 10+，直接使用 SAF URI 作为路径
            // 因为文件路径可能无法访问
            val pathToUse = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                uri.toString()
            } else {
                getRealPathFromUri(uri) ?: uri.toString()
            }

            // 创建一个基本信息对象
            LocalMusic(
                id = uri.hashCode().toLong(),
                title = fileName.substringBeforeLast("."),
                artist = "未知艺人",
                album = "未知专辑",
                duration = 0,
                path = pathToUse,
                coverUri = null,
                contentUri = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取音乐信息失败: $fileName", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * 尝试从URI获取真实文件路径
     */
    private fun getRealPathFromUri(uri: Uri): String? {
        return try {
            // 尝试通过内容解析器获取文件路径
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    return cursor.getString(dataColumn)
                }
            }
            null
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
        Log.d(TAG, "检查存储权限，Android 版本: ${Build.VERSION.SDK_INT}")
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ 需要 READ_MEDIA_AUDIO 和 MANAGE_EXTERNAL_STORAGE
                val hasAudioPermission = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                val hasManageStorage = Environment.isExternalStorageManager()

                Log.d(TAG, "Android 13+: hasAudioPermission=$hasAudioPermission, hasManageStorage=$hasManageStorage")

                if (!hasAudioPermission) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                        PERMISSION_REQUEST_CODE
                    )
                } else if (!hasManageStorage) {
                    // 申请所有文件访问权限（用于访问 Download 目录）
                    requestManageExternalStorage()
                }

                hasAudioPermission && hasManageStorage
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE 才能访问 Download 目录
                val hasManageStorage = Environment.isExternalStorageManager()
                Log.d(TAG, "Android 11+: hasManageStorage=$hasManageStorage")
                if (!hasManageStorage) {
                    requestManageExternalStorage()
                }
                hasManageStorage
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10 需要 READ_EXTERNAL_STORAGE 和 MANAGE_EXTERNAL_STORAGE
                val hasReadPermission = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                val hasManageStorage = Environment.isExternalStorageManager()

                Log.d(TAG, "Android 10: hasReadPermission=$hasReadPermission, hasManageStorage=$hasManageStorage")

                if (!hasReadPermission) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        PERMISSION_REQUEST_CODE
                    )
                } else if (!hasManageStorage) {
                    requestManageExternalStorage()
                }

                hasReadPermission && hasManageStorage
            }
            else -> {
                // Android 9 及以下
                val hasReadPermission = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                Log.d(TAG, "Android 9-: hasReadPermission=$hasReadPermission")

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

    /**
     * 申请所有文件访问权限（MANAGE_EXTERNAL_STORAGE）
     * 这是访问 Download 目录等受限目录所必需的
     */
    private fun requestManageExternalStorage() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
        } catch (e: Exception) {
            // 如果系统不支持 ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION，使用备选方案
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            } catch (e2: Exception) {
                // 如果还是失败，打开应用设置页面
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "请手动授予所有文件访问权限", Toast.LENGTH_LONG).show()
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

    /**
     * 播放本地音乐
     */
    private fun playMusic(music: LocalMusic) {
        lifecycleScope.launch {
            try {
                // 仅从本地缓存读取封面和歌词（不触发网络请求）
                val cachedInfo = withContext(Dispatchers.IO) {
                    localMusicInfoRepository.getCachedInfoByPath(music.path)
                }

                // 先直接本地播放；若封面/歌词缺失，PlaybackManager 会在后台补全
                val success = playbackManager.playFromLocalMusic(
                    context = this@LocalMusicActivity,
                    localMusic = music,
                    coverUrl = cachedInfo?.coverUrl,
                    lyrics = cachedInfo?.lyrics
                )

                if (success) {
                    Toast.makeText(
                        this@LocalMusicActivity,
                        "正在播放: ${music.title}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@LocalMusicActivity,
                        "播放失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                Toast.makeText(
                    this@LocalMusicActivity,
                    "播放失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 获取本地音乐最佳封面：
     * 1) 本地缓存封面
     * 2) music 自带封面（MediaStore/旧值）
     */
    private suspend fun getBestLocalCover(music: LocalMusic): String {
        val cached = withContext(Dispatchers.IO) {
            localMusicInfoRepository.getCachedInfoByPath(music.path)
        }
        return cached?.coverUrl ?: music.coverUri ?: ""
    }

    private fun showMusicOptionsDialog(music: LocalMusic) {
        val options = arrayOf("播放", "添加到播放列表", "添加到喜欢", "查看详情")

        MaterialAlertDialogBuilder(this)
            .setTitle(music.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playMusic(music)
                    1 -> addToPlaylist(music)
                    2 -> addToFavorites(music)
                    3 -> showMusicDetails(music)
                }
            }
            .show()
    }

    private fun addToPlaylist(music: LocalMusic) {
        lifecycleScope.launch {
            try {
                val bestCover = getBestLocalCover(music)
                val playlistSong = PlaylistSong(
                    id = "local_${music.path.hashCode()}",
                    name = music.title,
                    artists = music.artist,
                    coverUrl = bestCover,
                    platform = "LOCAL"
                )
                playlistManager.addSong(playlistSong)
                Toast.makeText(this@LocalMusicActivity, "已添加到播放列表", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@LocalMusicActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addToFavorites(music: LocalMusic) {
        lifecycleScope.launch {
            try {
                val bestCover = getBestLocalCover(music)
                val song = Song(
                    index = 0,
                    id = "local_${music.path.hashCode()}",
                    name = music.title,
                    artists = music.artist,
                    coverUrl = bestCover
                )
                favoriteRepository.addToFavorites(song, "LOCAL")
                Toast.makeText(this@LocalMusicActivity, "已添加到喜欢", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@LocalMusicActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
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

    // ==================== 批量操作 ====================

    private fun showBatchDeleteDialog() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的项目", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("仅删除记录", "删除记录及文件")
        var selectedOption = 0

        MaterialAlertDialogBuilder(this)
            .setTitle("删除本地音乐 (${selectedItems.size} 项)")
            .setSingleChoiceItems(options, selectedOption) { _, which ->
                selectedOption = which
            }
            .setPositiveButton("确定") { _, _ ->
                val deleteFile = selectedOption == 1
                deleteSelectedItems(selectedItems, deleteFile)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedItems(items: List<LocalMusic>, deleteFile: Boolean) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    items.forEach { music ->
                        // 从数据库删除
                        localMusicInfoRepository.deleteByPath(music.path)
                        // 如果需要，删除文件
                        if (deleteFile) {
                            try {
                                if (music.path.startsWith("content://")) {
                                    // SAF URI 文件使用 ContentResolver 删除
                                    deleteSafFile(music.path)
                                } else {
                                    // 普通文件路径
                                    File(music.path).delete()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "删除文件失败: ${music.path}", e)
                            }
                        }
                    }
                }

                // 从列表中移除
                localMusicList.removeAll(items)
                filteredMusicList.removeAll(items)
                adapter.submitList(filteredMusicList.toList())
                updateSongCount()

                val message = if (deleteFile) {
                    "已删除 ${items.size} 个记录及文件"
                } else {
                    "已删除 ${items.size} 个记录"
                }
                Toast.makeText(this@LocalMusicActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@LocalMusicActivity, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSelectedToFavorites() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, "请先选择要添加的项目", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                var addedCount = 0
                var alreadyFavoriteCount = 0

                selectedItems.forEach { music ->
                    val songId = "local_${music.path.hashCode()}"
                    val isFavorite = favoriteRepository.isFavorite(songId)
                    if (!isFavorite) {
                        val bestCover = getBestLocalCover(music)
                        val song = Song(
                            index = 0,
                            id = songId,
                            name = music.title,
                            artists = music.artist,
                            coverUrl = bestCover
                        )
                        favoriteRepository.addToFavorites(song, "LOCAL")
                        addedCount++
                    } else {
                        alreadyFavoriteCount++
                    }
                }

                val message = when {
                    alreadyFavoriteCount > 0 -> "已添加 $addedCount 首，$alreadyFavoriteCount 首已在喜欢列表"
                    else -> "已添加 $addedCount 首歌曲到我喜欢"
                }
                Toast.makeText(this@LocalMusicActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@LocalMusicActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addSelectedToNowPlaying() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, "请先选择要添加的项目", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                var addedCount = 0
                var duplicateCount = 0

                selectedItems.forEach { music ->
                    val bestCover = getBestLocalCover(music)
                    val playlistSong = PlaylistSong(
                        id = "local_${music.path.hashCode()}",
                        name = music.title,
                        artists = music.artist,
                        coverUrl = bestCover,
                        platform = "LOCAL"
                    )

                    val currentList = playlistManager.currentPlaylist.value
                    if (currentList.none { it.id == playlistSong.id }) {
                        playlistManager.addSong(playlistSong)
                        addedCount++
                    } else {
                        duplicateCount++
                    }
                }

                val message = when {
                    duplicateCount > 0 -> "已添加 $addedCount 首，$duplicateCount 首已存在"
                    else -> "已添加 $addedCount 首歌曲到正在播放列表"
                }
                Toast.makeText(this@LocalMusicActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@LocalMusicActivity, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showBatchPlaylistSelectionDialog() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, "请先选择要添加的项目", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val playlists = playlistRepository.getAllPlaylistsSync()

            if (playlists.isEmpty()) {
                MaterialAlertDialogBuilder(this@LocalMusicActivity)
                    .setTitle("添加到歌单")
                    .setMessage("暂无歌单，是否创建新歌单？")
                    .setPositiveButton("创建") { _, _ ->
                        showCreatePlaylistDialog(selectedItems)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val playlistNames = playlists.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this@LocalMusicActivity)
                .setTitle("添加到歌单")
                .setItems(playlistNames) { _, which ->
                    addSelectedToPlaylist(playlists[which].id, selectedItems)
                }
                .setPositiveButton("新建歌单") { _, _ ->
                    showCreatePlaylistDialog(selectedItems)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showCreatePlaylistDialog(items: List<LocalMusic>) {
        val editText = EditText(this).apply {
            hint = "歌单名称"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("新建歌单")
            .setView(editText)
            .setPositiveButton("创建") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val playlist = playlistRepository.createPlaylist(name)
                        addSelectedToPlaylist(playlist.id, items)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addSelectedToPlaylist(playlistId: String, items: List<LocalMusic>) {
        lifecycleScope.launch {
            try {
                val songList = mutableListOf<Song>()
                items.forEach { music ->
                    val bestCover = getBestLocalCover(music)
                    songList.add(
                        Song(
                            index = 0,
                            id = "local_${music.path.hashCode()}",
                            name = music.title,
                            artists = music.artist,
                            coverUrl = bestCover
                        )
                    )
                }

                playlistRepository.addSongsToPlaylist(playlistId, songList, "LOCAL")

                Toast.makeText(
                    this@LocalMusicActivity,
                    "已添加 ${items.size} 首歌曲到歌单",
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(
                    this@LocalMusicActivity,
                    "添加失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showClearAllConfirmDialog() {
        val options = arrayOf("仅清空记录", "清空记录并删除文件")
        var selectedOption = 0

        MaterialAlertDialogBuilder(this)
            .setTitle("清空本地音乐")
            .setSingleChoiceItems(options, selectedOption) { _, which ->
                selectedOption = which
            }
            .setPositiveButton("清空") { _, _ ->
                val deleteFile = selectedOption == 1
                clearAllMusic(deleteFile)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAllMusic(deleteFile: Boolean = false) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (deleteFile) {
                        // 删除所有文件
                        localMusicList.forEach { music ->
                            try {
                                if (music.path.startsWith("content://")) {
                                    // SAF URI 文件使用 ContentResolver 删除
                                    deleteSafFile(music.path)
                                } else {
                                    // 普通文件路径
                                    File(music.path).delete()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "删除文件失败: ${music.path}", e)
                            }
                        }
                    }
                    // 删除所有记录
                    localMusicInfoRepository.deleteAll()
                }
                val count = localMusicList.size
                localMusicList.clear()
                filteredMusicList.clear()
                adapter.submitList(emptyList())
                updateSongCount()
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.tvEmptyState.text = "暂无本地音乐，点击右上角扫描按钮添加"

                val message = if (deleteFile) {
                    "已清空 $count 个记录并删除文件"
                } else {
                    "已清空所有本地音乐记录"
                }
                Toast.makeText(this@LocalMusicActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Toast.makeText(this@LocalMusicActivity, "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showBatchRemoveDialog() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            Toast.makeText(this, "请先选择要移除的项目", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("仅移除记录", "移除记录并删除文件")
        var selectedOption = 0

        MaterialAlertDialogBuilder(this)
            .setTitle("移除本地音乐 (${selectedItems.size} 项)")
            .setSingleChoiceItems(options, selectedOption) { _, which ->
                selectedOption = which
            }
            .setPositiveButton("移除") { _, _ ->
                val deleteFile = selectedOption == 1
                removeSelectedItems(selectedItems, deleteFile)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun removeSelectedItems(items: List<LocalMusic>, deleteFile: Boolean = false) {
        lifecycleScope.launch {
            try {
                var deletedFileCount = 0
                var failedFileCount = 0

                withContext(Dispatchers.IO) {
                    items.forEach { music ->
                        // 从数据库删除记录（本地音乐信息缓存）
                        localMusicInfoRepository.deleteByPath(music.path)
                        Log.d(TAG, "已从数据库移除记录: ${music.path}")

                        // 如果需要，删除文件
                        if (deleteFile) {
                            try {
                                // 对于 SAF URI (content://)，使用 ContentResolver 删除
                                if (music.path.startsWith("content://")) {
                                    val deleted = deleteSafFile(music.path)
                                    if (deleted) {
                                        deletedFileCount++
                                        Log.d(TAG, "已删除 SAF URI 文件: ${music.path}")
                                    } else {
                                        failedFileCount++
                                        Log.w(TAG, "删除 SAF URI 文件失败: ${music.path}")
                                    }
                                } else {
                                    val file = File(music.path)
                                    if (file.exists()) {
                                        if (file.delete()) {
                                            deletedFileCount++
                                            Log.d(TAG, "已删除文件: ${music.path}")
                                        } else {
                                            failedFileCount++
                                            Log.w(TAG, "删除文件失败: ${music.path}")
                                        }
                                    } else {
                                        Log.w(TAG, "文件不存在，跳过删除: ${music.path}")
                                    }
                                }
                            } catch (e: Exception) {
                                failedFileCount++
                                Log.e(TAG, "删除文件时出错: ${music.path}", e)
                            }
                        }
                    }
                }

                // 从内存列表中移除
                localMusicList.removeAll(items)
                filteredMusicList.removeAll(items)
                adapter.submitList(filteredMusicList.toList())
                updateSongCount()

                // 显示结果消息
                val message = when {
                    deleteFile && deletedFileCount > 0 && failedFileCount == 0 ->
                        "已移除 ${items.size} 个记录并删除 $deletedFileCount 个文件"
                    deleteFile && deletedFileCount > 0 && failedFileCount > 0 ->
                        "已移除 ${items.size} 个记录，成功删除 $deletedFileCount 个文件，$failedFileCount 个失败"
                    deleteFile && deletedFileCount == 0 && failedFileCount > 0 ->
                        "已移除 ${items.size} 个记录，但文件删除失败"
                    else ->
                        "已移除 ${items.size} 个记录"
                }
                Toast.makeText(this@LocalMusicActivity, message, Toast.LENGTH_SHORT).show()
                exitMultiSelectMode()
            } catch (e: Exception) {
                Log.e(TAG, "移除失败", e)
                Toast.makeText(this@LocalMusicActivity, "移除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 删除 SAF URI 文件
     * 使用 ContentResolver 删除通过 Storage Access Framework 获取的文件
     *
     * @param uriString 文件的 content:// URI
     * @return 是否删除成功
     */
    private fun deleteSafFile(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            // 使用 ContentResolver 删除文件
            val deleted = contentResolver.delete(uri, null, null)
            deleted > 0
        } catch (e: SecurityException) {
            // 如果没有权限删除文件，尝试使用 DocumentsContract 删除
            Log.w(TAG, "ContentResolver 删除失败，尝试使用 DocumentsContract: $uriString")
            try {
                val uri = Uri.parse(uriString)
                DocumentsContract.deleteDocument(contentResolver, uri)
            } catch (e2: Exception) {
                Log.e(TAG, "DocumentsContract 删除失败: $uriString", e2)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除 SAF 文件失败: $uriString", e)
            false
        }
    }

    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
        } else {
            super.onBackPressed()
        }
    }
}
