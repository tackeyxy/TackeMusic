package com.tacke.music.playlist

import android.content.Context
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.Song
import com.tacke.music.data.preferences.PlaybackPreferences
import com.tacke.music.data.repository.MusicRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlaylistManager private constructor(context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val playlistSongDao = database.playlistSongDao()
    private val playbackPreferences = PlaybackPreferences.getInstance(context)

    // 当前播放列表
    private val _currentPlaylist = MutableStateFlow<List<PlaylistSong>>(emptyList())
    val currentPlaylist: StateFlow<List<PlaylistSong>> = _currentPlaylist.asStateFlow()

    // 当前播放索引
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // 播放模式: 0-顺序播放, 1-随机播放, 2-列表循环, 3-单曲循环
    private val _playMode = MutableStateFlow(0)
    val playMode: StateFlow<Int> = _playMode.asStateFlow()

    companion object {
        const val PLAY_MODE_SEQUENTIAL = 0  // 顺序播放
        const val PLAY_MODE_SHUFFLE = 1     // 随机播放
        const val PLAY_MODE_REPEAT_LIST = 2 // 列表循环
        const val PLAY_MODE_REPEAT_ONE = 3  // 单曲循环

        @Volatile
        private var instance: PlaylistManager? = null

        fun getInstance(context: Context): PlaylistManager {
            return instance ?: synchronized(this) {
                instance ?: PlaylistManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    init {
        // 从持久化存储恢复状态
        _playMode.value = playbackPreferences.playMode
        _currentIndex.value = playbackPreferences.currentIndex
    }

    // 加载播放列表
    suspend fun loadPlaylist() {
        val playlist = playlistSongDao.getAllSongsSync()
        _currentPlaylist.value = playlist
    }

    // 设置播放列表
    suspend fun setPlaylist(songs: List<PlaylistSong>, startIndex: Int = 0) {
        // 更新顺序索引
        val songsWithOrder = songs.mapIndexed { index, song ->
            song.copy(orderIndex = index)
        }

        // 清空并插入新列表
        playlistSongDao.deleteAllSongs()
        playlistSongDao.insertSongs(songsWithOrder)

        _currentPlaylist.value = songsWithOrder
        _currentIndex.value = startIndex.coerceIn(0, songsWithOrder.size.coerceAtLeast(1) - 1)

        // 保存到Preferences
        playbackPreferences.savePlaylist(songsWithOrder)
        playbackPreferences.currentIndex = _currentIndex.value
    }

    // 添加歌曲到播放列表
    // autoPlay: 是否在播放列表为空时自动设置当前索引为0（用于单曲播放场景）
    suspend fun addSong(song: PlaylistSong, autoPlay: Boolean = false) {
        val currentList = _currentPlaylist.value.toMutableList()
        val wasEmpty = currentList.isEmpty()
        val existingIndex = currentList.indexOfFirst { it.id == song.id }
        if (existingIndex == -1) {
            // 歌曲不存在，添加新歌曲
            val newSong = song.copy(orderIndex = currentList.size)
            currentList.add(newSong)
            playlistSongDao.insertSong(newSong)
            _currentPlaylist.value = currentList
            playbackPreferences.savePlaylist(currentList)

            // 仅在明确指定autoPlay且列表之前为空时，才设置当前索引
            // 批量添加时不应自动设置索引，避免非预期的播放行为
            if (autoPlay && wasEmpty) {
                _currentIndex.value = 0
                playbackPreferences.currentIndex = 0
            }
        } else {
            // 歌曲已存在，更新歌曲信息（如封面）
            val existingSong = currentList[existingIndex]
            // 如果新歌曲有封面而旧歌曲没有，则更新封面
            if (!song.coverUrl.isNullOrEmpty() && existingSong.coverUrl.isNullOrEmpty()) {
                val updatedSong = existingSong.copy(coverUrl = song.coverUrl)
                currentList[existingIndex] = updatedSong
                playlistSongDao.insertSong(updatedSong) // 使用 REPLACE 策略更新
                _currentPlaylist.value = currentList
                playbackPreferences.savePlaylist(currentList)
            }
        }
    }

    // 批量添加歌曲到播放列表（不触发自动播放）
    suspend fun addSongs(songs: List<PlaylistSong>): Pair<Int, Int> {
        val currentList = _currentPlaylist.value.toMutableList()
        var addedCount = 0
        var duplicateCount = 0

        songs.forEach { song ->
            val existingIndex = currentList.indexOfFirst { it.id == song.id }
            if (existingIndex == -1) {
                // 歌曲不存在，添加新歌曲
                val newSong = song.copy(orderIndex = currentList.size)
                currentList.add(newSong)
                addedCount++
            } else {
                // 歌曲已存在，更新歌曲信息（如封面）
                val existingSong = currentList[existingIndex]
                if (!song.coverUrl.isNullOrEmpty() && existingSong.coverUrl.isNullOrEmpty()) {
                    val updatedSong = existingSong.copy(coverUrl = song.coverUrl)
                    currentList[existingIndex] = updatedSong
                }
                duplicateCount++
            }
        }

        // 批量插入数据库
        if (addedCount > 0) {
            playlistSongDao.insertSongs(currentList.filter { song ->
                songs.any { it.id == song.id }
            })
            _currentPlaylist.value = currentList
            playbackPreferences.savePlaylist(currentList)
        }

        return Pair(addedCount, duplicateCount)
    }

    // 从播放列表移除歌曲
    suspend fun removeSong(songId: String) {
        val currentList = _currentPlaylist.value.toMutableList()
        val removedIndex = currentList.indexOfFirst { it.id == songId }

        if (removedIndex != -1) {
            val song = currentList[removedIndex]
            currentList.removeAt(removedIndex)

            // 更新剩余歌曲的顺序
            val updatedList = currentList.mapIndexed { index, s ->
                s.copy(orderIndex = index)
            }

            playlistSongDao.deleteSong(song)
            playlistSongDao.insertSongs(updatedList)

            _currentPlaylist.value = updatedList

            // 调整当前索引
            if (removedIndex < _currentIndex.value) {
                _currentIndex.value = (_currentIndex.value - 1).coerceAtLeast(0)
            } else if (removedIndex == _currentIndex.value && _currentIndex.value >= updatedList.size) {
                _currentIndex.value = (updatedList.size - 1).coerceAtLeast(0)
            }

            playbackPreferences.savePlaylist(updatedList)
            playbackPreferences.currentIndex = _currentIndex.value
        }
    }

    // 清空播放列表
    suspend fun clearPlaylist() {
        playlistSongDao.deleteAllSongs()
        _currentPlaylist.value = emptyList()
        _currentIndex.value = 0
        playbackPreferences.savePlaylist(emptyList())
        playbackPreferences.currentIndex = 0
    }

    // 获取当前歌曲
    fun getCurrentSong(): PlaylistSong? {
        val playlist = _currentPlaylist.value
        val index = _currentIndex.value
        return if (playlist.isNotEmpty() && index in playlist.indices) {
            playlist[index]
        } else null
    }

    // 设置当前索引
    fun setCurrentIndex(index: Int) {
        val playlist = _currentPlaylist.value
        if (playlist.isNotEmpty()) {
            _currentIndex.value = index.coerceIn(0, playlist.size - 1)
            playbackPreferences.currentIndex = _currentIndex.value
        }
    }

    // 获取播放列表大小
    fun getPlaylistSize(): Int {
        return _currentPlaylist.value.size
    }

    // 下一首
    fun next(): PlaylistSong? {
        val playlist = _currentPlaylist.value
        if (playlist.isEmpty()) return null

        return when (_playMode.value) {
            PLAY_MODE_REPEAT_ONE -> { // 单曲循环，返回当前歌曲
                getCurrentSong()
            }
            PLAY_MODE_SHUFFLE -> { // 随机播放
                if (playlist.size == 1) {
                    playlist[0]
                } else {
                    val randomIndex = (playlist.indices).random()
                    _currentIndex.value = randomIndex
                    playbackPreferences.currentIndex = randomIndex
                    playlist[randomIndex]
                }
            }
            PLAY_MODE_REPEAT_LIST -> { // 列表循环
                val nextIndex = if (_currentIndex.value + 1 < playlist.size) {
                    _currentIndex.value + 1
                } else {
                    0 // 循环到第一首
                }
                _currentIndex.value = nextIndex
                playbackPreferences.currentIndex = nextIndex
                playlist[nextIndex]
            }
            else -> { // 顺序播放
                val nextIndex = if (_currentIndex.value + 1 < playlist.size) {
                    _currentIndex.value + 1
                } else {
                    return null // 顺序播放不循环，返回null
                }
                _currentIndex.value = nextIndex
                playbackPreferences.currentIndex = nextIndex
                playlist[nextIndex]
            }
        }
    }

    // 上一首
    fun previous(): PlaylistSong? {
        val playlist = _currentPlaylist.value
        if (playlist.isEmpty()) return null

        return when (_playMode.value) {
            PLAY_MODE_REPEAT_ONE -> { // 单曲循环，返回当前歌曲
                getCurrentSong()
            }
            PLAY_MODE_SHUFFLE -> { // 随机播放
                if (playlist.size == 1) {
                    playlist[0]
                } else {
                    val randomIndex = (playlist.indices).random()
                    _currentIndex.value = randomIndex
                    playbackPreferences.currentIndex = randomIndex
                    playlist[randomIndex]
                }
            }
            PLAY_MODE_REPEAT_LIST -> { // 列表循环
                val prevIndex = if (_currentIndex.value - 1 >= 0) {
                    _currentIndex.value - 1
                } else {
                    playlist.size - 1 // 循环到最后一首
                }
                _currentIndex.value = prevIndex
                playbackPreferences.currentIndex = prevIndex
                playlist[prevIndex]
            }
            else -> { // 顺序播放
                val prevIndex = if (_currentIndex.value - 1 >= 0) {
                    _currentIndex.value - 1
                } else {
                    return null // 顺序播放不循环，返回null
                }
                _currentIndex.value = prevIndex
                playbackPreferences.currentIndex = prevIndex
                playlist[prevIndex]
            }
        }
    }

    // 设置播放模式
    fun setPlayMode(mode: Int) {
        _playMode.value = mode.coerceIn(0, 3)
        playbackPreferences.playMode = _playMode.value
    }

    // 切换播放模式
    fun togglePlayMode(): Int {
        val newMode = (_playMode.value + 1) % 4
        setPlayMode(newMode)
        return newMode
    }

    // 获取播放模式名称
    fun getPlayModeName(): String {
        return when (_playMode.value) {
            PLAY_MODE_SEQUENTIAL -> "顺序播放"
            PLAY_MODE_SHUFFLE -> "随机播放"
            PLAY_MODE_REPEAT_LIST -> "列表循环"
            PLAY_MODE_REPEAT_ONE -> "单曲循环"
            else -> "顺序播放"
        }
    }

    // 是否有下一首
    fun hasNext(): Boolean {
        val playlist = _currentPlaylist.value
        return when (_playMode.value) {
            PLAY_MODE_REPEAT_ONE -> true // 单曲循环总有下一首
            PLAY_MODE_SHUFFLE -> playlist.size > 1 // 随机播放需要至少2首
            PLAY_MODE_REPEAT_LIST -> playlist.isNotEmpty() // 列表循环只要有歌曲就可以
            else -> _currentIndex.value + 1 < playlist.size // 顺序播放需要检查是否到末尾
        }
    }

    // 是否有上一首
    fun hasPrevious(): Boolean {
        val playlist = _currentPlaylist.value
        return when (_playMode.value) {
            PLAY_MODE_REPEAT_ONE -> true // 单曲循环总有上一首
            PLAY_MODE_SHUFFLE -> playlist.size > 1 // 随机播放需要至少2首
            PLAY_MODE_REPEAT_LIST -> playlist.isNotEmpty() // 列表循环只要有歌曲就可以
            else -> _currentIndex.value - 1 >= 0 // 顺序播放需要检查是否到开头
        }
    }

    // 播放指定歌曲
    suspend fun playSong(songId: String): PlaylistSong? {
        val playlist = _currentPlaylist.value
        val index = playlist.indexOfFirst { it.id == songId }
        return if (index != -1) {
            _currentIndex.value = index
            playbackPreferences.currentIndex = index
            playlist[index]
        } else null
    }

    // 将Song转换为PlaylistSong
    fun convertToPlaylistSong(song: Song, platform: MusicRepository.Platform): PlaylistSong {
        return PlaylistSong(
            id = song.id,
            name = song.name,
            artists = song.artists,
            coverUrl = song.coverUrl,
            platform = platform.name
        )
    }

    // 获取播放列表Flow
    fun getPlaylistFlow(): Flow<List<PlaylistSong>> {
        return playlistSongDao.getAllSongs()
    }
}
