package com.tacke.music.data.repository

import android.content.Context
import android.util.Log
import com.tacke.music.MusicApplication
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.db.DownloadTaskEntity
import com.tacke.music.utils.CoverImageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ListCoverRepairManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getDatabase(appContext)
    private val favoriteDao = db.favoriteSongDao()
    private val playlistDao = db.playlistDao()
    private val recentPlayDao = db.recentPlayDao()
    private val downloadTaskDao = db.downloadTaskDao()
    private val localMusicInfoRepository = LocalMusicInfoRepository(appContext)
    private val playlistRepository = PlaylistRepository(appContext)

    private val appScope: CoroutineScope =
        (appContext as? MusicApplication)?.applicationScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val runningFlags = ConcurrentHashMap<String, AtomicBoolean>()

    companion object {
        private const val TAG = "ListCoverRepairMgr"

        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_RECENT = "recent"
        private const val KEY_DOWNLOADING = "downloading"
        private const val KEY_HISTORY = "history"

        @Volatile
        private var INSTANCE: ListCoverRepairManager? = null

        fun getInstance(context: Context): ListCoverRepairManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ListCoverRepairManager(context).also { INSTANCE = it }
            }
        }
    }

    fun repairFavoritesAsync() {
        launchOnce(KEY_FAVORITES) {
            val favorites = favoriteDao.getAllFavoriteSongsSync()
            favorites.forEach { song ->
                val cover = resolveMissingCover(
                    songId = song.id,
                    name = song.name,
                    artists = song.artists,
                    platform = song.platform,
                    currentCover = song.coverUrl
                ) ?: return@forEach
                if (cover != song.coverUrl) {
                    favoriteDao.updateSongCoverUrl(song.id, cover)
                }
            }
        }
    }

    fun repairPlaylistsAsync() {
        launchOnce(KEY_PLAYLISTS) {
            val playlists = playlistDao.getAllPlaylistsSync()
            val processedSongIds = mutableSetOf<String>()

            playlists.forEach { playlist ->
                val songs = playlistDao.getSongsInPlaylistEntitiesSync(playlist.id)
                songs.forEach { song ->
                    if (!processedSongIds.add(song.id)) return@forEach
                    val cover = resolveMissingCover(
                        songId = song.id,
                        name = song.name,
                        artists = song.artists,
                        platform = song.platform,
                        currentCover = song.coverUrl
                    ) ?: return@forEach
                    if (cover != song.coverUrl) {
                        playlistDao.updateSongCoverUrlBySongId(song.id, cover)
                    }
                }
            }

            playlists.forEach { playlist ->
                playlistRepository.updatePlaylistCoverToLatest(playlist.id)
            }
        }
    }

    fun repairRecentPlayAsync() {
        launchOnce(KEY_RECENT) {
            val recentPlays = recentPlayDao.getRecentPlaysSync(200)
            recentPlays.forEach { item ->
                val cover = resolveMissingCover(
                    songId = item.id,
                    name = item.name,
                    artists = item.artists,
                    platform = item.platform,
                    currentCover = item.coverUrl
                ) ?: return@forEach
                if (cover != item.coverUrl) {
                    recentPlayDao.updateCoverUrl(item.id, cover)
                }
            }
        }
    }

    fun repairDownloadingListAsync() {
        launchOnce(KEY_DOWNLOADING) {
            val tasks = downloadTaskDao.getDownloadingTasksOnce()
            repairDownloadTaskCovers(tasks)
        }
    }

    fun repairDownloadHistoryAsync() {
        launchOnce(KEY_HISTORY) {
            val tasks = downloadTaskDao.getCompletedTasksOnce()
            repairDownloadTaskCovers(tasks)
        }
    }

    private suspend fun repairDownloadTaskCovers(tasks: List<DownloadTaskEntity>) {
        val processedSongIds = mutableSetOf<String>()
        tasks.forEach { task ->
            if (!processedSongIds.add(task.songId)) return@forEach
            val cover = resolveMissingCover(
                songId = task.songId,
                name = task.songName,
                artists = task.artist,
                platform = task.platform,
                currentCover = task.coverUrl
            ) ?: return@forEach
            if (cover != task.coverUrl) {
                downloadTaskDao.updateCoverUrlBySongId(task.songId, cover)
            }
        }
    }

    private fun launchOnce(key: String, block: suspend () -> Unit) {
        val flag = runningFlags.getOrPut(key) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) {
            return
        }
        appScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "repair task failed: $key", e)
            } finally {
                flag.set(false)
            }
        }
    }

    private suspend fun resolveMissingCover(
        songId: String,
        name: String,
        artists: String,
        platform: String,
        currentCover: String?
    ): String? {
        if (!isCoverMissing(currentCover)) {
            return null
        }

        if (isLocalSong(songId, platform)) {
            return resolveLocalSongCover(songId, name, artists)
        }

        // 使用小写的平台名称（与CoverImageManager缓存键一致）
        val cachePlatform = platform.lowercase()
        val cachedPath = CoverImageManager.getCoverPath(appContext, songId, cachePlatform)
        if (!cachedPath.isNullOrBlank()) {
            return cachedPath
        }

        return CoverImageManager.downloadAndCacheCover(
            context = appContext,
            songId = songId,
            platform = cachePlatform,
            songName = name,
            artist = artists
        )
    }

    private suspend fun resolveLocalSongCover(
        songId: String,
        name: String,
        artists: String
    ): String? {
        val localList = localMusicInfoRepository.getAllCachedMusic()
        if (localList.isEmpty()) return null

        val matched = findMatchedLocalMusic(localList, songId, name, artists)
        if (matched == null) {
            Log.w(TAG, "LOCAL 封面匹配失败: songId=$songId, name=$name, artists=$artists")
            return null
        }

        val info = localMusicInfoRepository.getLocalMusicInfo(matched, forceRefresh = false)
        return info?.coverUrl?.takeIf { it.isNotBlank() } ?: matched.coverUri?.takeIf { it.isNotBlank() }
    }

    private fun findMatchedLocalMusic(
        localList: List<com.tacke.music.ui.LocalMusic>,
        songId: String,
        name: String,
        artists: String
    ): com.tacke.music.ui.LocalMusic? {
        val hash = songId.removePrefix("local_").toIntOrNull()
        if (hash != null) {
            localList.firstOrNull { it.path.hashCode() == hash }?.let { return it }
        }

        val normalizedName = normalizeForMatch(name)
        if (normalizedName.isEmpty()) return null

        val normalizedArtist = normalizeForMatch(artists)
        val titleMatched = localList.filter { isTitleMatched(normalizedName, normalizeForMatch(it.title)) }
        if (titleMatched.isEmpty()) return null

        val artistMatched = titleMatched.firstOrNull { local ->
            isArtistMatched(normalizedArtist, normalizeForMatch(local.artist))
        }
        if (artistMatched != null) return artistMatched

        // 艺术家信息不稳定时，标题唯一命中也可作为兜底
        if (titleMatched.size == 1) return titleMatched.first()

        return null
    }

    private fun isArtistMatched(targetArtist: String, candidateArtist: String): Boolean {
        if (targetArtist.isBlank() || isUnknownArtist(targetArtist)) return true
        if (candidateArtist.isBlank()) return false
        return targetArtist == candidateArtist ||
            targetArtist.contains(candidateArtist) ||
            candidateArtist.contains(targetArtist)
    }

    private fun isTitleMatched(targetTitle: String, candidateTitle: String): Boolean {
        if (targetTitle.isBlank() || candidateTitle.isBlank()) return false
        return targetTitle == candidateTitle ||
            targetTitle.contains(candidateTitle) ||
            candidateTitle.contains(targetTitle)
    }

    private fun isUnknownArtist(artist: String): Boolean {
        return artist.isBlank() ||
            artist == normalizeForMatch("未知艺人") ||
            artist == normalizeForMatch("未知艺术家") ||
            artist == "unknownartist" ||
            artist == "unknown"
    }

    private fun normalizeForMatch(value: String?): String {
        return value.orEmpty()
            .replace(Regex("[\\(（\\[【].*?[\\)）\\]】]"), "")
            .lowercase()
            .replace(Regex("[\\s\\p{P}\\p{S}]"), "")
    }

    private fun isLocalSong(songId: String, platform: String): Boolean {
        return platform.equals("LOCAL", ignoreCase = true) || songId.startsWith("local_")
    }

    private fun isCoverMissing(cover: String?): Boolean {
        if (cover.isNullOrBlank()) return true
        return when {
            cover.startsWith("http", ignoreCase = true) -> false
            cover.startsWith("content://", ignoreCase = true) -> false
            cover.startsWith("file://", ignoreCase = true) -> false
            cover.startsWith("/") -> !File(cover).exists()
            else -> true
        }
    }
}
