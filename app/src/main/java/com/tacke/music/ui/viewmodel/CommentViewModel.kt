package com.tacke.music.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tacke.music.data.repository.CommentRepository
import com.tacke.music.data.repository.MusicRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 评论ViewModel - 管理评论UI状态和交互
 */
class CommentViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CommentViewModel"
        private const val LOAD_MORE_DEBOUNCE_MS = 300L // 加载更多防抖时间
    }

    private val repository = CommentRepository.getInstance()

    // UI状态
    private val _uiState = MutableStateFlow<CommentUiState>(CommentUiState.Loading)
    val uiState: StateFlow<CommentUiState> = _uiState.asStateFlow()

    // 评论数据
    private val _comments = MutableStateFlow<List<com.tacke.music.data.model.Comment>>(emptyList())
    val comments: StateFlow<List<com.tacke.music.data.model.Comment>> = _comments.asStateFlow()

    // 评论总数
    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    // 是否有更多数据
    private val _hasMoreData = MutableStateFlow(true)
    val hasMoreData: StateFlow<Boolean> = _hasMoreData.asStateFlow()

    // 加载状态 - 暴露给UI显示加载更多指示器
    val loadingState: StateFlow<CommentRepository.CommentLoadingState> = repository.loadingState

    // 事件通道
    private val _events = Channel<CommentEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // 加载更多防抖
    private var loadMoreJob: Job? = null
    private var lastLoadMoreTime = 0L

    // 切换排序防抖
    private var switchSortJob: Job? = null
    private var lastSwitchTime = 0L
    private val SWITCH_DEBOUNCE_MS = 500L // 切换排序防抖时间

    // 当前状态
    private var currentSongId = ""
    private var currentSongName = ""
    private var currentArtist = ""
    private var currentPlatform: MusicRepository.Platform = MusicRepository.Platform.KUWO
    private var currentSortType: CommentRepository.SortType = CommentRepository.SortType.HOT
    private var isLoadingFirstPage = false

    sealed class CommentUiState {
        object Loading : CommentUiState()
        object Empty : CommentUiState()
        data class Content(val hasMore: Boolean) : CommentUiState()
        data class Error(val message: String) : CommentUiState()
    }

    sealed class CommentEvent {
        data class ShowToast(val message: String) : CommentEvent()
        object ScrollToTop : CommentEvent()
    }

    init {
        // 收集仓库的数据流
        viewModelScope.launch {
            repository.commentsFlow.collect { comments ->
                _comments.value = comments
                updateUiState()
            }
        }

        viewModelScope.launch {
            repository.loadingState.collect { state ->
                when (state) {
                    is CommentRepository.CommentLoadingState.LoadingFirstPage -> {
                        _uiState.value = CommentUiState.Loading
                    }
                    is CommentRepository.CommentLoadingState.Error -> {
                        if (_comments.value.isEmpty()) {
                            _uiState.value = CommentUiState.Error(state.message)
                        }
                    }
                    is CommentRepository.CommentLoadingState.Success -> {
                        _hasMoreData.value = state.hasMore
                        updateUiState()
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 初始化并加载第一页
     * @param forceReload 是否强制重新加载，用于横竖屏切换等场景
     */
    fun init(
        songId: String,
        songName: String,
        artist: String,
        platform: MusicRepository.Platform,
        sortType: CommentRepository.SortType = CommentRepository.SortType.HOT,
        forceReload: Boolean = false
    ) {
        Log.d(TAG, "初始化: songId=$songId, sortType=$sortType, forceReload=$forceReload, currentSongId=$currentSongId, currentSortType=$currentSortType, commentsSize=${_comments.value.size}")
        
        // 判断是否是同一首歌且排序类型相同
        val isSameSongAndSort = currentSongId == songId && currentSortType == sortType
        
        // 更新当前歌曲信息
        currentSongId = songId
        currentSongName = songName
        currentArtist = artist
        currentPlatform = platform
        currentSortType = sortType

        // 如果不是强制重载，且是同一首歌且排序方式相同且已有数据，直接更新UI状态即可
        if (!forceReload && isSameSongAndSort && _comments.value.isNotEmpty()) {
            Log.d(TAG, "使用已有数据，仅更新UI状态: ${_comments.value.size}条")
            updateUiState()
            return
        }

        // 如果正在加载，取消之前的任务
        if (isLoadingFirstPage) {
            switchSortJob?.cancel()
            loadMoreJob?.cancel()
        }

        // 清空数据并显示加载状态
        _comments.value = emptyList()
        _uiState.value = CommentUiState.Loading

        loadFirstPage()
    }

    /**
     * 切换排序方式（带防抖）
     */
    fun switchSortType(sortType: CommentRepository.SortType) {
        if (currentSortType == sortType) return

        // 防抖检查
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSwitchTime < SWITCH_DEBOUNCE_MS) {
            Log.d(TAG, "切换排序过于频繁，忽略本次请求")
            return
        }
        lastSwitchTime = currentTime

        // 取消之前的加载任务
        switchSortJob?.cancel()
        loadMoreJob?.cancel()

        currentSortType = sortType
        _comments.value = emptyList()
        _uiState.value = CommentUiState.Loading // 立即显示加载状态

        switchSortJob = viewModelScope.launch {
            // 稍微延迟，确保UI更新
            delay(50)
            loadFirstPage()
        }
    }

    /**
     * 加载第一页
     */
    private fun loadFirstPage() {
        // 防止重复加载
        if (isLoadingFirstPage) {
            Log.d(TAG, "正在加载第一页，跳过重复请求")
            return
        }

        viewModelScope.launch {
            isLoadingFirstPage = true
            _uiState.value = CommentUiState.Loading
            Log.d(TAG, "开始加载第一页: songId=$currentSongId")
            
            try {
                val result = repository.loadFirstPage(
                    songId = currentSongId,
                    songName = currentSongName,
                    artist = currentArtist,
                    platform = currentPlatform,
                    sortType = currentSortType
                )

                _totalCount.value = result.totalCount
                _hasMoreData.value = result.hasMore

                Log.d(TAG, "加载完成: ${result.comments.size}条评论, hasMore=${result.hasMore}")
                
                if (result.comments.isEmpty()) {
                    _uiState.value = CommentUiState.Empty
                } else {
                    _uiState.value = CommentUiState.Content(result.hasMore)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载评论失败", e)
                if (_comments.value.isEmpty()) {
                    _uiState.value = CommentUiState.Error(e.message ?: "加载失败")
                }
                _events.send(CommentEvent.ShowToast("加载失败: ${e.message}"))
            } finally {
                isLoadingFirstPage = false
            }
        }
    }

    /**
     * 加载更多（带防抖）
     */
    fun loadMoreComments() {
        // 检查是否可以加载
        if (_uiState.value !is CommentUiState.Content || !_hasMoreData.value) {
            return
        }

        // 防抖检查
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLoadMoreTime < LOAD_MORE_DEBOUNCE_MS) {
            return
        }
        lastLoadMoreTime = currentTime

        // 取消之前的加载任务
        loadMoreJob?.cancel()

        loadMoreJob = viewModelScope.launch {
            try {
                val result = repository.loadMoreComments()
                _hasMoreData.value = result.hasMore
                updateUiState()
            } catch (e: Exception) {
                Log.e(TAG, "加载更多失败", e)
                _events.send(CommentEvent.ShowToast("加载更多失败"))
            }
        }
    }

    /**
     * 重试加载
     */
    fun retry() {
        if (_comments.value.isEmpty()) {
            loadFirstPage()
        } else {
            loadMoreComments()
        }
    }

    /**
     * 刷新数据
     */
    fun refresh() {
        // 取消之前的加载任务
        switchSortJob?.cancel()
        loadMoreJob?.cancel()

        // 重置加载标志
        isLoadingFirstPage = false

        // 清除缓存（这会同时清空repository的数据流）
        repository.clearCacheForSong(currentSongId)

        // 清空ViewModel的数据并显示加载状态
        _comments.value = emptyList()
        _totalCount.value = 0
        _hasMoreData.value = true
        _uiState.value = CommentUiState.Loading

        // 立即加载，不需要延迟
        loadFirstPage()
    }

    /**
     * 更新UI状态
     */
    private fun updateUiState() {
        val comments = _comments.value
        _uiState.value = when {
            comments.isEmpty() && _uiState.value is CommentUiState.Loading -> CommentUiState.Loading
            comments.isEmpty() -> CommentUiState.Empty
            else -> CommentUiState.Content(_hasMoreData.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadMoreJob?.cancel()
    }
}
