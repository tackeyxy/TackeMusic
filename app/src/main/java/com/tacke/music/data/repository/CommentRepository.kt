package com.tacke.music.data.repository

import android.util.Log
import com.tacke.music.data.api.CommentSource
import com.tacke.music.data.api.KuwoApi
import com.tacke.music.data.api.MusicSearchApi
import com.tacke.music.data.api.NeteaseApi
import com.tacke.music.data.api.RetrofitClient
import com.tacke.music.data.model.Comment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 评论仓库 - 管理评论数据的获取、缓存和状态
 */
class CommentRepository private constructor() {

    companion object {
        private const val TAG = "CommentRepository"
        private const val CACHE_EXPIRY_MS = 5 * 60 * 1000L // 缓存5分钟
        private const val PAGE_SIZE = 20
        private const val PRELOAD_THRESHOLD = 5 // 距离底部5个item时预加载

        @Volatile
        private var instance: CommentRepository? = null

        fun getInstance(): CommentRepository {
            return instance ?: synchronized(this) {
                instance ?: CommentRepository().also { instance = it }
            }
        }
    }

    // 内存缓存 - 按歌曲ID+平台+排序方式存储
    private val commentCache = ConcurrentHashMap<String, CommentCacheEntry>()
    private val cacheMutex = Mutex()

    // 加载状态管理
    private val _loadingState = MutableStateFlow<CommentLoadingState>(CommentLoadingState.Idle)
    val loadingState: StateFlow<CommentLoadingState> = _loadingState.asStateFlow()

    // 评论数据流
    private val _commentsFlow = MutableStateFlow<List<Comment>>(emptyList())
    val commentsFlow: StateFlow<List<Comment>> = _commentsFlow.asStateFlow()

    // 分页状态
    private var currentPage = 1
    private var hasMoreData = true
    private var isLoading = false
    private var currentSongId = ""
    private var currentPlatform: CommentSource? = null
    private var currentSortType: SortType = SortType.HOT

    // 本地歌曲实际使用的评论源
    private var actualCommentSource: CommentSource? = null
    private var actualSongId: String = ""

    // 本地歌曲评论来源映射缓存 - 避免每次搜索返回不同结果
    // key: 本地歌曲ID (local_xxx), value: Pair<评论源, 平台歌曲ID>
    private val localSongMapping = ConcurrentHashMap<String, Pair<CommentSource, String>>()

    enum class SortType { HOT, LATEST }

    sealed class CommentLoadingState {
        object Idle : CommentLoadingState()
        object LoadingFirstPage : CommentLoadingState()
        object LoadingMore : CommentLoadingState()
        data class Error(val message: String) : CommentLoadingState()
        data class Success(val hasMore: Boolean) : CommentLoadingState()
    }

    data class CommentCacheEntry(
        val comments: List<Comment>,
        val totalCount: Int,
        val lastPage: Int,
        val hasMore: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS
    }

    data class CommentResult(
        val comments: List<Comment>,
        val totalCount: Int,
        val hasMore: Boolean
    )

    /**
     * 获取缓存key
     */
    private fun getCacheKey(songId: String, platform: CommentSource, sortType: SortType): String {
        return "${songId}_${platform.name}_${sortType.name}"
    }

    /**
     * 加载第一页评论（带缓存）
     */
    suspend fun loadFirstPage(
        songId: String,
        songName: String,
        artist: String,
        platform: MusicRepository.Platform,
        sortType: SortType
    ): CommentResult = withContext(Dispatchers.IO) {
        currentSongId = songId
        currentSortType = sortType
        currentPage = 1
        hasMoreData = true

        // 确定评论源
        val commentSource = determineCommentSource(songId, platform)

        // 检查缓存
        val cacheKey = getCacheKey(songId, commentSource, sortType)
        val cachedEntry = commentCache[cacheKey]

        if (cachedEntry != null && !cachedEntry.isExpired()) {
            Log.d(TAG, "使用缓存数据: $cacheKey, 共${cachedEntry.comments.size}条")
            _commentsFlow.value = cachedEntry.comments
            currentPage = cachedEntry.lastPage
            hasMoreData = cachedEntry.hasMore
            currentPlatform = commentSource
            _loadingState.value = CommentLoadingState.Success(cachedEntry.hasMore)
            return@withContext CommentResult(
                comments = cachedEntry.comments,
                totalCount = cachedEntry.totalCount,
                hasMore = cachedEntry.hasMore
            )
        }

        // 没有缓存或缓存过期，从网络加载
        _loadingState.value = CommentLoadingState.LoadingFirstPage

        try {
            val result = when {
                songId.startsWith("local_") -> loadLocalSongComments(songId, songName, artist, sortType)
                platform == MusicRepository.Platform.KUWO -> loadKuwoComments(songId, sortType, 1)
                platform == MusicRepository.Platform.NETEASE -> loadNeteaseComments(songId, sortType, 1)
                else -> CommentResult(emptyList(), 0, false)
            }

            // 更新缓存和数据流（即使是空列表也要更新）
            cacheMutex.withLock {
                commentCache[cacheKey] = CommentCacheEntry(
                    comments = result.comments,
                    totalCount = result.totalCount,
                    lastPage = 1,
                    hasMore = result.hasMore
                )
            }
            _commentsFlow.value = result.comments

            currentPlatform = commentSource
            hasMoreData = result.hasMore
            _loadingState.value = CommentLoadingState.Success(result.hasMore)

            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "加载评论失败", e)
            _loadingState.value = CommentLoadingState.Error(e.message ?: "加载失败")
            throw e
        }
    }

    /**
     * 确定评论源
     * - 本地歌曲：检查是否有缓存的映射，有直接返回，没有则返回默认源（酷我）
     * - 明确来源的歌曲：直接使用对应平台
     */
    private fun determineCommentSource(songId: String, platform: MusicRepository.Platform): CommentSource {
        return when {
            songId.startsWith("local_") -> {
                // 本地歌曲，检查是否有缓存的映射
                localSongMapping[songId]?.first ?: CommentSource.KUWO
            }
            platform == MusicRepository.Platform.KUWO -> CommentSource.KUWO
            platform == MusicRepository.Platform.NETEASE -> CommentSource.NETEASE
            else -> CommentSource.KUWO
        }
    }

    /**
     * 加载更多评论
     */
    suspend fun loadMoreComments(): CommentResult = withContext(Dispatchers.IO) {
        if (isLoading || !hasMoreData) {
            return@withContext CommentResult(_commentsFlow.value, 0, hasMoreData)
        }

        isLoading = true
        _loadingState.value = CommentLoadingState.LoadingMore

        try {
            currentPage++
            val nextPage = currentPage

            val result = when {
                actualCommentSource != null -> {
                    when (actualCommentSource) {
                        CommentSource.KUWO -> loadKuwoComments(actualSongId, currentSortType, nextPage)
                        CommentSource.NETEASE -> loadNeteaseComments(actualSongId, currentSortType, nextPage)
                        else -> CommentResult(emptyList(), 0, false)
                    }
                }
                currentPlatform == CommentSource.KUWO -> loadKuwoComments(currentSongId, currentSortType, nextPage)
                currentPlatform == CommentSource.NETEASE -> loadNeteaseComments(currentSongId, currentSortType, nextPage)
                else -> CommentResult(emptyList(), 0, false)
            }

            if (result.comments.isEmpty()) {
                hasMoreData = false
                currentPage--
            } else {
                // 合并数据
                val currentList = _commentsFlow.value.toMutableList()
                currentList.addAll(result.comments)
                _commentsFlow.value = currentList

                // 更新缓存
                val cacheKey = getCacheKey(currentSongId, currentPlatform ?: CommentSource.KUWO, currentSortType)
                cacheMutex.withLock {
                    commentCache[cacheKey] = CommentCacheEntry(
                        comments = currentList,
                        totalCount = result.totalCount,
                        lastPage = currentPage,
                        hasMore = result.hasMore
                    )
                }
            }

            hasMoreData = result.hasMore
            _loadingState.value = CommentLoadingState.Success(result.hasMore)

            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "加载更多评论失败", e)
            currentPage--
            _loadingState.value = CommentLoadingState.Error(e.message ?: "加载失败")
            throw e
        } finally {
            isLoading = false
        }
    }

    /**
     * 加载本地歌曲评论
     */
    private suspend fun loadLocalSongComments(
        songId: String,
        songName: String,
        artist: String,
        sortType: SortType
    ): CommentResult = withContext(Dispatchers.IO) {
        val searchKeyword = "$songName $artist"
        Log.d(TAG, "本地歌曲搜索: $searchKeyword")

        try {
            // 检查是否已有映射缓存
            val cachedMapping = localSongMapping[songId]
            if (cachedMapping != null) {
                val (source, mappedSongId) = cachedMapping
                Log.d(TAG, "使用缓存的本地歌曲映射: $songId -> $source:$mappedSongId")
                actualCommentSource = source
                actualSongId = mappedSongId
                val comments = when (source) {
                    CommentSource.KUWO -> loadKuwoComments(mappedSongId, sortType, 1)
                    CommentSource.NETEASE -> loadNeteaseComments(mappedSongId, sortType, 1)
                }
                return@withContext CommentResult(comments.comments, comments.totalCount, comments.hasMore)
            }

            // 并行搜索两个平台
            val kuwoDeferred = async { searchKuwoSong(searchKeyword) }
            val neteaseDeferred = async { searchNeteaseSong(searchKeyword) }

            val kuwoSongId = kuwoDeferred.await()
            val neteaseSongId = neteaseDeferred.await()

            if (kuwoSongId == null && neteaseSongId == null) {
                return@withContext CommentResult(emptyList(), 0, false)
            }

            // 获取评论数量（只获取第一页来统计）
            val kuwoTotalDeferred = async { kuwoSongId?.let { getKuwoCommentTotal(it) } ?: 0 }
            val neteaseTotalDeferred = async { neteaseSongId?.let { getNeteaseCommentTotal(it) } ?: 0 }

            val kuwoTotal = kuwoTotalDeferred.await()
            val neteaseTotal = neteaseTotalDeferred.await()

            // 选择评论较多的源
            val (selectedSource, selectedSongId, total) = when {
                kuwoSongId != null && neteaseSongId == null ->
                    Triple(CommentSource.KUWO, kuwoSongId, kuwoTotal)
                kuwoSongId == null && neteaseSongId != null ->
                    Triple(CommentSource.NETEASE, neteaseSongId, neteaseTotal)
                kuwoTotal >= neteaseTotal ->
                    Triple(CommentSource.KUWO, kuwoSongId!!, kuwoTotal)
                else ->
                    Triple(CommentSource.NETEASE, neteaseSongId!!, neteaseTotal)
            }

            // 缓存映射关系
            localSongMapping[songId] = Pair(selectedSource, selectedSongId)
            actualCommentSource = selectedSource
            actualSongId = selectedSongId

            Log.d(TAG, "缓存本地歌曲映射: $songId -> $selectedSource:$selectedSongId")

            // 加载第一页评论
            val comments = when (selectedSource) {
                CommentSource.KUWO -> loadKuwoComments(selectedSongId, sortType, 1)
                CommentSource.NETEASE -> loadNeteaseComments(selectedSongId, sortType, 1)
            }

            return@withContext CommentResult(comments.comments, total, comments.hasMore)
        } catch (e: Exception) {
            Log.e(TAG, "加载本地歌曲评论失败", e)
            throw e
        }
    }

    /**
     * 加载酷我评论
     */
    private suspend fun loadKuwoComments(
        songId: String,
        sortType: SortType,
        page: Int
    ): CommentResult = withContext(Dispatchers.IO) {
        val type = if (sortType == SortType.HOT) "get_rec_comment" else "get_comment"

        try {
            val response = RetrofitClient.kuwoCommentApi.getComments(
                type = type,
                page = page,
                rows = PAGE_SIZE,
                songId = songId
            )

            val total = response.total ?: 0
            val comments = response.rows?.mapNotNull { item ->
                if (item.content.isNullOrBlank()) null
                else Comment(
                    id = item.id ?: "${songId}_${page}_${System.currentTimeMillis()}",
                    content = item.content,
                    userName = item.userName ?: "未知用户",
                    userAvatar = item.userAvatar,
                    time = item.time,
                    likeCount = item.likeCount?.toIntOrNull() ?: 0
                )
            } ?: emptyList()

            val hasMore = comments.size >= PAGE_SIZE && (page * PAGE_SIZE) < total

            CommentResult(comments, total, hasMore)
        } catch (e: Exception) {
            Log.e(TAG, "加载酷我评论失败", e)
            throw e
        }
    }

    /**
     * 加载网易云评论
     */
    private suspend fun loadNeteaseComments(
        songId: String,
        sortType: SortType,
        page: Int
    ): CommentResult = withContext(Dispatchers.IO) {
        try {
            val offset = (page - 1) * PAGE_SIZE
            val response = if (sortType == SortType.HOT) {
                RetrofitClient.neteaseCommentApi.getHotComments(
                    songId = songId,
                    limit = PAGE_SIZE,
                    offset = offset
                )
            } else {
                RetrofitClient.neteaseCommentApi.getLatestComments(
                    songId = songId,
                    limit = PAGE_SIZE,
                    offset = offset
                )
            }

            val total = response.total ?: 0
            val commentList = if (sortType == SortType.HOT) response.hotComments else response.comments

            val comments = commentList?.map { item ->
                Comment(
                    id = item.commentId?.toString() ?: "${songId}_${page}_${System.currentTimeMillis()}",
                    content = item.content,
                    userName = item.user?.nickname ?: "未知用户",
                    userAvatar = item.user?.avatarUrl,
                    time = item.time,
                    likeCount = item.likedCount ?: 0
                )
            } ?: emptyList()

            val hasMore = comments.size >= PAGE_SIZE && (page * PAGE_SIZE) < total

            CommentResult(comments, total, hasMore)
        } catch (e: Exception) {
            Log.e(TAG, "加载网易云评论失败", e)
            throw e
        }
    }

    /**
     * 搜索酷我歌曲
     */
    private suspend fun searchKuwoSong(keyword: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.kuwoApi.searchMusic(keywords = keyword)
            val jsonString = response.string()
            val jsonObject = com.google.gson.JsonParser.parseString(jsonString).asJsonObject
            val abslist = jsonObject.getAsJsonArray("abslist")

            abslist?.firstOrNull()?.asJsonObject?.get("MUSICRID")?.asString?.replace("MUSIC_", "")
        } catch (e: Exception) {
            Log.e(TAG, "搜索酷我歌曲失败", e)
            null
        }
    }

    /**
     * 搜索网易云歌曲
     */
    private suspend fun searchNeteaseSong(keyword: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.musicSearchApi.searchSongs(name = keyword)
            response.firstOrNull { it.source == "netease" || it.source == "wy" }?.id
        } catch (e: Exception) {
            Log.e(TAG, "搜索网易云歌曲失败", e)
            null
        }
    }

    /**
     * 获取酷我评论总数
     */
    private suspend fun getKuwoCommentTotal(songId: String): Int = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.kuwoCommentApi.getComments(
                type = "get_comment",
                page = 1,
                rows = 1,
                songId = songId
            )
            response.total ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 获取网易云评论总数
     */
    private suspend fun getNeteaseCommentTotal(songId: String): Int = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.neteaseCommentApi.getLatestComments(
                songId = songId,
                limit = 1,
                offset = 0
            )
            response.total ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 获取评论源
     */
    private fun getCommentSource(platform: MusicRepository.Platform, songId: String): CommentSource {
        return when (platform) {
            MusicRepository.Platform.KUWO -> CommentSource.KUWO
            MusicRepository.Platform.NETEASE -> CommentSource.NETEASE
            else -> CommentSource.KUWO
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        commentCache.clear()
        currentPage = 1
        hasMoreData = true
        isLoading = false
        _commentsFlow.value = emptyList()
        _loadingState.value = CommentLoadingState.Idle
    }

    /**
     * 清除指定歌曲的缓存并清空数据流
     */
    fun clearCacheForSong(songId: String) {
        commentCache.keys.filter { it.startsWith(songId) }.forEach {
            commentCache.remove(it)
        }
        // 清空数据流，确保UI状态同步
        _commentsFlow.value = emptyList()
    }

    /**
     * 重置状态
     */
    fun reset() {
        currentPage = 1
        hasMoreData = true
        isLoading = false
        actualCommentSource = null
        actualSongId = ""
        _loadingState.value = CommentLoadingState.Idle
    }
}
