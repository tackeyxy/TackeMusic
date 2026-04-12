package com.tacke.music.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.RecentPlay
import com.tacke.music.data.model.Song
import com.tacke.music.data.model.SongDetail
import com.tacke.music.data.model.SongInfo
import com.tacke.music.data.preferences.PlaybackPreferences
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.data.repository.LocalMusicInfoRepository
import com.tacke.music.data.repository.MusicRepository
import com.tacke.music.data.repository.SongPreloadManager
import com.tacke.music.playlist.PlaylistManager
import com.tacke.music.ui.PlayerActivity
import com.tacke.music.ui.SettingsActivity
import com.tacke.music.utils.CoverImageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一播放管理器
 * 负责统一处理所有播放入口的逻辑，确保播放进度存储的一致性
 * 
 * 优化后的播放流程：
 * 1. 优先使用本地缓存的封面和歌词
 * 2. 快速获取URL后立即进入播放页
 * 3. 歌词和图片在后台加载完成后刷新显示
 * 4. 添加到播放列表的歌曲在后台自动预加载
 */
class PlaybackManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val playlistManager = PlaylistManager.getInstance(appContext)
    private val playbackPreferences = PlaybackPreferences.getInstance(appContext)
    private val preloadManager = SongPreloadManager.getInstance(appContext)
    private val cachedMusicRepository = CachedMusicRepository(appContext)

    companion object {
        private const val TAG = "PlaybackManager"

        @Volatile
        private var instance: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return instance ?: synchronized(this) {
                instance ?: PlaybackManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        // 支持的平台列表
        val SUPPORTED_PLATFORMS = listOf("KUWO", "NETEASE")
    }

    private val repository = MusicRepository()

    private fun isLocalPlaylistSong(song: PlaylistSong): Boolean {
        return song.platform.equals("LOCAL", ignoreCase = true) || song.id.startsWith("local_")
    }

    /**
     * 播放歌曲（从搜索列表）- 优化版本
     * 快速启动播放页面，后台加载歌词和图片
     * 
     * @param context 上下文
     * @param song 搜索结果的歌曲
     * @param platform 音乐平台
     * @param songDetail 歌曲详情（包含URL、封面、歌词等）
     */
    suspend fun playFromSearch(
        context: Context,
        song: Song,
        platform: MusicRepository.Platform,
        songDetail: SongDetail
    ) {
        // 创建播放列表歌曲
        val playlistSong = PlaylistSong(
            id = song.id,
            name = song.name,
            artists = song.artists,
            coverUrl = songDetail.cover ?: song.coverUrl,
            platform = platform.name
        )

        // 添加到播放列表并设置为当前播放（不清空原有列表）
        playlistManager.addSong(playlistSong)
        playlistManager.setCurrentIndex(playlistManager.getPlaylistSize() - 1)

        // 关键修复：彻底清除之前的播放状态，确保新歌曲从头开始播放
        clearPlaybackState()

        // 确保播放位置为0，新歌曲从头开始
        playbackPreferences.currentPosition = 0L
        playbackPreferences.isPlaying = false

        // 获取用户设置的试听音质
        val playbackQuality = SettingsActivity.getPlaybackQuality(appContext)

        // 保存播放状态（新歌曲从头开始播放，位置为0）
        savePlaybackState(
            songId = song.id,
            position = 0L,
            isPlaying = true,
            quality = playbackQuality,
            songDetail = songDetail
        )

        // 再次确保位置为0
        playbackPreferences.currentPosition = 0L

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = song.id,
            songName = song.name,
            songArtists = song.artists,
            platform = platform,
            songUrl = songDetail.url,
            songCover = songDetail.cover ?: song.coverUrl,
            songLyrics = songDetail.lyrics
        )
    }

    /**
     * 快速播放歌曲（从搜索列表）- 极速版本
     * 优先使用本地缓存，快速进入播放页，后台加载完整信息
     * 
     * @param context 上下文
     * @param song 搜索结果的歌曲
     * @param platform 音乐平台
     */
    suspend fun playFromSearchFast(
        context: Context,
        song: Song,
        platform: MusicRepository.Platform
    ) {
        Log.d(TAG, "快速播放歌曲: ${song.name}")

        // 获取用户设置的试听音质
        val playbackQuality = SettingsActivity.getPlaybackQuality(appContext)

        // 1. 使用getSongUrlWithCache获取完整的歌曲详情（包括URL、封面、歌词）
        // 该方法会优先使用本地缓存，如果没有缓存则根据平台使用不同的封面获取逻辑
        val cachedRepository = CachedMusicRepository(context)
        val detail = withContext(Dispatchers.IO) {
            cachedRepository.getSongUrlWithCache(
                platform = platform,
                songId = song.id,
                quality = playbackQuality,
                songName = song.name,
                artists = song.artists,
                useCache = true,
                coverUrlFromSearch = song.coverUrl
            )
        }

        if (detail == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "获取歌曲链接失败", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // 2. 使用获取到的封面和歌词
        val mergedCover = detail.cover ?: song.coverUrl
        val mergedLyrics = detail.lyrics

        // 3. 创建完整的SongDetail
        val finalDetail = SongDetail(
            url = detail.url,
            info = detail.info,
            cover = mergedCover,
            lyrics = mergedLyrics
        )

        // 4. 创建播放列表歌曲
        val playlistSong = PlaylistSong(
            id = song.id,
            name = song.name,
            artists = song.artists,
            coverUrl = mergedCover ?: "",
            platform = platform.name
        )

        // 5. 添加到播放列表
        playlistManager.addSong(playlistSong)
        playlistManager.setCurrentIndex(playlistManager.getPlaylistSize() - 1)

        // 6. 清除之前的播放状态
        clearPlaybackState()
        playbackPreferences.currentPosition = 0L
        playbackPreferences.isPlaying = false

        // 7. 保存播放状态（使用用户设置的试听音质）
        savePlaybackState(
            songId = song.id,
            position = 0L,
            isPlaying = true,
            quality = playbackQuality,
            songDetail = finalDetail
        )

        // 8. 启动播放页面（快速进入）
        startPlayerActivity(
            context = context,
            songId = song.id,
            songName = song.name,
            songArtists = song.artists,
            platform = platform,
            songUrl = detail.url,
            songCover = mergedCover,
            songLyrics = mergedLyrics
        )

        // 9. 后台预加载完整信息（如果缓存不完整）
        if (detail.cover == null || detail.lyrics == null) {
            Log.d(TAG, "后台预加载歌曲完整信息: ${song.name}")
            preloadManager.preloadSongInfo(song, platform)
        }
    }

    /**
     * 播放歌曲（从下载历史）
     * @param context 上下文
     * @param songId 歌曲ID
     * @param songName 歌曲名称
     * @param artist 艺术家
     * @param coverUrl 封面URL
     * @param playUrl 播放URL（本地文件路径或在线URL）
     * @param platform 音乐平台
     * @param songDetail 歌曲详情（可选，用于获取歌词和封面）
     * @param quality 音质（可选，默认使用用户设置的试听音质）
     */
    suspend fun playFromDownload(
        context: Context,
        songId: String,
        songName: String,
        artist: String,
        coverUrl: String?,
        playUrl: String,
        platform: MusicRepository.Platform = MusicRepository.Platform.KUWO,
        songDetail: SongDetail? = null,
        quality: String? = null
    ) {
        // 创建播放列表歌曲
        val playlistSong = PlaylistSong(
            id = songId,
            name = songName,
            artists = artist,
            coverUrl = coverUrl ?: "",
            platform = platform.name
        )

        // 添加到播放列表
        playlistManager.addSong(playlistSong)

        // 使用传入的音质，如果没有则使用用户设置的试听音质
        val playbackQuality = quality ?: SettingsActivity.getPlaybackQuality(appContext)

        // 保存播放状态（新歌曲从头开始播放，位置为0）
        savePlaybackState(
            songId = songId,
            position = 0L,
            isPlaying = true,
            quality = playbackQuality,
            songDetail = songDetail
        )

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = songId,
            songName = songName,
            songArtists = artist,
            platform = platform,
            songUrl = playUrl,
            songCover = songDetail?.cover ?: coverUrl,
            songLyrics = songDetail?.lyrics
        )
    }

    /**
     * 播放歌曲（从歌单）- 优化版本
     * @param context 上下文
     * @param song 歌单中的歌曲
     * @param playUrl 播放URL（本地文件路径或在线URL）
     * @param songDetail 歌曲详情（可选，用于获取歌词和封面）
     */
    suspend fun playFromPlaylist(
        context: Context,
        song: PlaylistSong,
        playUrl: String,
        songDetail: SongDetail? = null
    ) {
        // LOCAL 平台歌曲：优先本地文件立即播放，封面/歌词后台补全
        if (isLocalPlaylistSong(song)) {
            playLocalSongById(
                context = context,
                songId = song.id,
                songName = song.name,
                songArtists = song.artists,
                fallbackCoverUrl = song.coverUrl
            )
            return
        }

        val platform = try {
            MusicRepository.Platform.valueOf(song.platform.uppercase())
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }

        // 确定最终使用的封面URL：优先使用 songDetail 中的封面，其次使用 song 中的封面
        val finalCoverUrl = songDetail?.cover ?: song.coverUrl

        // 如果 songDetail 中有封面，更新 song 的 coverUrl
        val updatedSong = if (songDetail?.cover != null) {
            song.copy(coverUrl = songDetail.cover)
        } else {
            song
        }

        // 添加到播放列表（如果歌曲已存在，会更新歌曲信息如封面）
        playlistManager.addSong(updatedSong)

        // 设置当前播放索引为新添加/更新的歌曲
        val songIndex = playlistManager.currentPlaylist.value.indexOfFirst { it.id == updatedSong.id }
        if (songIndex != -1) {
            playlistManager.setCurrentIndex(songIndex)
        }

        // 关键修复：彻底清除之前的播放状态，确保新歌曲从头开始播放
        // 先清除再保存，避免任何旧状态残留
        clearPlaybackState()

        // 确保播放位置为0，新歌曲从头开始
        playbackPreferences.currentPosition = 0L
        playbackPreferences.isPlaying = false

        // 获取用户设置的试听音质
        val playbackQuality = SettingsActivity.getPlaybackQuality(appContext)

        // 保存播放状态（新歌曲从头开始播放，位置为0）
        savePlaybackState(
            songId = song.id,
            position = 0L,
            isPlaying = true,
            quality = playbackQuality,
            songDetail = songDetail
        )

        // 再次确保位置为0
        playbackPreferences.currentPosition = 0L

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = song.id,
            songName = song.name,
            songArtists = song.artists,
            platform = platform,
            songUrl = playUrl,
            songCover = finalCoverUrl,
            songLyrics = songDetail?.lyrics
        )
    }

    /**
     * 快速播放歌曲（从歌单）- 极速版本
     * 优先使用本地缓存，快速进入播放页
     * 
     * @param context 上下文
     * @param song 歌单中的歌曲
     * @param autoSkipOnFailure 获取失败时是否自动跳过到下一首
     */
    suspend fun playFromPlaylistFast(
        context: Context,
        song: PlaylistSong,
        autoSkipOnFailure: Boolean = true
    ) {
        Log.d(TAG, "快速播放歌单歌曲: ${song.name}")

        if (isLocalPlaylistSong(song)) {
            playLocalSongById(
                context = context,
                songId = song.id,
                songName = song.name,
                songArtists = song.artists,
                fallbackCoverUrl = song.coverUrl
            )
            return
        }

        val platform = try {
            MusicRepository.Platform.valueOf(song.platform.uppercase())
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }

        // 获取用户设置的试听音质
        val playbackQuality = SettingsActivity.getPlaybackQuality(appContext)

        // 1. 先检查本地缓存
        val cachedRepository = CachedMusicRepository(context)
        val cachedDetail = cachedRepository.getCachedCoverAndLyrics(song.id)

        // 2. 快速获取URL（使用getSongUrlWithCache以获取完整的封面和歌词逻辑）
        val urlDetail = withContext(Dispatchers.IO) {
            cachedRepository.getSongUrlWithCache(
                platform = platform,
                songId = song.id,
                quality = playbackQuality,
                songName = song.name,
                artists = song.artists,
                useCache = true,
                coverUrlFromSearch = song.coverUrl
            )
        }

        if (urlDetail == null) {
            Log.e(TAG, "获取歌曲链接失败: ${song.name} - ${song.artists}")
            
            // 如果允许自动跳过，尝试播放下一首
            if (autoSkipOnFailure) {
                Log.d(TAG, "尝试自动跳过到下一首")
                val nextSong = playlistManager.next()
                if (nextSong != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "'${song.name}' 无法播放，自动切换到下一首", Toast.LENGTH_SHORT).show()
                    }
                    // 递归调用播放下一首，但只尝试一次自动跳过
                    playFromPlaylistFast(context, nextSong, autoSkipOnFailure = false)
                    return
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "当前歌曲无法播放，且没有下一首", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "获取歌曲链接失败", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        // 3. 使用获取到的封面和歌词（getSongUrlWithCache已经处理了平台特定的封面获取逻辑）
        val mergedCover = urlDetail.cover ?: song.coverUrl
        val mergedLyrics = urlDetail.lyrics

        // 4. 更新播放列表歌曲信息
        val updatedSong = song.copy(coverUrl = mergedCover ?: song.coverUrl)
        playlistManager.addSong(updatedSong)

        val songIndex = playlistManager.currentPlaylist.value.indexOfFirst { it.id == updatedSong.id }
        if (songIndex != -1) {
            playlistManager.setCurrentIndex(songIndex)
        }

        // 6. 清除播放状态
        clearPlaybackState()
        playbackPreferences.currentPosition = 0L
        playbackPreferences.isPlaying = false

        // 7. 保存播放状态（使用用户设置的试听音质）
        val finalDetail = SongDetail(
            url = urlDetail.url,
            info = urlDetail.info,
            cover = mergedCover,
            lyrics = mergedLyrics
        )
        savePlaybackState(
            songId = song.id,
            position = 0L,
            isPlaying = true,
            quality = playbackQuality,
            songDetail = finalDetail
        )

        // 8. 启动播放页面
        startPlayerActivity(
            context = context,
            songId = song.id,
            songName = song.name,
            songArtists = song.artists,
            platform = platform,
            songUrl = urlDetail.url,
            songCover = mergedCover,
            songLyrics = mergedLyrics
        )

        // 9. 后台预加载（如果缓存不完整）
        if (cachedDetail?.cover == null || cachedDetail.lyrics == null) {
            preloadManager.preloadPlaylistSong(song)
        }
    }

    /**
     * 播放歌曲（从正在播放列表）
     * 这种情况通常是切换歌曲，需要重置播放进度为0，确保新歌曲从头开始播放
     * @param context 上下文
     * @param song 要播放的歌曲
     * @param playUrl 播放URL
     * @param songDetail 歌曲详情
     */
    suspend fun playFromNowPlaying(
        context: Context,
        song: PlaylistSong,
        playUrl: String,
        songDetail: SongDetail? = null
    ) {
        if (song.platform.equals("LOCAL", ignoreCase = true)) {
            playLocalSongById(
                context = context,
                songId = song.id,
                songName = song.name,
                songArtists = song.artists,
                fallbackCoverUrl = song.coverUrl
            )
            return
        }

        val platform = try {
            MusicRepository.Platform.valueOf(song.platform.uppercase())
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }

        // 关键修复：彻底清除之前的播放状态，确保新歌曲从头开始播放
        // 先清除再保存，避免任何旧状态残留
        clearPlaybackState()

        // 确保播放位置为0，新歌曲从头开始
        playbackPreferences.currentPosition = 0L
        playbackPreferences.isPlaying = false

        // 获取用户设置的试听音质
        val playbackQuality = SettingsActivity.getPlaybackQuality(appContext)

        // 保存播放状态（新歌曲从头开始播放，位置为0）
        savePlaybackState(
            songId = song.id,
            position = 0L,
            isPlaying = true,
            quality = playbackQuality,
            songDetail = songDetail
        )

        // 再次确保位置为0
        playbackPreferences.currentPosition = 0L

        // 确定封面URL：优先使用 songDetail 中的封面，其次使用 song 中的封面
        val finalCoverUrl = songDetail?.cover ?: song.coverUrl

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = song.id,
            songName = song.name,
            songArtists = song.artists,
            platform = platform,
            songUrl = playUrl,
            songCover = finalCoverUrl,
            songLyrics = songDetail?.lyrics
        )
    }

    /**
     * 添加歌曲到播放列表但不立即播放
     * 在后台自动预加载歌词和封面
     *
     * @param song 歌曲
     * @param platform 平台
     */
    suspend fun addToPlaylistWithoutPlay(
        song: Song,
        platform: MusicRepository.Platform
    ) {
        // 创建播放列表歌曲
        val playlistSong = PlaylistSong(
            id = song.id,
            name = song.name,
            artists = song.artists,
            coverUrl = song.coverUrl ?: "",
            platform = platform.name
        )

        // 添加到播放列表（不触发自动播放）
        playlistManager.addSong(playlistSong, autoPlay = false)

        // 后台预加载歌曲信息
        preloadManager.preloadSongInfo(song, platform)

        Log.d(TAG, "添加歌曲到播放列表并后台预加载: ${song.name}")
    }

    /**
     * 添加播放列表歌曲但不立即播放
     *
     * @param song 播放列表歌曲
     */
    suspend fun addPlaylistSongWithoutPlay(song: PlaylistSong) {
        val normalizedSong = if (isLocalPlaylistSong(song)) {
            enrichLocalPlaylistSong(song)
        } else {
            song
        }

        // 添加到播放列表（不触发自动播放）
        playlistManager.addSong(normalizedSong, autoPlay = false)

        // 后台预加载
        if (!isLocalPlaylistSong(normalizedSong)) {
            preloadManager.preloadPlaylistSong(normalizedSong)
        }

        Log.d(TAG, "添加播放列表歌曲并后台预加载: ${normalizedSong.name}")
    }

    /**
     * 批量添加歌曲到播放列表但不立即播放
     * 新添加的歌曲追加到列表末尾，不替换当前播放，不触发自动播放
     *
     * @param songs 歌曲列表
     * @param platform 平台
     * @return Pair<添加数量, 重复数量>
     */
    suspend fun addSongsToPlaylistWithoutPlay(
        songs: List<Song>,
        platform: MusicRepository.Platform
    ): Pair<Int, Int> {
        // 转换为播放列表歌曲
        val playlistSongs = songs.map { song ->
            PlaylistSong(
                id = song.id,
                name = song.name,
                artists = song.artists,
                coverUrl = song.coverUrl ?: "",
                platform = platform.name
            )
        }

        // 批量添加到播放列表（不触发自动播放）
        val result = playlistManager.addSongs(playlistSongs)

        // 关键优化：同步预加载第一首歌的信息，确保用户进入播放页后可以立即播放
        // 其余歌曲在后台异步预加载
        if (songs.isNotEmpty()) {
            val firstSong = songs.first()
            try {
                Log.d(TAG, "同步预加载第一首歌信息: ${firstSong.name}")

                // 使用用户设置的听音质进行预加载
                val playbackQuality = SettingsActivity.getPlaybackQuality(appContext)
                
                // 获取歌曲详情（优先从缓存获取，如果没有缓存则从网络获取）
                val detail = cachedMusicRepository.getSongDetail(
                    platform = platform,
                    songId = firstSong.id,
                    quality = playbackQuality,
                    coverUrlFromSearch = firstSong.coverUrl,
                    songName = firstSong.name,
                    artists = firstSong.artists
                )

                if (detail != null) {
                    Log.d(TAG, "第一首歌信息预加载成功: ${firstSong.name}, 封面=${detail.cover != null}, 歌词=${detail.lyrics != null}")

                    // 关键修复：将歌曲详情保存到 playbackPreferences，这样进入播放页时可以恢复
                    playbackPreferences.saveSongDetail(firstSong.id, detail)

                    // 如果封面URL有效，预下载封面图片到本地缓存
                    if (!detail.cover.isNullOrEmpty()) {
                        // 使用小写的平台名称（与CoverImageManager缓存键一致）
                        CoverImageManager.downloadAndCacheCover(
                            context = appContext,
                            songId = firstSong.id,
                            platform = platform.name.lowercase(),
                            quality = playbackQuality,
                            songName = firstSong.name,
                            artist = firstSong.artists
                        )
                    }
                } else {
                    Log.w(TAG, "第一首歌信息预加载失败: ${firstSong.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "预加载第一首歌信息异常: ${firstSong.name}", e)
            }

            // 其余歌曲在后台异步预加载
            if (songs.size > 1) {
                songs.drop(1).forEach { song ->
                    preloadManager.preloadSongInfo(song, platform)
                }
            }
        }

        Log.d(TAG, "批量添加 ${result.first} 首歌曲到播放列表，${result.second} 首已存在")
        return result
    }

    /**
     * 批量添加播放列表歌曲但不立即播放
     *
     * @param songs 播放列表歌曲列表
     * @return Pair<添加数量, 重复数量>
     */
    suspend fun addPlaylistSongsWithoutPlay(songs: List<PlaylistSong>): Pair<Int, Int> {
        val normalizedSongs = songs.map { song ->
            if (isLocalPlaylistSong(song)) {
                enrichLocalPlaylistSong(song)
            } else {
                song
            }
        }

        // 批量添加到播放列表（不触发自动播放）
        val result = playlistManager.addSongs(normalizedSongs)

        // 后台预加载
        normalizedSongs.forEach { song ->
            if (!isLocalPlaylistSong(song)) {
                preloadManager.preloadPlaylistSong(song)
            }
        }

        Log.d(TAG, "批量添加 ${result.first} 首播放列表歌曲，${result.second} 首已存在")
        return result
    }

    /**
     * LOCAL 歌曲加入播放列表时，优先使用本地缓存封面/歌词；
     * 若缺失则后台拉取并缓存（不阻塞添加流程）。
     */
    private suspend fun enrichLocalPlaylistSong(song: PlaylistSong): PlaylistSong {
        val localMusicInfoRepository = LocalMusicInfoRepository(appContext)
        val allLocalMusic = withContext(Dispatchers.IO) {
            localMusicInfoRepository.getAllCachedMusic()
        }
        if (allLocalMusic.isEmpty()) {
            return song
        }

        val hash = song.id.removePrefix("local_").toIntOrNull()
        val matchedMusic = allLocalMusic.firstOrNull { hash != null && it.path.hashCode() == hash }
            ?: allLocalMusic.firstOrNull { it.title == song.name && it.artist == song.artists }
            ?: return song

        val cachedInfo = withContext(Dispatchers.IO) {
            localMusicInfoRepository.getCachedInfoByPath(matchedMusic.path)
        }

        if (cachedInfo == null || cachedInfo.coverUrl.isNullOrBlank() || cachedInfo.lyrics.isNullOrBlank()) {
            preloadManager.preloadLocalMusicInfo(matchedMusic, MusicRepository.Platform.KUWO)
        }

        val bestCover = cachedInfo?.coverUrl ?: matchedMusic.coverUri ?: song.coverUrl
        return song.copy(coverUrl = bestCover)
    }

    /**
     * 保存播放状态
     */
    private fun savePlaybackState(
        songId: String,
        position: Long,
        isPlaying: Boolean,
        quality: String,
        songDetail: SongDetail? = null
    ) {
        playbackPreferences.apply {
            currentSongId = songId
            currentPosition = position
            this.isPlaying = isPlaying
            currentQuality = quality
        }

        // 同时保存完整的歌曲信息到PlaybackPreferences，用于恢复播放
        songDetail?.let { detail ->
            playbackPreferences.saveSongDetail(songId, detail)
        }
    }

    /**
     * 启动播放页面
     */
    private fun startPlayerActivity(
        context: Context,
        songId: String,
        songName: String,
        songArtists: String,
        platform: MusicRepository.Platform,
        songUrl: String,
        songCover: String?,
        songLyrics: String?
    ) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("song_id", songId)
            putExtra("song_name", songName)
            putExtra("song_artists", songArtists)
            putExtra("platform", platform.name)
            putExtra("song_url", songUrl)
            putExtra("song_cover", songCover)
            putExtra("song_lyrics", songLyrics)
            putExtra(PlayerActivity.EXTRA_LAUNCH_MODE, PlayerActivity.LAUNCH_MODE_NORMAL)
        }
        context.startActivity(intent)
    }

    /**
     * 更新当前播放进度
     * 应在播放进度变化时定期调用
     */
    fun updatePlaybackPosition(position: Long) {
        playbackPreferences.currentPosition = position
    }

    /**
     * 获取上次保存的播放状态
     */
    fun getLastPlaybackState(): PlaybackState {
        return PlaybackState(
            songId = playbackPreferences.currentSongId,
            position = playbackPreferences.currentPosition,
            isPlaying = playbackPreferences.isPlaying,
            quality = playbackPreferences.currentQuality
        )
    }

    /**
     * 清除播放状态
     */
    fun clearPlaybackState() {
        playbackPreferences.clearPlaybackState()
    }

    /**
     * 播放状态数据类
     */
    data class PlaybackState(
        val songId: String?,
        val position: Long,
        val isPlaying: Boolean,
        val quality: String
    )

    /**
     * 验证音源平台是否可用
     * @param platform 平台名称
     * @return 是否可用
     */
    fun isPlatformValid(platform: String?): Boolean {
        return platform != null && SUPPORTED_PLATFORMS.contains(platform.uppercase())
    }

    /**
     * 获取有效的平台，如果无效则返回默认平台
     * @param platform 平台名称
     * @return 有效的平台
     */
    fun getValidPlatform(platform: String?): MusicRepository.Platform {
        return try {
            if (isPlatformValid(platform)) {
                MusicRepository.Platform.valueOf(platform!!.uppercase())
            } else {
                MusicRepository.Platform.KUWO
            }
        } catch (e: Exception) {
            MusicRepository.Platform.KUWO
        }
    }

    /**
     * 播放歌曲（从最近播放记录）- 优化版本
     * @param context 上下文
     * @param recentPlay 最近播放记录
     */
    suspend fun playFromRecentPlay(
        context: Context,
        recentPlay: RecentPlay
    ): Boolean {
        // LOCAL 平台歌曲：优先本地文件立即播放，封面/歌词后台补全
        if (recentPlay.platform.equals("LOCAL", ignoreCase = true) || recentPlay.id.startsWith("local_")) {
            return playLocalSongById(
                context = context,
                songId = recentPlay.id,
                songName = recentPlay.name,
                songArtists = recentPlay.artists,
                fallbackCoverUrl = recentPlay.coverUrl
            )
        }

        // 验证平台可用性
        val platform = getValidPlatform(recentPlay.platform)

        // 获取用户设置的试听音质
        val playbackQuality = SettingsActivity.getPlaybackQuality(appContext)

        // 使用带缓存的Repository获取歌曲详情，传入封面URL以获取正确的封面
        val cachedRepository = CachedMusicRepository(context)
        val detail = withContext(Dispatchers.IO) {
            cachedRepository.getSongDetail(
                platform = platform,
                songId = recentPlay.id,
                quality = playbackQuality,
                songName = recentPlay.name,
                artists = recentPlay.artists,
                coverUrlFromSearch = recentPlay.coverUrl
            )
        }

        return if (detail != null) {
            // 使用获取到的封面，如果没有则尝试使用数据库中的封面
            val finalCoverUrl = detail.cover ?: recentPlay.coverUrl

            // 创建播放列表歌曲
            val playlistSong = PlaylistSong(
                id = recentPlay.id,
                name = recentPlay.name,
                artists = recentPlay.artists,
                coverUrl = finalCoverUrl ?: "",
                platform = platform.name
            )

            // 添加到播放列表并设置为当前播放（不清空原有列表）
            playlistManager.addSong(playlistSong)
            playlistManager.setCurrentIndex(playlistManager.getPlaylistSize() - 1)

            // 保存播放状态（使用用户设置的试听音质）
            savePlaybackState(
                songId = recentPlay.id,
                position = 0L,
                isPlaying = true,
                quality = playbackQuality,
                songDetail = detail
            )

            // 启动播放页面
            startPlayerActivity(
                context = context,
                songId = recentPlay.id,
                songName = recentPlay.name,
                songArtists = recentPlay.artists,
                platform = platform,
                songUrl = detail.url,
                songCover = finalCoverUrl,
                songLyrics = detail.lyrics
            )
            true
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "获取歌曲信息失败，可能音源不可用", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    /**
     * 播放歌曲（从下载历史）- 带平台信息版本
     * @param context 上下文
     * @param songId 歌曲ID
     * @param songName 歌曲名称
     * @param artist 艺术家
     * @param coverUrl 封面URL
     * @param playUrl 播放URL（本地文件路径）
     * @param platform 音源平台
     * @param quality 音质（可选，默认使用用户设置的试听音质）
     */
    suspend fun playFromDownloadWithPlatform(
        context: Context,
        songId: String,
        songName: String,
        artist: String,
        coverUrl: String?,
        playUrl: String,
        platform: String,
        quality: String? = null
    ): Boolean {
        // 验证平台
        val validPlatform = getValidPlatform(platform)

        // 本地文件直接播放，不需要获取详情
        val playlistSong = PlaylistSong(
            id = songId,
            name = songName,
            artists = artist,
            coverUrl = coverUrl ?: "",
            platform = validPlatform.name
        )

        // 添加到播放列表
        playlistManager.addSong(playlistSong)

        // 使用传入的音质，如果没有则使用用户设置的试听音质
        val playbackQuality = quality ?: SettingsActivity.getPlaybackQuality(appContext)

        // 优先从本地缓存获取歌曲详情（不触发网络请求）
        val cachedRepository = CachedMusicRepository(context)
        val cachedDetail = withContext(Dispatchers.IO) {
            cachedRepository.getLocalSongDetail(songId)
        }

        // 如果本地缓存存在，直接使用缓存数据
        val finalDetail = if (cachedDetail != null) {
            Log.d(TAG, "使用本地缓存的歌曲详情播放下载歌曲: $songName")
            cachedDetail
        } else {
            // 本地缓存不存在，创建基本的SongDetail用于播放
            Log.d(TAG, "本地缓存不存在，使用下载任务信息播放: $songName")
            SongDetail(
                url = playUrl,
                info = SongInfo(name = songName, artist = artist),
                cover = coverUrl,
                lyrics = null
            )
        }

        // 保存播放状态
        savePlaybackState(
            songId = songId,
            position = 0L,
            isPlaying = true,
            quality = playbackQuality,
            songDetail = finalDetail
        )

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = songId,
            songName = songName,
            songArtists = artist,
            platform = validPlatform,
            songUrl = playUrl,
            songCover = finalDetail.cover ?: coverUrl,
            songLyrics = finalDetail.lyrics
        )

        // 后台尝试获取缺失的封面和歌词（如果缓存中没有）
        if (cachedDetail?.cover.isNullOrBlank() || cachedDetail?.lyrics.isNullOrBlank()) {
            Log.d(TAG, "后台获取缺失的封面和歌词: $songName")
            preloadManager.preloadPlaylistSong(playlistSong)
        }

        return true
    }

    /**
     * 通过 LOCAL 歌曲ID（local_<pathHash>）快速定位本地文件并播放
     * 优先使用本地缓存的封面和歌词，缺失时在后台补全
     */
    suspend fun playLocalSongById(
        context: Context,
        songId: String,
        songName: String,
        songArtists: String,
        fallbackCoverUrl: String? = null
    ): Boolean {
        if (!songId.startsWith("local_")) {
            return false
        }

        return try {
            val localMusicInfoRepository = LocalMusicInfoRepository(context)
            val allLocalMusic = withContext(Dispatchers.IO) {
                localMusicInfoRepository.getAllCachedMusic()
            }

            if (allLocalMusic.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "未找到本地音乐记录，请先重新扫描本地音乐", Toast.LENGTH_SHORT).show()
                }
                return false
            }

            val hash = songId.removePrefix("local_").toIntOrNull()
            val matchedMusic = allLocalMusic.firstOrNull { hash != null && it.path.hashCode() == hash }
                ?: allLocalMusic.firstOrNull { it.title == songName && it.artist == songArtists }

            if (matchedMusic == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "未找到本地文件，请先重新扫描本地音乐", Toast.LENGTH_SHORT).show()
                }
                return false
            }

            val cachedInfo = withContext(Dispatchers.IO) {
                localMusicInfoRepository.getCachedInfoByPath(matchedMusic.path)
            }

            playFromLocalMusic(
                context = context,
                localMusic = matchedMusic,
                coverUrl = cachedInfo?.coverUrl ?: fallbackCoverUrl ?: matchedMusic.coverUri,
                lyrics = cachedInfo?.lyrics
            )
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "本地播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    /**
     * 播放本地音乐
     * 优先使用本地数据库缓存的封面和歌词，如果没有则使用通用接口获取
     *
     * @param context 上下文
     * @param localMusic 本地音乐信息
     * @param coverUrl 本地封面URI或URL
     * @param lyrics 本地缓存歌词
     * @param platform 音源平台（可选，默认KUWO）
     */
    suspend fun playFromLocalMusic(
        context: Context,
        localMusic: com.tacke.music.ui.LocalMusic,
        coverUrl: String? = null,
        lyrics: String? = null,
        platform: MusicRepository.Platform = MusicRepository.Platform.KUWO
    ): Boolean {
        Log.d(TAG, "播放本地音乐: ${localMusic.title}")

        // 生成唯一ID（使用文件路径的hashCode）
        val songId = "local_${localMusic.path.hashCode()}"

        // 创建播放列表歌曲
        val playlistSong = PlaylistSong(
            id = songId,
            name = localMusic.title,
            artists = localMusic.artist,
            coverUrl = coverUrl ?: localMusic.coverUri ?: "",
            platform = "LOCAL" // 使用LOCAL标识本地音乐
        )

        // 添加到播放列表
        playlistManager.addSong(playlistSong)
        // 关键修复：将当前播放索引切到本次点击播放的本地歌曲
        // 否则从列表页再次进入播放页时，可能按旧索引恢复成上一首歌曲信息
        val songIndex = playlistManager.currentPlaylist.value.indexOfFirst { it.id == songId }
        if (songIndex != -1) {
            playlistManager.setCurrentIndex(songIndex)
        }

        // 获取用户设置的试听音质
        val playbackQuality = SettingsActivity.getPlaybackQuality(appContext)

        // 优先使用 contentUri（MediaStore URI），这在 Android 10+ 上更可靠
        // 如果没有 contentUri，则使用文件路径
        val playUrl = if (!localMusic.contentUri.isNullOrEmpty()) {
            Log.d(TAG, "使用 MediaStore URI 播放: ${localMusic.contentUri}")
            localMusic.contentUri
        } else {
            Log.d(TAG, "使用文件路径播放: ${localMusic.path}")
            localMusic.path
        }

        // 创建SongDetail，优先使用本地缓存的封面和歌词
        val songDetail = SongDetail(
            url = playUrl, // 使用 contentUri 或文件路径作为播放URL
            info = SongInfo(
                name = localMusic.title,
                artist = localMusic.artist
            ),
            cover = coverUrl ?: localMusic.coverUri,
            lyrics = lyrics
        )

        // 保存播放状态
        savePlaybackState(
            songId = songId,
            position = 0L,
            isPlaying = true,
            quality = playbackQuality,
            songDetail = songDetail
        )

        // 启动播放页面
        startPlayerActivity(
            context = context,
            songId = songId,
            songName = localMusic.title,
            songArtists = localMusic.artist,
            platform = platform, // 使用传入的平台，用于后续获取在线封面和歌词
            songUrl = playUrl,
            songCover = songDetail.cover,
            songLyrics = songDetail.lyrics
        )

        // 后台尝试获取在线封面和歌词（如果本地没有）
        if (coverUrl == null || lyrics == null) {
            Log.d(TAG, "后台获取在线封面和歌词: ${localMusic.title}")
            preloadManager.preloadLocalMusicInfo(localMusic, platform)
        }

        return true
    }
}
