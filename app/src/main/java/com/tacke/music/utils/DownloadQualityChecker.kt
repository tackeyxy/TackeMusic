package com.tacke.music.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.model.DownloadTask
import com.tacke.music.download.DownloadManager
import com.tacke.music.ui.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载音质检查工具类
 * 用于检查已下载文件的音质，并在需要时替换低音质文件
 * 
 * 关键功能：
 * 1. 检查下载历史记录（数据库）
 * 2. 扫描下载文件夹内的实际文件
 * 3. 支持通过歌曲名和艺术家匹配文件
 */
object DownloadQualityChecker {

    private const val TAG = "DownloadQualityChecker"

    // 音质等级映射
    fun getQualityLevel(quality: String): Int {
        return when (quality.lowercase()) {
            "flac24bit" -> 5
            "flac" -> 4
            "320k" -> 3
            "192k" -> 2
            "128k" -> 1
            else -> 0
        }
    }

    /**
     * 从音频文件提取准确音质（使用 MediaMetadataRetriever）
     * 优先使用比特率信息，如果无法获取则回退到文件名解析
     */
    private fun extractQualityFromFile(file: File): String {
        val fileName = file.name.lowercase()
        
        // 1. 首先检查文件名是否包含音质标识（最可靠）
        when {
            fileName.contains("flac24bit") || fileName.contains("24bit") -> return "flac24bit"
            fileName.contains("flac") && fileName.contains("16bit") -> return "flac"
            fileName.contains("320k") || fileName.contains("320kbps") -> return "320k"
            fileName.contains("192k") || fileName.contains("192kbps") -> return "192k"
            fileName.contains("128k") || fileName.contains("128kbps") -> return "128k"
        }
        
        // 2. 使用 MediaMetadataRetriever 获取准确的比特率
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            
            // 获取比特率（单位：bps）
            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val bitrate = bitrateStr?.toIntOrNull()
            
            if (bitrate != null && bitrate > 0) {
                // 将 bps 转换为 kbps 并判断音质
                val kbps = bitrate / 1000
                Log.d(TAG, "从文件提取到比特率: ${file.name}, ${kbps}kbps")
                
                return when {
                    kbps >= 320 -> "320k"
                    kbps >= 256 -> "320k" // 256k 也归类为 320k
                    kbps >= 192 -> "192k"
                    kbps >= 128 -> "128k"
                    else -> "128k"
                }
            }
            
            // 如果是 FLAC 文件但没有比特率信息，检查采样率和位深度
            if (fileName.endsWith(".flac")) {
                // 尝试获取位深度（Android 10+ 支持）
                val bitsPerSample = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                if (bitsPerSample != null) {
                    val bits = bitsPerSample.toIntOrNull() ?: 16
                    Log.d(TAG, "FLAC 文件位深度: ${file.name}, ${bits}bit")
                    return if (bits >= 24) "flac24bit" else "flac"
                }
                // 无法获取位深度，根据文件大小估算
                val fileSizeMB = file.length() / (1024 * 1024)
                return if (fileSizeMB > 38) "flac24bit" else "flac"
            }
        } catch (e: Exception) {
            Log.w(TAG, "提取音频元数据失败: ${file.name}, ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        
        // 3. 回退到文件扩展名和大小估算
        return when {
            fileName.endsWith(".flac") -> {
                val fileSizeMB = file.length() / (1024 * 1024)
                if (fileSizeMB > 38) "flac24bit" else "flac"
            }
            fileName.endsWith(".mp3") -> {
                val fileSizeMB = file.length() / (1024 * 1024)
                when {
                    fileSizeMB >= 8 -> "320k"
                    fileSizeMB >= 5 -> "192k"
                    else -> "128k"
                }
            }
            else -> "128k"
        }
    }

    /**
     * 检查下载文件夹内是否存在同一首歌的文件
     * 通过歌曲名和艺术家名匹配文件名
     * @return Pair<是否存在更高或相同音质的文件, 已存在的文件路径>
     */
    private suspend fun checkDownloadFolder(
        context: Context,
        songName: String,
        artist: String,
        newQuality: String
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val downloadPath = SettingsActivity.getDefaultDownloadPath(context)
        val downloadDir = File(downloadPath)
        
        if (!downloadDir.exists() || !downloadDir.isDirectory) {
            Log.d(TAG, "下载文件夹不存在: $downloadPath")
            return@withContext Pair(false, null)
        }
        
        // 安全化处理歌曲名和艺术家名（去除非法字符）
        val safeSongName = songName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val safeArtist = artist.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        
        // 关键修复：更灵活的文件名匹配
        // 文件名格式：歌曲名-艺术家.扩展名
        // 但需要考虑：1. 歌曲名/艺术家可能被截断 2. 特殊字符被替换 3. 不同扩展名
        val allFiles = downloadDir.listFiles { file ->
            file.isFile && (file.extension.equals("mp3", ignoreCase = true) || 
                          file.extension.equals("flac", ignoreCase = true))
        }
        
        if (allFiles.isNullOrEmpty()) {
            Log.d(TAG, "下载文件夹中没有音频文件: $downloadPath")
            return@withContext Pair(false, null)
        }
        
        // 关键修复：使用模糊匹配查找同一首歌的不同版本
        // 匹配规则：文件名包含歌曲名（去除特殊字符后）
        val normalizedSongName = safeSongName.lowercase()
        val normalizedArtist = safeArtist.lowercase()
        
        val files = allFiles.filter { file ->
            val fileNameLower = file.nameWithoutExtension.lowercase()
            // 检查文件名是否包含歌曲名
            val songNameMatch = fileNameLower.contains(normalizedSongName) || 
                               normalizedSongName.contains(fileNameLower)
            // 如果艺术家不为空，也检查艺术家
            val artistMatch = if (normalizedArtist.isNotEmpty()) {
                fileNameLower.contains(normalizedArtist) || normalizedArtist.contains(fileNameLower)
            } else true
            
            songNameMatch && artistMatch
        }
        
        if (files.isNullOrEmpty()) {
            Log.d(TAG, "下载文件夹中未找到匹配文件: $songName - $artist")
            return@withContext Pair(false, null)
        }
        
        // 关键修复：遍历所有匹配的文件，找到最高音质的那个
        var highestQualityFile: File? = null
        var highestQualityLevel = -1
        var lowestQualityFile: File? = null
        var lowestQualityLevel = Int.MAX_VALUE
        
        for (file in files) {
            val fileQuality = extractQualityFromFile(file)
            val fileQualityLevel = getQualityLevel(fileQuality)
            val newQualityLevel = getQualityLevel(newQuality)
            
            Log.d(TAG, "下载文件夹中找到匹配文件: ${file.name}, 大小=${file.length() / 1024}KB, 解析音质=$fileQuality(${fileQualityLevel}), 目标音质=$newQuality(${newQualityLevel})")
            
            // 记录最高音质的文件
            if (fileQualityLevel > highestQualityLevel) {
                highestQualityLevel = fileQualityLevel
                highestQualityFile = file
            }
            
            // 记录最低音质的文件（用于删除）
            if (fileQualityLevel < lowestQualityLevel) {
                lowestQualityLevel = fileQualityLevel
                lowestQualityFile = file
            }
            
            // 如果找到更高或相同音质的文件，立即返回
            if (fileQualityLevel >= newQualityLevel) {
                Log.d(TAG, "下载文件夹中已存在更高或相同音质: $fileQuality($fileQualityLevel) >= $newQuality($newQualityLevel)")
                return@withContext Pair(true, file.absolutePath)
            }
        }
        
        // 所有匹配文件的音质都低于目标音质，返回最低音质文件路径用于删除
        if (lowestQualityFile != null) {
            Log.d(TAG, "下载文件夹中已存在但音质都更低，最高音质: ${highestQualityFile?.name}($highestQualityLevel), 目标: $newQuality(${getQualityLevel(newQuality)})")
            return@withContext Pair(false, lowestQualityFile.absolutePath)
        }
        
        return@withContext Pair(false, null)
    }

    /**
     * 检查已下载文件的音质（向后兼容版本，只检查数据库）
     * @return Pair<是否存在更高或相同音质的文件, 已存在的文件路径>
     */
    suspend fun checkExistingDownloadQuality(
        context: Context,
        songId: String,
        newQuality: String
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val result = checkExistingDownloadQuality(context, songId, "", "", newQuality)
        return@withContext Pair(result.first, result.second)
    }

    /**
     * 检查已下载文件的音质（完整版本）
     * 关键修复：同时检查数据库记录和下载文件夹内的实际文件
     * 如果数据库有记录但文件不存在，会自动清理数据库记录
     * @return Triple<是否存在更高或相同音质的文件, 已存在的文件路径, 检测来源>
     * 检测来源："database" - 数据库记录, "folder" - 下载文件夹, null - 未找到
     */
    suspend fun checkExistingDownloadQuality(
        context: Context,
        songId: String,
        songName: String,
        artist: String,
        newQuality: String
    ): Triple<Boolean, String?, String?> = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context)

        // 1. 首先检查下载历史记录（数据库）
        val downloadTaskDao = database.downloadTaskDao()
        val completedTaskEntities = downloadTaskDao.getCompletedTasksOnce()
        Log.d(TAG, "检查歌曲下载历史: songId=$songId, newQuality=$newQuality, 已完成任务数=${completedTaskEntities.size}")
        val existingTask = completedTaskEntities.find { it.songId == songId }

        if (existingTask != null) {
            Log.d(TAG, "找到已存在的下载任务: songId=$songId, 音质=${existingTask.quality}, 路径=${existingTask.filePath}")
            val file = File(existingTask.filePath)
            
            // 检查文件是否实际存在
            if (!file.exists() || file.length() == 0L) {
                // 数据库有记录但文件不存在，需要清理数据库记录并允许重新下载
                Log.d(TAG, "数据库有记录但文件不存在，清理记录并允许重新下载: ${existingTask.songName}, 路径: ${existingTask.filePath}")
                // 删除数据库记录
                try {
                    downloadTaskDao.deleteTask(existingTask)
                    Log.d(TAG, "已删除数据库中的无效记录: $songId")
                } catch (e: Exception) {
                    Log.e(TAG, "删除数据库记录失败: $songId, 错误: ${e.message}")
                }
                // 继续检查下载文件夹
            } else {
                // 文件存在，比较音质
                val existingQualityLevel = getQualityLevel(existingTask.quality)
                val newQualityLevel = getQualityLevel(newQuality)

                if (existingQualityLevel >= newQualityLevel) {
                    Log.d(TAG, "下载历史中已存在更高或相同音质: ${existingTask.quality} >= $newQuality, 歌曲: ${existingTask.songName}")
                    return@withContext Triple(true, existingTask.filePath, "database")
                } else {
                    // 存在但音质更低，返回文件路径用于后续删除
                    Log.d(TAG, "下载历史中已存在但音质更低: ${existingTask.quality} < $newQuality, 歌曲: ${existingTask.songName}")
                    return@withContext Triple(false, existingTask.filePath, "database")
                }
            }
        }

        // 2. 检查下载文件夹内的实际文件（通过歌曲名和艺术家匹配）
        // 只有当 songName 不为空时才检查下载文件夹（向后兼容）
        if (songName.isNotEmpty()) {
            val (folderHasHigherQuality, folderFilePath) = checkDownloadFolder(
                context,
                songName,
                artist,
                newQuality
            )
            
            if (folderFilePath != null) {
                if (folderHasHigherQuality) {
                    Log.d(TAG, "下载文件夹中已存在更高或相同音质: $newQuality, 歌曲: $songName")
                    return@withContext Triple(true, folderFilePath, "folder")
                } else {
                    Log.d(TAG, "下载文件夹中已存在但音质更低: $newQuality, 歌曲: $songName")
                    return@withContext Triple(false, folderFilePath, "folder")
                }
            }
        }

        // 3. 检查本地歌曲列表（通过数据库）
        val localMusicInfoDao = database.localMusicInfoDao()
        val localMusicEntities = localMusicInfoDao.getAllSync()

        // 注意：这里假设通过 songId 可以匹配到本地歌曲
        // 实际匹配逻辑可能需要根据歌曲名和艺术家
        val existingLocalSong = localMusicEntities.find { entity ->
            // 尝试通过文件路径中的 songId 匹配，或者通过其他方式
            // 这里简化处理，实际可能需要更复杂的匹配逻辑
            false // 暂时返回 false，避免误判
        }

        if (existingLocalSong != null) {
            // 本地歌曲没有音质信息，假设存在相同音质
            Log.d(TAG, "本地歌曲列表中已存在该歌曲")
            return@withContext Triple(true, existingLocalSong.path, "local")
        }

        return@withContext Triple(false, null, null)
    }

    /**
     * 删除或重命名已存在的低音质文件
     * 关键修复：如果无法删除（如文件被占用），则重命名为 .bak 文件
     * @return 是否成功处理（删除或重命名）
     */
    suspend fun deleteExistingFile(filePath: String?): Boolean = withContext(Dispatchers.IO) {
        if (filePath.isNullOrEmpty()) {
            return@withContext false
        }

        val file = File(filePath)
        if (file.exists()) {
            // 首先尝试删除文件
            val deleted = file.delete()
            if (deleted) {
                Log.d(TAG, "删除已存在的低音质文件: $filePath")
                return@withContext true
            } else {
                // 删除失败，尝试重命名文件
                val parentDir = file.parentFile
                val fileName = file.nameWithoutExtension
                val extension = file.extension
                val bakFileName = "${fileName}_old.${extension}.bak"
                val bakFile = File(parentDir, bakFileName)
                
                // 如果备份文件已存在，添加数字后缀
                var finalBakFile = bakFile
                var counter = 1
                while (finalBakFile.exists()) {
                    finalBakFile = File(parentDir, "${fileName}_old_${counter}.${extension}.bak")
                    counter++
                }
                
                val renamed = file.renameTo(finalBakFile)
                if (renamed) {
                    Log.d(TAG, "无法删除文件，已重命名为: ${finalBakFile.absolutePath}")
                    return@withContext true
                } else {
                    Log.e(TAG, "无法删除或重命名文件: $filePath")
                    return@withContext false
                }
            }
        }
        return@withContext false
    }

    /**
     * 从数据库中删除下载记录
     */
    suspend fun deleteDownloadRecord(context: Context, songId: String) = withContext(Dispatchers.IO) {
        try {
            val database = AppDatabase.getDatabase(context)
            val downloadTaskDao = database.downloadTaskDao()
            val task = downloadTaskDao.getCompletedTasksOnce().find { it.songId == songId }
            task?.let {
                downloadTaskDao.deleteTask(it)
                Log.d(TAG, "已删除数据库中的下载记录: $songId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除数据库记录失败: $songId, 错误: ${e.message}")
        }
    }

    /**
     * 批量检查下载任务的音质
     * @param tasks 待检查的任务列表
     * @return Pair<可以下载的任务列表, 需要提示的已存在高音质任务数量>
     */
    suspend fun batchCheckDownloadQuality(
        context: Context,
        tasks: List<DownloadTaskInfo>
    ): Pair<List<DownloadTaskInfo>, Int> = withContext(Dispatchers.IO) {
        val canDownloadList = mutableListOf<DownloadTaskInfo>()
        var skippedCount = 0

        for (task in tasks) {
            val (hasHigherOrEqualQuality, existingPath, source) = checkExistingDownloadQuality(
                context,
                task.songId,
                task.songName,
                task.artist,
                task.quality
            )

            if (hasHigherOrEqualQuality) {
                // 已存在更高或相同音质，跳过
                skippedCount++
                Log.d(TAG, "批量检查: 跳过 ${task.songName}, 已存在更高或相同音质 (来源: $source)")
            } else {
                // 可以下载，如果需要则删除旧文件
                if (existingPath != null) {
                    deleteExistingFile(existingPath)
                    // 同时删除数据库记录
                    deleteDownloadRecord(context, task.songId)
                }
                canDownloadList.add(task)
            }
        }

        return@withContext Pair(canDownloadList, skippedCount)
    }

    /**
     * 下载任务信息数据类
     */
    data class DownloadTaskInfo(
        val songId: String,
        val songName: String,
        val artist: String,
        val quality: String,
        val url: String,
        val platform: String
    )
}
