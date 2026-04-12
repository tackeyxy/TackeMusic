package com.tacke.music.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.model.DownloadTask
import com.tacke.music.download.DownloadManager
import com.tacke.music.ui.CacheManageActivity
import com.tacke.music.utils.CacheManager
import com.tacke.music.utils.DownloadQualityChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 缓存管理服务
 * 负责：
 * 1. 边听边存 - 后台创建下载任务下载当前播放的歌曲（到下载目录，非缓存目录）
 * 2. 在线播放自动缓存 - 缓存正在播放的歌曲文件（到缓存目录）
 * 3. 过期缓存自动清理（只清理缓存目录，不清理下载目录）
 * 4. 多平台音质对比和替换
 */
class CacheManagerService : Service() {

    private val TAG = "CacheManagerService"

    private lateinit var database: AppDatabase
    private lateinit var downloadManager: DownloadManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentDownloadJob: Job? = null
    private var previousSongId: String? = null
    private var currentSongId: String? = null

    companion object {
        const val ACTION_START_LISTEN_WHILE_CACHE = "action_start_listen_while_cache"
        const val ACTION_STOP_LISTEN_WHILE_CACHE = "action_stop_listen_while_cache"
        const val ACTION_CACHE_ONLINE_SONG = "action_cache_online_song"
        const val ACTION_CLEAN_EXPIRED_CACHE = "action_clean_expired_cache"
        const val ACTION_SONG_CHANGED = "action_song_changed"

        const val EXTRA_SONG_ID = "extra_song_id"
        const val EXTRA_SONG_NAME = "extra_song_name"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_PLATFORM = "extra_platform"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_SONG_URL = "extra_song_url"

        fun startListenWhileCache(context: Context, songId: String, songName: String, artist: String, platform: String, quality: String, songUrl: String) {
            val intent = Intent(context, CacheManagerService::class.java).apply {
                action = ACTION_START_LISTEN_WHILE_CACHE
                putExtra(EXTRA_SONG_ID, songId)
                putExtra(EXTRA_SONG_NAME, songName)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_PLATFORM, platform)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_SONG_URL, songUrl)
            }
            context.startService(intent)
        }

        fun stopListenWhileCache(context: Context) {
            val intent = Intent(context, CacheManagerService::class.java).apply {
                action = ACTION_STOP_LISTEN_WHILE_CACHE
            }
            context.startService(intent)
        }

        fun cacheOnlineSong(context: Context, songId: String, songName: String, artist: String, platform: String, quality: String, songUrl: String) {
            val intent = Intent(context, CacheManagerService::class.java).apply {
                action = ACTION_CACHE_ONLINE_SONG
                putExtra(EXTRA_SONG_ID, songId)
                putExtra(EXTRA_SONG_NAME, songName)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_PLATFORM, platform)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_SONG_URL, songUrl)
            }
            context.startService(intent)
        }

        fun cleanExpiredCache(context: Context) {
            val intent = Intent(context, CacheManagerService::class.java).apply {
                action = ACTION_CLEAN_EXPIRED_CACHE
            }
            context.startService(intent)
        }

        fun notifySongChanged(context: Context, songId: String) {
            val intent = Intent(context, CacheManagerService::class.java).apply {
                action = ACTION_SONG_CHANGED
                putExtra(EXTRA_SONG_ID, songId)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        downloadManager = DownloadManager.getInstance(this)
        Log.d(TAG, "CacheManagerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTEN_WHILE_CACHE -> {
                val songId = intent.getStringExtra(EXTRA_SONG_ID) ?: return START_NOT_STICKY
                val songName = intent.getStringExtra(EXTRA_SONG_NAME) ?: return START_NOT_STICKY
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: return START_NOT_STICKY
                val platform = intent.getStringExtra(EXTRA_PLATFORM) ?: return START_NOT_STICKY
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: return START_NOT_STICKY
                val songUrl = intent.getStringExtra(EXTRA_SONG_URL) ?: return START_NOT_STICKY

                startListenWhileCache(songId, songName, artist, platform, quality, songUrl)
            }
            ACTION_STOP_LISTEN_WHILE_CACHE -> {
                stopListenWhileCache()
            }
            ACTION_CACHE_ONLINE_SONG -> {
                val songId = intent.getStringExtra(EXTRA_SONG_ID) ?: return START_NOT_STICKY
                val songName = intent.getStringExtra(EXTRA_SONG_NAME) ?: return START_NOT_STICKY
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: return START_NOT_STICKY
                val platform = intent.getStringExtra(EXTRA_PLATFORM) ?: return START_NOT_STICKY
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: return START_NOT_STICKY
                val songUrl = intent.getStringExtra(EXTRA_SONG_URL) ?: return START_NOT_STICKY

                cacheOnlineSong(songId, songName, artist, platform, quality, songUrl)
            }
            ACTION_CLEAN_EXPIRED_CACHE -> {
                cleanExpiredCache()
            }
            ACTION_SONG_CHANGED -> {
                val songId = intent.getStringExtra(EXTRA_SONG_ID)
                onSongChanged(songId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        currentDownloadJob?.cancel()
        super.onDestroy()
        Log.d(TAG, "CacheManagerService destroyed")
    }

    /**
     * 边听边存 - 后台创建下载任务下载当前播放的歌曲
     * 注意：边听边存下载到下载目录，不是缓存目录，不受缓存清理影响
     * 
     * 条件：下载历史列表和本地歌曲列表不存在该歌（忽略音源），
     *       或者存在的歌曲音质低于当前试听音质
     * 
     * 关键修复：如果已存在更高或相同音质的文件，提示用户；如果存在低音质文件，则删除后下载新高音质
     */
    private fun startListenWhileCache(songId: String, songName: String, artist: String, platform: String, quality: String, songUrl: String) {
        if (!com.tacke.music.ui.SettingsActivity.isListenWhileCacheEnabled(this)) {
            Log.d(TAG, "边听边存未开启，跳过")
            return
        }
        
        // 关键修复：跳过本地歌曲（songId 以 local_ 开头）
        if (songId.startsWith("local_")) {
            Log.d(TAG, "本地歌曲，跳过边听边存: $songName")
            return
        }

        serviceScope.launch {
            // 检查是否已在下载队列中
            val existingTask = downloadManager.downloadingTasks.value.find { it.id == songId }
            if (existingTask != null) {
                Log.d(TAG, "歌曲已在下载队列中，跳过边听边存: $songName")
                return@launch
            }

            // 关键修复：首先检查音质，无论 URL 是网络还是本地文件
            val (hasHigherOrEqualQuality, existingFilePath, source) = DownloadQualityChecker.checkExistingDownloadQuality(
                this@CacheManagerService,
                songId,
                songName,
                artist,
                quality
            )

            if (hasHigherOrEqualQuality) {
                // 已存在更高或相同音质的文件，提示用户
                val message = "歌曲《${songName}》的更高音质或相同音质的文件已存在，请在本地歌曲列表扫描添加！"
                Log.d(TAG, message)
                // 在主线程显示 Toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CacheManagerService, message, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // 如果存在低音质文件，删除它
            if (existingFilePath != null) {
                Log.d(TAG, "删除已存在的低音质文件: $existingFilePath")
                DownloadQualityChecker.deleteExistingFile(existingFilePath)
                // 同时从下载历史中删除记录
                DownloadQualityChecker.deleteDownloadRecord(this@CacheManagerService, songId)
            }
            
            // 关键修复：检查 URL 是否为网络 URL（以 http:// 或 https:// 开头）
            // 注意：这个检查放在音质检测之后，确保低音质文件被删除后，可以下载网络版本
            if (!songUrl.startsWith("http://") && !songUrl.startsWith("https://")) {
                Log.d(TAG, "URL 不是网络链接，无法下载: $songName, url=$songUrl")
                return@launch
            }

            // 检查本地歌曲列表中是否已存在该歌曲（忽略音源）
            // 注意：本地歌曲通过数据库中的 local_music_info 表管理
            val localMusicInfoDao = database.localMusicInfoDao()
            val localMusicEntities = localMusicInfoDao.getAllSync()
            val existingLocalSong = localMusicEntities.find { 
                // 通过歌曲名和歌手名匹配（因为本地歌曲没有 songId）
                it.title == songName && it.artist == artist 
            }
            
            if (existingLocalSong != null) {
                // 本地歌曲的音质信息无法直接从数据库获取，需要通过文件检查
                // 这里我们假设如果本地存在该歌曲，就跳过下载（因为无法准确比较音质）
                Log.d(TAG, "本地歌曲列表中已存在该歌曲，跳过边听边存: $songName")
                return@launch
            }

            // 创建下载任务（到下载目录，不是缓存目录）
            // 生成文件名和文件路径
            // 关键修复：处理文件名中的非法字符，避免文件创建失败
            val safeSongName = songName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val safeArtist = artist.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            // 根据音质选择正确的文件扩展名
            val fileExtension = when (quality.lowercase()) {
                "flac", "flac24bit" -> "flac"
                else -> "mp3"
            }
            val fileName = "${safeSongName}-${safeArtist}.${fileExtension}"
            // 关键修复：使用设置中指定的下载路径，确保与音质检测逻辑一致
            val downloadPath = com.tacke.music.ui.SettingsActivity.getDefaultDownloadPath(this@CacheManagerService)
            val downloadDir = File(downloadPath)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            val filePath = File(downloadDir, fileName).absolutePath

            val downloadTask = DownloadTask(
                id = songId,
                songId = songId,
                songName = songName,
                artist = artist,
                coverUrl = null,
                url = songUrl,
                fileName = fileName,
                filePath = filePath,
                platform = platform,
                quality = quality
            )

            // 添加到下载管理器（这会下载到下载目录）
            downloadManager.startDownload(downloadTask)
            Log.d(TAG, "边听边存已创建下载任务: $songName, 音质: $quality")
        }
    }

    private fun stopListenWhileCache() {
        Log.d(TAG, "边听边存停止（下载任务会继续在后台执行）")
    }

    /**
     * 在线播放自动缓存
     * 缓存当前播放的歌曲文件到本地缓存目录
     */
    private fun cacheOnlineSong(songId: String, songName: String, artist: String, platform: String, quality: String, songUrl: String) {
        serviceScope.launch {
            // 检查是否已下载（在下载目录中）- 已下载的歌曲不需要缓存
            val existingDownload = downloadManager.getLocalSongPath(songId)
            if (existingDownload != null) {
                Log.d(TAG, "歌曲已下载，跳过自动缓存: $songName")
                return@launch
            }

            // 检查缓存音质对比
            val existingCache = CacheManager.getCachedSongInfo(this@CacheManagerService, songId)
            if (existingCache != null) {
                val currentQualityLevel = getQualityLevel(quality)
                val cachedQualityLevel = getQualityLevel(existingCache.quality)

                if (currentQualityLevel <= cachedQualityLevel) {
                    Log.d(TAG, "当前音质($quality)不高于缓存音质(${existingCache.quality})，跳过缓存: $songName")
                    return@launch
                } else {
                    Log.d(TAG, "当前音质($quality)高于缓存音质(${existingCache.quality})，替换缓存: $songName")
                    // 删除旧缓存
                    File(existingCache.filePath).delete()
                }
            }

            // 检查多平台音质对比
            if (existingCache != null && existingCache.platform != platform) {
                val shouldCache = checkQualityForCrossPlatform(songId, platform, quality, existingCache)
                if (!shouldCache) {
                    Log.d(TAG, "跨平台音质对比：本地缓存音质更高或相等，跳过缓存: $songName")
                    return@launch
                }
            }

            // 缓存新音质到缓存目录
            val onlineSongsDir = File(cacheDir, "online_songs")
            if (!onlineSongsDir.exists()) onlineSongsDir.mkdirs()
            val cacheFile = File(onlineSongsDir, "${songId}_${quality}.mp3")

            downloadSongToCache(songId, songUrl, cacheFile, quality, platform)
        }
    }

    /**
     * 下载歌曲到缓存目录
     */
    private suspend fun downloadSongToCache(songId: String, songUrl: String, cacheFile: File, quality: String, platform: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始缓存歌曲到缓存目录: $songId, 音质: $quality")

            val request = Request.Builder()
                .url(songUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "缓存歌曲失败，HTTP错误: ${response.code}")
                return@withContext
            }

            val inputStream = response.body?.byteStream() ?: return@withContext
            val outputStream = cacheFile.outputStream()

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            // 保存缓存信息到数据库
            database.songDetailDao().insertSongDetail(
                com.tacke.music.data.db.SongDetailEntity(
                    id = songId,
                    name = "",
                    artists = "",
                    platform = platform,
                    playUrl = cacheFile.absolutePath,
                    coverUrl = null,
                    lyrics = null,
                    quality = quality,
                    updatedAt = System.currentTimeMillis()
                )
            )

            Log.d(TAG, "歌曲缓存完成: $songId, 路径: ${cacheFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "缓存歌曲失败: ${e.message}", e)
            cacheFile.delete()
        }
    }

    /**
     * 歌曲切换时的处理
     * 清理上一首歌曲的旧音质缓存（如果新音质更高）
     */
    private fun onSongChanged(newSongId: String?) {
        serviceScope.launch {
            // 保存上一首歌曲ID
            val prevId = previousSongId
            previousSongId = currentSongId
            currentSongId = newSongId

            if (prevId != null && prevId != newSongId) {
                // 获取上一首歌曲的缓存信息
                val cachedInfo = CacheManager.getCachedSongInfo(this@CacheManagerService, prevId)
                if (cachedInfo != null) {
                    // 延迟一段时间后检查是否需要清理旧音质
                    delay(5000) // 5秒后检查

                    val latestInfo = CacheManager.getCachedSongInfo(this@CacheManagerService, prevId)
                    if (latestInfo != null && latestInfo.quality != cachedInfo.quality) {
                        val oldQualityLevel = getQualityLevel(cachedInfo.quality)
                        val newQualityLevel = getQualityLevel(latestInfo.quality)

                        // 如果新音质高于旧音质，删除旧音质文件
                        if (newQualityLevel > oldQualityLevel) {
                            Log.d(TAG, "新音质(${latestInfo.quality})高于旧音质(${cachedInfo.quality})，删除旧缓存")
                            val oldFile = File(cachedInfo.filePath)
                            if (oldFile.exists()) {
                                oldFile.delete()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 清理过期缓存（只清理缓存目录，不清理下载目录）
     */
    private fun cleanExpiredCache() {
        serviceScope.launch {
            Log.d(TAG, "开始清理过期缓存")
            CacheManager.cleanExpiredCache(this@CacheManagerService)
            Log.d(TAG, "过期缓存清理完成")
        }
    }

    /**
     * 跨平台音质对比
     * 检查是否应该缓存B平台的歌曲（当A平台已有缓存时）
     */
    private suspend fun checkQualityForCrossPlatform(
        songId: String,
        newPlatform: String,
        newQuality: String,
        existingCache: CacheManager.CachedSongInfo
    ): Boolean {
        // 如果平台相同，直接比较音质
        if (existingCache.platform == newPlatform) {
            return getQualityLevel(newQuality) > getQualityLevel(existingCache.quality)
        }

        // 不同平台，比较音质等级
        val newQualityLevel = getQualityLevel(newQuality)
        val existingQualityLevel = getQualityLevel(existingCache.quality)

        // 如果新音质小于等于现有缓存音质，不缓存
        if (newQualityLevel <= existingQualityLevel) {
            return false
        }

        // 新音质更高，允许缓存并替换
        return true
    }

    /**
     * 获取音质等级，用于比较
     */
    private fun getQualityLevel(quality: String): Int {
        return when (quality.lowercase()) {
            "flac24bit" -> 5
            "flac" -> 4
            "320k" -> 3
            "192k" -> 2
            "128k" -> 1
            else -> 0
        }
    }
}