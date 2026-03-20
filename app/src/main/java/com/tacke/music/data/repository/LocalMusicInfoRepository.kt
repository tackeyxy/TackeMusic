package com.tacke.music.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.tacke.music.data.api.*
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.db.LocalMusicInfoEntity
import com.tacke.music.ui.LocalMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地音乐信息仓库
 * 用于搜索匹配本地歌曲的在线信息（封面、歌词）并缓存
 */
class LocalMusicInfoRepository(private val context: Context) {

    private val musicSearchApi = RetrofitClient.musicSearchApi
    private val localMusicInfoDao = AppDatabase.getDatabase(context).localMusicInfoDao()

    companion object {
        private const val TAG = "LocalMusicInfoRepo"
        // 缓存有效期：30天
        private const val CACHE_VALIDITY_DAYS = 30
    }

    /**
     * 获取所有缓存的本地音乐
     */
    suspend fun getAllCachedMusic(): List<LocalMusic> = withContext(Dispatchers.IO) {
        val entities = localMusicInfoDao.getAllSync()
        entities.map { entity ->
            // 如果 path 是 content:// URI，则同时设置为 contentUri
            val isUri = entity.path.startsWith("content://")
            LocalMusic(
                id = entity.id,
                title = entity.title,
                artist = entity.artist,
                album = entity.album,
                duration = 0L, // 从数据库加载时不存储时长
                path = entity.path,
                coverUri = entity.coverUrl,
                contentUri = if (isUri) entity.path else null
            )
        }
    }

    /**
     * 获取本地音乐信息（带缓存）
     * 优先从缓存获取，如果没有缓存或缓存过期则从API获取
     *
     * @param localMusic 本地音乐信息
     * @param forceRefresh 是否强制刷新缓存
     * @return 包含封面和歌词的本地音乐信息
     */
    suspend fun getLocalMusicInfo(
        localMusic: LocalMusic,
        forceRefresh: Boolean = false
    ): LocalMusicInfoEntity? = withContext(Dispatchers.IO) {
        // 如果不是强制刷新，先检查缓存（通过文件路径）
        if (!forceRefresh) {
            val cachedInfo = localMusicInfoDao.getByPath(localMusic.path)
            val hasCompleteCache = cachedInfo != null &&
                !cachedInfo.coverUrl.isNullOrBlank() &&
                !cachedInfo.lyrics.isNullOrBlank()
            if (cachedInfo != null && isCacheValid(cachedInfo) && hasCompleteCache) {
                Log.d(TAG, "从缓存获取本地音乐信息: ${localMusic.title}")
                return@withContext cachedInfo
            }
        }

        // 从API搜索并获取信息
        try {
            val info = fetchMusicInfoFromApi(localMusic)
            if (info != null) {
                // 保存到缓存
                localMusicInfoDao.insert(info)
                Log.d(TAG, "已缓存本地音乐信息: ${localMusic.title}")
            }
            return@withContext info
        } catch (e: Exception) {
            Log.e(TAG, "获取本地音乐信息失败: ${localMusic.title}", e)
            // 如果API请求失败，返回过期的缓存（如果有）
            return@withContext localMusicInfoDao.getByPath(localMusic.path)
        }
    }

    /**
     * 批量获取本地音乐信息
     * 用于首次扫描后批量获取信息
     */
    suspend fun batchGetLocalMusicInfo(
        localMusicList: List<LocalMusic>,
        onProgress: ((Int, Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val total = localMusicList.size
        var completed = 0

        localMusicList.forEach { music ->
            try {
                // 检查是否已有有效缓存（通过文件路径）
                val cachedInfo = localMusicInfoDao.getByPath(music.path)
                val hasCompleteCache = cachedInfo != null &&
                    !cachedInfo.coverUrl.isNullOrEmpty() &&
                    !cachedInfo.lyrics.isNullOrEmpty()

                if (cachedInfo == null || !isCacheValid(cachedInfo) || !hasCompleteCache) {
                    val info = fetchMusicInfoFromApi(music)
                    if (info != null) {
                        localMusicInfoDao.insert(info)
                    }
                }
                completed++
                onProgress?.invoke(completed, total)
            } catch (e: Exception) {
                Log.e(TAG, "批量获取失败: ${music.title}", e)
                completed++
                onProgress?.invoke(completed, total)
            }
        }
    }

    /**
     * 从API获取音乐信息
     */
    private suspend fun fetchMusicInfoFromApi(localMusic: LocalMusic): LocalMusicInfoEntity? {
        val title = localMusic.title.trim()
        val artist = localMusic.artist.trim()
        val hasValidArtist = artist.isNotEmpty() &&
            !artist.equals("未知艺术家", ignoreCase = true) &&
            !artist.equals("未知艺人", ignoreCase = true)
        val keyword = if (hasValidArtist) "$title $artist" else title
        val musicRepository = MusicRepository()
        val cachedRepo = CachedMusicRepository(context)

        // 1. 按平台顺序搜索：酷我 -> 网易云；默认取各平台第一个结果
        val kuwoResults = try {
            musicRepository.searchMusic(MusicRepository.Platform.KUWO, keyword, 0)
        } catch (e: Exception) {
            Log.e(TAG, "酷我搜索失败: ${localMusic.title}", e)
            emptyList()
        }
        val neteaseResults = try {
            musicRepository.searchMusic(MusicRepository.Platform.NETEASE, keyword, 0)
        } catch (e: Exception) {
            Log.e(TAG, "网易云搜索失败: ${localMusic.title}", e)
            emptyList()
        }

        val firstKuwo = kuwoResults.firstOrNull()
        val firstNetease = neteaseResults.firstOrNull()

        var selected: Pair<MusicRepository.Platform, com.tacke.music.data.model.Song>? = null
        if (firstKuwo != null && isSongMatched(firstKuwo, title, artist)) {
            selected = MusicRepository.Platform.KUWO to firstKuwo
        } else if (firstNetease != null && isSongMatched(firstNetease, title, artist)) {
            selected = MusicRepository.Platform.NETEASE to firstNetease
        } else if (firstKuwo != null) {
            // 两个平台首条都不严格匹配时，按顺序保底使用酷我首条
            selected = MusicRepository.Platform.KUWO to firstKuwo
        } else if (firstNetease != null) {
            selected = MusicRepository.Platform.NETEASE to firstNetease
        }

        if (selected == null) {
            Log.w(TAG, "双平台搜索无结果: ${localMusic.title}")
            return null
        }

        val (selectedPlatform, selectedSong) = selected
        val source = if (selectedPlatform == MusicRepository.Platform.KUWO) "kuwo" else "netease"

        Log.d(
            TAG,
            "双平台最佳匹配: platform=${selectedPlatform.name}, title=${selectedSong.name}, artist=${selectedSong.artists}, id=${selectedSong.id}"
        )

        // 2. 使用原有在线播放链路获取图词（复用酷我/网易云原规则）
        val detail = try {
            cachedRepo.getSongDetail(
                platform = selectedPlatform,
                songId = selectedSong.id,
                quality = "320k",
                coverUrlFromSearch = selectedSong.coverUrl,
                songName = localMusic.title,
                artists = localMusic.artist
            )
        } catch (e: Exception) {
            Log.e(TAG, "双平台详情获取失败: ${localMusic.title}", e)
            null
        }

        val coverUrl = detail?.cover ?: selectedSong.coverUrl
        val lyrics = detail?.lyrics

        // 3. 写入本地缓存实体
        return LocalMusicInfoEntity(
            id = 0, // 自增ID
            title = localMusic.title,
            artist = localMusic.artist,
            album = localMusic.album,
            path = localMusic.path,
            // 双平台搜索结果中没有独立 pic/lyric id，使用 songId 作为追踪字段
            picId = selectedSong.id,
            lyricId = selectedSong.id,
            source = source,
            coverUrl = coverUrl,
            lyrics = lyrics,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun isSongMatched(song: com.tacke.music.data.model.Song, title: String, artist: String): Boolean {
        val normalizedTargetTitle = normalizeForMatch(title)
        val normalizedSongTitle = normalizeForMatch(song.name)
        if (normalizedTargetTitle.isEmpty() || normalizedSongTitle.isEmpty()) return false

        val titleMatched =
            normalizedTargetTitle == normalizedSongTitle ||
            normalizedSongTitle.contains(normalizedTargetTitle) ||
            normalizedTargetTitle.contains(normalizedSongTitle)
        if (!titleMatched) return false

        val normalizedTargetArtist = normalizeForMatch(artist)
        if (normalizedTargetArtist.isEmpty()) return true

        val normalizedSongArtist = normalizeForMatch(song.artists)
        if (normalizedSongArtist.isEmpty()) return false

        return normalizedSongArtist == normalizedTargetArtist ||
            normalizedSongArtist.contains(normalizedTargetArtist) ||
            normalizedTargetArtist.contains(normalizedSongArtist)
    }

    private fun normalizeForMatch(value: String?): String {
        return value.orEmpty()
            .lowercase()
            .replace(Regex("[\\s\\p{P}\\p{S}]"), "")
    }

    /**
     * 检查缓存是否有效
     */
    private fun isCacheValid(entity: LocalMusicInfoEntity): Boolean {
        val currentTime = System.currentTimeMillis()
        val cacheAge = currentTime - entity.updatedAt
        val maxAge = CACHE_VALIDITY_DAYS * 24 * 60 * 60 * 1000L // 转换为毫秒
        return cacheAge < maxAge
    }

    /**
     * 获取缓存的本地音乐信息（不触发网络请求）
     */
    suspend fun getCachedInfoByPath(path: String): LocalMusicInfoEntity? {
        return localMusicInfoDao.getByPath(path)
    }

    /**
     * 检查是否有缓存
     */
    suspend fun hasCache(path: String): Boolean {
        return localMusicInfoDao.getByPath(path) != null
    }

    /**
     * 删除指定音乐的缓存
     */
    suspend fun deleteCacheByPath(path: String) {
        localMusicInfoDao.deleteByPath(path)
    }

    /**
     * 根据路径删除本地音乐（用于批量操作）
     */
    suspend fun deleteByPath(path: String) {
        localMusicInfoDao.deleteByPath(path)
    }

    /**
     * 删除所有本地音乐记录
     */
    suspend fun deleteAll() {
        localMusicInfoDao.deleteAll()
    }

    /**
     * 清理过期缓存
     */
    suspend fun cleanExpiredCache() {
        val expiryTime = System.currentTimeMillis() - (CACHE_VALIDITY_DAYS * 24 * 60 * 60 * 1000L)
        localMusicInfoDao.deleteExpired(expiryTime)
    }

    /**
     * 获取缓存数量
     */
    suspend fun getCacheCount(): Int {
        return localMusicInfoDao.getCount()
    }

    /**
     * 扫描并保存本地音乐到数据库
     * 优先使用文件元数据，无元数据时解析文件名"歌名-歌手"格式
     *
     * @param musicFiles 扫描到的本地音乐文件列表
     * @param onProgress 进度回调 (已完成数量, 总数)
     * @return 保存到数据库的音乐列表
     */
    suspend fun scanAndSaveMusic(
        musicFiles: List<LocalMusic>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<LocalMusic> = withContext(Dispatchers.IO) {
        val total = musicFiles.size
        var completed = 0
        val savedMusicList = mutableListOf<LocalMusic>()

        musicFiles.forEach { music ->
            try {
                // 检查文件是否可访问
                // 对于 SAF URI (content://)，不需要检查文件是否存在
                // 对于文件路径，检查文件是否存在
                if (!music.path.startsWith("content://")) {
                    val file = File(music.path)
                    if (!file.exists()) {
                        Log.w(TAG, "跳过已删除的文件: ${music.path}")
                        completed++
                        onProgress?.invoke(completed, total)
                        return@forEach
                    }
                }

                // 从文件元数据获取信息（优先）
                // 优先使用 contentUri（MediaStore URI），这在 Android 10+ 上更可靠
                // 对于 SAF 选择的文件，使用 path 作为 URI
                val uriToUse = if (music.path.startsWith("content://")) music.path else music.contentUri
                val metadata = extractMetadataFromFile(music.path, uriToUse)

                val title: String
                val artist: String
                val album: String

                if (metadata != null && metadata.title.isNotBlank()) {
                    // 使用文件元数据
                    title = metadata.title
                    artist = metadata.artist.takeIf { it.isNotBlank() } ?: "未知艺人"
                    album = metadata.album.takeIf { it.isNotBlank() } ?: "未知专辑"
                    Log.d(TAG, "使用元数据: $title - $artist")
                } else {
                    // 无元数据，解析文件名
                    val parsed = parseFileName(music.title)
                    title = parsed.first
                    artist = parsed.second
                    album = "未知专辑"
                    Log.d(TAG, "解析文件名: $title - $artist (原始: ${music.title})")
                }

                // 扫描阶段只落库本地元数据，不做在线封面/歌词请求
                val coverUrl: String? = null
                val lyrics: String? = null
                val picId: String? = null
                val lyricId: String? = null
                val source: String? = null

                // 检查是否已存在相同路径的歌曲
                val existingEntity = localMusicInfoDao.getByPath(music.path)

                // 创建实体并保存到数据库
                val entity = LocalMusicInfoEntity(
                    id = existingEntity?.id ?: 0, // 如果已存在则使用原有ID，否则为0（自增）
                    title = title,
                    artist = artist,
                    album = album,
                    path = music.path,
                    picId = picId,
                    lyricId = lyricId,
                    source = source,
                    coverUrl = coverUrl,
                    lyrics = lyrics,
                    updatedAt = System.currentTimeMillis()
                )

                val insertedId = localMusicInfoDao.insertAndReturnId(entity)

                savedMusicList.add(LocalMusic(
                    id = insertedId,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = music.duration,
                    path = music.path,
                    coverUri = coverUrl
                ))

                completed++
                onProgress?.invoke(completed, total)

            } catch (e: Exception) {
                Log.e(TAG, "处理音乐文件失败: ${music.path}", e)
                completed++
                onProgress?.invoke(completed, total)
            }
        }

        savedMusicList
    }

    /**
     * 从文件提取元数据
     * 支持通过文件路径或URI访问文件
     */
    private fun extractMetadataFromFile(path: String, contentUri: String? = null): MetadataInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            // 优先尝试使用 contentUri（MediaStore URI 或 SAF URI），这在 Android 10+ 上更可靠
            if (!contentUri.isNullOrEmpty()) {
                try {
                    val uri = Uri.parse(contentUri)
                    retriever.setDataSource(context, uri)
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                    val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                    Log.d(TAG, "使用 URI 提取元数据成功: $contentUri")
                    return MetadataInfo(title, artist, album)
                } catch (e: Exception) {
                    Log.w(TAG, "使用 URI 提取元数据失败，尝试使用文件路径: $contentUri", e)
                }
            }

            // 如果 path 是 content:// URI，也尝试使用它
            if (path.startsWith("content://")) {
                try {
                    val uri = Uri.parse(path)
                    retriever.setDataSource(context, uri)
                    val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                    val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                    val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                    Log.d(TAG, "使用 path URI 提取元数据成功: $path")
                    return MetadataInfo(title, artist, album)
                } catch (e: Exception) {
                    Log.w(TAG, "使用 path URI 提取元数据失败: $path", e)
                }
            }

            // 尝试使用文件路径
            val file = File(path)
            if (file.exists() && file.canRead()) {
                retriever.setDataSource(path)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                return MetadataInfo(title, artist, album)
            } else {
                Log.w(TAG, "文件不存在或无法读取: $path")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取元数据失败: $path", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /**
     * 解析文件名，格式为 "歌名-歌手" 或 "歌名 - 歌手"
     * 也支持 "歌手-歌名" 格式，通过在线搜索确定正确顺序
     */
    private suspend fun parseFileName(fileName: String): Pair<String, String> {
        // 移除扩展名
        val nameWithoutExt = fileName.substringBeforeLast(".")

        // 尝试多种分隔符
        val separators = listOf(" - ", "-", " _ ", "_", " — ", "—")

        for (separator in separators) {
            if (nameWithoutExt.contains(separator)) {
                val parts = nameWithoutExt.split(separator, limit = 2)
                if (parts.size == 2) {
                    val part1 = parts[0].trim()
                    val part2 = parts[1].trim()
                    if (part1.isNotBlank() && part2.isNotBlank()) {
                        // 尝试两种顺序，通过API搜索判断哪种更可能正确
                        return determineCorrectOrder(part1, part2)
                    }
                }
            }
        }

        // 无法解析，返回原文件名作为歌名
        return nameWithoutExt.trim() to "未知艺人"
    }

    /**
     * 通过API搜索判断歌名和歌手的正确顺序
     * 尝试两种顺序，返回有搜索结果的那个
     */
    private suspend fun determineCorrectOrder(part1: String, part2: String): Pair<String, String> {
        return try {
            // 尝试顺序1：part1作为歌名，part2作为歌手
            val search1 = try {
                musicSearchApi.searchSongs(name = part1, pages = 1)
            } catch (e: Exception) {
                Log.e(TAG, "搜索失败: $part1", e)
                emptyList()
            }
            val match1 = search1.find { 
                it.name.equals(part1, ignoreCase = true) && 
                it.artist.equals(part2, ignoreCase = true) 
            }

            // 尝试顺序2：part2作为歌名，part1作为歌手
            val search2 = try {
                musicSearchApi.searchSongs(name = part2, pages = 1)
            } catch (e: Exception) {
                Log.e(TAG, "搜索失败: $part2", e)
                emptyList()
            }
            val match2 = search2.find { 
                it.name.equals(part2, ignoreCase = true) && 
                it.artist.equals(part1, ignoreCase = true) 
            }

            when {
                match1 != null && match2 == null -> {
                    // 顺序1正确：part1是歌名，part2是歌手
                    Log.d(TAG, "解析顺序：$part1 - $part2 (歌名-歌手)")
                    part1 to part2
                }
                match1 == null && match2 != null -> {
                    // 顺序2正确：part2是歌名，part1是歌手
                    Log.d(TAG, "解析顺序：$part2 - $part1 (歌名-歌手)")
                    part2 to part1
                }
                else -> {
                    // 两种顺序都有结果或都没有结果，默认使用 歌名-歌手 顺序
                    // 通常文件名前半部分是歌名的情况更常见
                    Log.d(TAG, "默认解析顺序：$part1 - $part2 (歌名-歌手)")
                    part1 to part2
                }
            }
        } catch (e: Exception) {
            // API调用失败，默认使用 歌名-歌手 顺序
            Log.d(TAG, "API搜索失败，默认解析：$part1 - $part2")
            part1 to part2
        }
    }

    /**
     * 元数据信息数据类
     */
    private data class MetadataInfo(
        val title: String,
        val artist: String,
        val album: String
    )
}
