package com.tacke.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.model.Song
import com.tacke.music.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 歌曲封面加载器
 * 
 * 功能：
 * 1. 首次从推荐页榜单、推荐页推荐歌单外的其他歌单点击播放/批量添加播放歌曲时
 *    优先从源歌曲列表的本地缓存获取对应歌曲图片
 * 2. 如果源歌曲列表的本地缓存不存在该歌的图片，则根据该歌曲的来源调用对应的歌曲图片获取逻辑获取并缓存到本地
 * 3. 获取到本地后同时回写到缺失该歌曲图片的源歌曲列表
 */
object SongCoverLoader {

    private const val TAG = "SongCoverLoader"

    /**
     * 源歌曲列表类型
     */
    enum class SourceType {
        SEARCH_RESULT,      // 搜索结果
        PLAYLIST_DETAIL,    // 歌单详情
        LOCAL_MUSIC,        // 本地音乐
        RECENT_PLAY,        // 最近播放
        FAVORITE,           // 收藏
        DOWNLOAD_HISTORY,   // 下载历史
        UNKNOWN             // 未知
    }

    /**
     * 封面加载结果
     */
    data class CoverLoadResult(
        val bitmap: Bitmap?,
        val localPath: String?,     // 本地缓存路径
        val coverUrl: String?,      // 封面URL
        val sourceUpdated: Boolean  // 是否更新了源列表
    )

    /**
     * 加载歌曲封面
     * 
     * @param context 上下文
     * @param song 歌曲信息
     * @param sourceType 源列表类型
     * @param sourceList 源歌曲列表（用于回写封面）
     * @return 封面加载结果
     */
    suspend fun loadCover(
        context: Context,
        song: Song,
        sourceType: SourceType,
        sourceList: List<Song>? = null
    ): CoverLoadResult = withContext(Dispatchers.IO) {

        // 使用小写的平台名称（与CoverImageManager缓存键一致）
        val cachePlatform = song.platform.lowercase()

        // 1. 首先检查本地缓存（CoverImageManager缓存）
        val cachedPath = CoverImageManager.getCoverPath(context, song.id, cachePlatform)
        if (cachedPath != null) {
            Log.d(TAG, "从本地缓存获取封面: ${song.name}, path=$cachedPath")
            val bitmap = BitmapFactory.decodeFile(cachedPath)

            // 如果源列表中缺少封面，回写
            val sourceUpdated = if (!song.coverUrl.isNullOrEmpty()) {
                false
            } else {
                sourceList?.let { updateSourceListCover(it, song.id, cachedPath) } ?: false
            }

            return@withContext CoverLoadResult(
                bitmap = bitmap,
                localPath = cachedPath,
                coverUrl = "file://$cachedPath",
                sourceUpdated = sourceUpdated
            )
        }

        // 2. 如果源歌曲有coverUrl，尝试直接使用
        val songCoverUrl = song.coverUrl
        if (!songCoverUrl.isNullOrEmpty()) {
            Log.d(TAG, "使用源列表中的coverUrl: ${song.name}, url=$songCoverUrl")

            // 下载并缓存
            val localPath = when {
                songCoverUrl.startsWith("http") -> {
                    CoverImageManager.downloadAndCacheCoverByUrl(
                        context,
                        song.id,
                        cachePlatform,
                        songCoverUrl
                    )
                }
                songCoverUrl.startsWith("/") -> {
                    // 已经是本地路径
                    songCoverUrl
                }
                else -> null
            }

            if (localPath != null) {
                val bitmap = BitmapFactory.decodeFile(localPath)
                return@withContext CoverLoadResult(
                    bitmap = bitmap,
                    localPath = localPath,
                    coverUrl = "file://$localPath",
                    sourceUpdated = false
                )
            }
        }

        // 3. 根据歌曲来源获取封面
        Log.d(TAG, "从网络获取封面: ${song.name}, platform=${song.platform}")
        val localPath = CoverImageManager.downloadAndCacheCover(
            context,
            song.id,
            cachePlatform,
            "320k",
            song.name,
            song.artists
        )

        if (localPath != null) {
            val bitmap = BitmapFactory.decodeFile(localPath)
            
            // 回写到源列表
            val sourceUpdated = sourceList?.let { 
                updateSourceListCover(it, song.id, localPath)
            } ?: false
            
            // 同时更新数据库中的歌曲信息
            updateDatabaseSongCover(context, song.id, localPath)
            
            return@withContext CoverLoadResult(
                bitmap = bitmap,
                localPath = localPath,
                coverUrl = "file://$localPath",
                sourceUpdated = sourceUpdated
            )
        }

        // 4. 所有方式都失败
        Log.w(TAG, "无法获取封面: ${song.name}")
        return@withContext CoverLoadResult(
            bitmap = null,
            localPath = null,
            coverUrl = null,
            sourceUpdated = false
        )
    }

    /**
     * 批量加载歌曲封面
     * 
     * @param context 上下文
     * @param songs 歌曲列表
     * @param sourceType 源列表类型
     * @param onProgress 进度回调 (当前索引, 总数, 当前结果)
     */
    suspend fun loadCoversBatch(
        context: Context,
        songs: List<Song>,
        sourceType: SourceType,
        onProgress: ((Int, Int, CoverLoadResult) -> Unit)? = null
    ): Map<String, CoverLoadResult> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, CoverLoadResult>()
        
        songs.forEachIndexed { index, song ->
            val result = loadCover(context, song, sourceType, songs)
            results[song.id] = result
            onProgress?.invoke(index + 1, songs.size, result)
        }
        
        results
    }

    /**
     * 更新源歌曲列表中的封面
     * 
     * @param sourceList 源歌曲列表
     * @param songId 歌曲ID
     * @param coverPath 封面路径
     * @return 是否成功更新
     */
    private fun updateSourceListCover(
        sourceList: List<Song>,
        songId: String,
        coverPath: String
    ): Boolean {
        val song = sourceList.find { it.id == songId }
        return if (song != null && song.coverUrl.isNullOrEmpty()) {
            song.coverUrl = "file://$coverPath"
            Log.d(TAG, "回写封面到源列表: ${song.name}, path=$coverPath")
            true
        } else {
            false
        }
    }

    /**
     * 更新数据库中的歌曲封面
     * 
     * @param context 上下文
     * @param songId 歌曲ID
     * @param coverPath 封面路径
     */
    private suspend fun updateDatabaseSongCover(
        context: Context,
        songId: String,
        coverPath: String
    ) = withContext(Dispatchers.IO) {
        try {
            val database = AppDatabase.getDatabase(context)
            val songDetailDao = database.songDetailDao()
            
            // 获取现有详情
            val existingDetail = songDetailDao.getSongDetailOnce(songId)
            if (existingDetail != null) {
                // 更新封面URL
                val updatedDetail = existingDetail.copy(
                    coverUrl = "file://$coverPath",
                    updatedAt = System.currentTimeMillis()
                )
                songDetailDao.insertSongDetail(updatedDetail)
                Log.d(TAG, "更新数据库歌曲封面: $songId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新数据库歌曲封面失败: ${e.message}", e)
        }
    }

    /**
     * 预加载歌曲封面（用于批量添加时）
     * 只加载第一首歌的封面，其他歌曲在后台异步加载
     * 
     * @param context 上下文
     * @param songs 歌曲列表
     * @param sourceType 源列表类型
     * @return 第一首歌的封面结果
     */
    suspend fun preloadFirstSongCover(
        context: Context,
        songs: List<Song>,
        sourceType: SourceType
    ): CoverLoadResult? = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext null
        
        val firstSong = songs.first()
        Log.d(TAG, "预加载第一首歌封面: ${firstSong.name}")
        
        // 加载第一首歌的封面
        val result = loadCover(context, firstSong, sourceType, songs)
        
        // 注意：其他歌曲的封面在需要时单独加载，不在此处批量加载
        // 这样可以避免复杂的协程作用域问题
        
        result
    }
}
