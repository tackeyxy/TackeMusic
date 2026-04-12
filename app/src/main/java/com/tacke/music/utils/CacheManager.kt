package com.tacke.music.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.db.SongDetailEntity
import com.tacke.music.ui.CacheManageActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object CacheManager {
    private const val TAG = "CacheManager"
    
    const val CACHE_TYPE_SONG_FILE = "song_file"
    const val CACHE_TYPE_LYRICS = "lyrics"
    const val CACHE_TYPE_COVER = "cover"
    const val CACHE_TYPE_SONG_INFO = "song_info"
    
    data class CacheInfo(
        val type: String,
        val size: Long,
        val description: String
    )
    
    suspend fun getAllCacheInfo(context: Context): List<CacheInfo> = withContext(Dispatchers.IO) {
        val cacheList = mutableListOf<CacheInfo>()
        
        val songFileCache = getSongFileCacheSize(context)
        cacheList.add(CacheInfo(
            type = CACHE_TYPE_SONG_FILE,
            size = songFileCache,
            description = "歌曲文件缓存"
        ))
        
        val lyricsCache = getLyricsCacheSize(context)
        cacheList.add(CacheInfo(
            type = CACHE_TYPE_LYRICS,
            size = lyricsCache,
            description = "歌词缓存"
        ))
        
        val coverCache = getCoverCacheSize(context)
        cacheList.add(CacheInfo(
            type = CACHE_TYPE_COVER,
            size = coverCache,
            description = "歌曲图缓存"
        ))
        
        val songInfoCache = getSongInfoCacheSize(context)
        cacheList.add(CacheInfo(
            type = CACHE_TYPE_SONG_INFO,
            size = songInfoCache,
            description = "歌曲信息缓存"
        ))
        
        cacheList
    }
    
    fun getTotalCacheSize(context: Context): Long {
        return getSongFileCacheSize(context) + 
               getLyricsCacheSize(context) + 
               getCoverCacheSize(context) + 
               getSongInfoCacheSize(context)
    }
    
    private fun getSongFileCacheSize(context: Context): Long {
        val cacheDir = File(context.cacheDir, "online_songs")
        return getDirSize(cacheDir)
    }
    
    private fun getLyricsCacheSize(context: Context): Long {
        val cacheDir = File(context.cacheDir, "lyrics")
        return getDirSize(cacheDir)
    }
    
    private fun getCoverCacheSize(context: Context): Long {
        val cacheDir = File(context.cacheDir, "song_covers")
        return getDirSize(cacheDir)
    }
    
    private fun getSongInfoCacheSize(context: Context): Long {
        return 0L
    }
    
    private fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
    
    suspend fun clearCache(context: Context, cacheType: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            when (cacheType) {
                CACHE_TYPE_SONG_FILE -> clearSongFileCache(context)
                CACHE_TYPE_LYRICS -> clearLyricsCache(context)
                CACHE_TYPE_COVER -> clearCoverCache(context)
                CACHE_TYPE_SONG_INFO -> clearSongInfoCache(context)
                null -> {
                    clearSongFileCache(context)
                    clearLyricsCache(context)
                    clearCoverCache(context)
                    clearSongInfoCache(context)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "清除缓存失败: ${e.message}", e)
            false
        }
    }
    
    private suspend fun clearSongFileCache(context: Context) = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "online_songs")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }
    
    private suspend fun clearLyricsCache(context: Context) = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "lyrics")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }
    
    private suspend fun clearCoverCache(context: Context) = withContext(Dispatchers.IO) {
        CoverImageManager.clearAllCache(context)
    }
    
    private suspend fun clearSongInfoCache(context: Context) = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context)
        database.songDetailDao().clearAllSongDetails()
    }
    
    suspend fun cleanExpiredCache(context: Context) = withContext(Dispatchers.IO) {
        val expiryDays = CacheManageActivity.getCacheExpiryDays(context)
        val expiryMillis = TimeUnit.DAYS.toMillis(expiryDays.toLong())
        val currentTime = System.currentTimeMillis()
        
        val database = AppDatabase.getDatabase(context)
        val songDetailDao = database.songDetailDao()
        
        val allDetails = songDetailDao.getAllSongDetailsOnce()
        
        for (detail in allDetails) {
            val age = currentTime - detail.updatedAt
            if (age > expiryMillis) {
                val cachedSongFile = getCachedSongFile(context, detail.id)
                if (cachedSongFile != null && !isProtectedSongFile(context, cachedSongFile.absolutePath)) {
                    cachedSongFile.delete()
                }
                songDetailDao.deleteSongDetailById(detail.id)
            }
        }

        val onlineSongsDir = File(context.cacheDir, "online_songs")
        if (onlineSongsDir.exists()) {
            onlineSongsDir.listFiles()?.forEach { file ->
                val age = currentTime - file.lastModified()
                if (age > expiryMillis && !isProtectedSongFile(context, file.absolutePath)) {
                    file.delete()
                }
            }
        }
    }
    
    fun isProtectedSongFile(context: Context, filePath: String): Boolean {
        val protectedFiles = context.getSharedPreferences("protected_cache", Context.MODE_PRIVATE)
        return protectedFiles.getBoolean(filePath, false)
    }

    private fun getCachedSongFile(context: Context, songId: String): File? {
        val cacheDir = File(context.cacheDir, "online_songs")
        val songFile = File(cacheDir, "$songId.mp3")
        return if (songFile.exists()) songFile else null
    }

    fun protectSongFile(context: Context, filePath: String, protect: Boolean) {
        val protectedFiles = context.getSharedPreferences("protected_cache", Context.MODE_PRIVATE)
        protectedFiles.edit().putBoolean(filePath, protect).apply()
    }
    
    suspend fun saveOnlineSong(context: Context, songId: String, data: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "online_songs")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val songFile = File(cacheDir, "$songId.mp3")
            songFile.writeBytes(data)
            songFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存在线歌曲失败: ${e.message}", e)
            null
        }
    }
    
    fun getCachedSongPath(context: Context, songId: String): String? {
        val cacheDir = File(context.cacheDir, "online_songs")
        val songFile = File(cacheDir, "$songId.mp3")
        return if (songFile.exists()) songFile.absolutePath else null
    }
    
    suspend fun getCachedSongInfo(context: Context, songId: String): CachedSongInfo? = withContext(Dispatchers.IO) {
        val songFile = getCachedSongFile(context, songId)
        if (songFile != null && songFile.exists()) {
            val database = AppDatabase.getDatabase(context)
            val detail = database.songDetailDao().getSongDetailOnce(songId)
            CachedSongInfo(
                songId = songId,
                filePath = songFile.absolutePath,
                fileSize = songFile.length(),
                quality = detail?.quality ?: "320k",
                lastAccessed = songFile.lastModified(),
                platform = detail?.platform ?: "KUWO"
            )
        } else {
            null
        }
    }
    
    suspend fun removeOldQualityCache(context: Context, songId: String, newQuality: String) = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context)
        val detail = database.songDetailDao().getSongDetailOnce(songId)
        
        if (detail != null && detail.quality != newQuality) {
            val cacheDir = File(context.cacheDir, "online_songs")
            val oldFile = File(cacheDir, "${songId}_${detail.quality}.mp3")
            if (oldFile.exists() && !isProtectedSongFile(context, oldFile.absolutePath)) {
                oldFile.delete()
            }
        }
    }
    
    data class CachedSongInfo(
        val songId: String,
        val filePath: String,
        val fileSize: Long,
        val quality: String,
        val lastAccessed: Long,
        val platform: String
    )
}
