package com.tacke.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.tacke.music.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 歌曲封面图片管理器
 * 负责下载、缓存和加载歌曲封面图片
 */
object CoverImageManager {

    private const val TAG = "CoverImageManager"
    private const val COVER_CACHE_DIR = "song_covers"

    /**
     * 获取封面图片的本地缓存路径
     * @param context 上下文
     * @param songId 歌曲ID
     * @param platform 平台标识 (kuwo/netease)
     * @return 本地图片文件路径，如果不存在返回null
     */
    fun getCoverPath(context: Context, songId: String, platform: String): String? {
        val cacheDir = File(context.cacheDir, COVER_CACHE_DIR)
        val fileName = generateFileName(songId, platform)
        val coverFile = File(cacheDir, fileName)
        return if (coverFile.exists()) coverFile.absolutePath else null
    }

    /**
     * 下载并缓存歌曲封面图片
     * @param context 上下文
     * @param songId 歌曲ID
     * @param platform 平台 (kuwo/netease)
     * @param quality 音质质量，用于获取歌曲详情
     * @return 本地图片文件路径，下载失败返回null
     */
    suspend fun downloadAndCacheCover(
        context: Context,
        songId: String,
        platform: String,
        quality: String = "320k"
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 检查是否已缓存
            val existingPath = getCoverPath(context, songId, platform)
            if (existingPath != null) {
                Log.d(TAG, "封面已缓存: $existingPath")
                return@withContext existingPath
            }

            // 获取歌曲详情中的封面URL
            val platformEnum = when (platform.lowercase()) {
                "kuwo" -> MusicRepository.Platform.KUWO
                "netease" -> MusicRepository.Platform.NETEASE
                else -> {
                    Log.e(TAG, "未知的平台: $platform")
                    return@withContext null
                }
            }

            val musicRepository = MusicRepository()
            val songDetail = musicRepository.getSongDetail(platformEnum, songId, quality)
            
            val coverUrl = songDetail?.cover
            if (coverUrl.isNullOrEmpty()) {
                Log.e(TAG, "无法获取封面URL: songId=$songId, platform=$platform")
                return@withContext null
            }

            // 下载图片
            val bitmap = downloadImage(coverUrl)
            if (bitmap == null) {
                Log.e(TAG, "下载图片失败: $coverUrl")
                return@withContext null
            }

            // 保存到本地缓存
            val cachePath = saveBitmapToCache(context, bitmap, songId, platform)
            if (cachePath != null) {
                Log.d(TAG, "封面已下载并缓存: $cachePath")
            }
            cachePath
        } catch (e: Exception) {
            Log.e(TAG, "下载封面失败: ${e.message}", e)
            null
        }
    }

    /**
     * 批量下载并缓存歌曲封面
     * @param context 上下文
     * @param songs 歌曲列表，每个元素包含 songId 和 platform
     * @param quality 音质质量
     * @return 下载成功的歌曲ID到本地路径的映射
     */
    suspend fun downloadAndCacheCovers(
        context: Context,
        songs: List<Pair<String, String>>, // Pair<songId, platform>
        quality: String = "320k"
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, String>()
        songs.forEach { (songId, platform) ->
            val path = downloadAndCacheCover(context, songId, platform, quality)
            if (path != null) {
                results[songId] = path
            }
        }
        results
    }

    /**
     * 从网络下载图片
     */
    private fun downloadImage(urlString: String): Bitmap? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                doInput = true
                connect()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } else {
                Log.e(TAG, "HTTP错误: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载图片异常: ${e.message}", e)
            null
        }
    }

    /**
     * 将Bitmap保存到缓存目录
     */
    private fun saveBitmapToCache(
        context: Context,
        bitmap: Bitmap,
        songId: String,
        platform: String
    ): String? {
        return try {
            val cacheDir = File(context.cacheDir, COVER_CACHE_DIR)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val fileName = generateFileName(songId, platform)
            val coverFile = File(cacheDir, fileName)

            FileOutputStream(coverFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            }

            coverFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存图片失败: ${e.message}", e)
            null
        }
    }

    /**
     * 生成缓存文件名
     */
    private fun generateFileName(songId: String, platform: String): String {
        val hash = md5("$songId-$platform")
        return "cover_${hash}.jpg"
    }

    /**
     * MD5哈希
     */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 加载本地缓存的封面图片
     * @param context 上下文
     * @param songId 歌曲ID
     * @param platform 平台
     * @return Bitmap对象，如果不存在返回null
     */
    fun loadCoverBitmap(context: Context, songId: String, platform: String): Bitmap? {
        val path = getCoverPath(context, songId, platform) ?: return null
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            Log.e(TAG, "加载图片失败: ${e.message}", e)
            null
        }
    }

    /**
     * 清除所有缓存的封面图片
     */
    fun clearAllCache(context: Context): Boolean {
        return try {
            val cacheDir = File(context.cacheDir, COVER_CACHE_DIR)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "清除缓存失败: ${e.message}", e)
            false
        }
    }

    /**
     * 获取缓存大小（字节）
     */
    fun getCacheSize(context: Context): Long {
        val cacheDir = File(context.cacheDir, COVER_CACHE_DIR)
        return if (cacheDir.exists()) {
            cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } else {
            0L
        }
    }
}
