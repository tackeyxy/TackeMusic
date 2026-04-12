package com.tacke.music.data.repository

import android.content.Context
import android.util.Log
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.db.FavoriteSongEntity
import com.tacke.music.data.model.Song
import com.tacke.music.utils.CoverImageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FavoriteRepository(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val favoriteSongDao = database.favoriteSongDao()

    companion object {
        private const val TAG = "FavoriteRepository"
    }

    private fun isLocalSong(songId: String, platform: String): Boolean {
        return platform.equals("LOCAL", ignoreCase = true) || songId.startsWith("local_")
    }

    private fun cachePlatform(platform: String): String = platform.lowercase()

    private suspend fun downloadCoverForSong(song: Song, platform: String): String? {
        val normalizedPlatform = cachePlatform(platform)
        val coverUrl = song.coverUrl?.trim()
        val isOnlineSong = normalizedPlatform == "kuwo" || normalizedPlatform == "netease"

        // 在线歌曲默认使用搜索列表封面：有 coverUrl 时仅缓存该 URL，不再改拉其他封面来源
        if (isOnlineSong && !coverUrl.isNullOrEmpty() && coverUrl.startsWith("http", ignoreCase = true)) {
            val byUrlPath = CoverImageManager.downloadAndCacheCoverByUrl(
                context = context,
                songId = song.id,
                platform = normalizedPlatform,
                coverUrl = coverUrl
            )
            if (!byUrlPath.isNullOrEmpty()) {
                return byUrlPath
            }
            Log.w(TAG, "搜索封面缓存失败，保持原始coverUrl不覆盖: ${song.name}")
            return null
        }

        // 仅在缺失搜索封面时兜底：按平台与歌曲信息获取
        return CoverImageManager.downloadAndCacheCover(
            context = context,
            songId = song.id,
            platform = normalizedPlatform,
            songName = song.name,
            artist = song.artists
        )
    }

    fun getAllFavoriteSongs(): Flow<List<FavoriteSongEntity>> {
        return favoriteSongDao.getAllFavoriteSongs()
    }

    suspend fun getAllFavoriteSongsSync(): List<FavoriteSongEntity> {
        return favoriteSongDao.getAllFavoriteSongsSync()
    }

    suspend fun isFavorite(songId: String): Boolean {
        return favoriteSongDao.isFavorite(songId)
    }

    suspend fun addToFavorites(song: Song, platform: String) {
        val favoriteSong = FavoriteSongEntity(
            id = song.id,
            name = song.name,
            artists = song.artists,
            coverUrl = song.coverUrl,
            platform = platform
        )
        favoriteSongDao.insertFavoriteSong(favoriteSong)

        Log.d(TAG, "添加歌曲到我喜欢: ${song.name}")

        if (isLocalSong(song.id, platform)) {
            return
        }

        // 后台异步下载封面
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val localCoverPath = downloadCoverForSong(song, platform)
                if (localCoverPath != null) {
                    favoriteSongDao.updateSongCoverUrl(song.id, localCoverPath)
                    Log.d(TAG, "封面异步下载完成: ${song.name}, 路径: $localCoverPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "封面异步下载失败: ${song.name}, ${e.message}")
            }
        }
    }

    suspend fun addSongsToFavorites(songs: List<Song>, platform: String) {
        songs.forEach { song ->
            val favoriteSong = FavoriteSongEntity(
                id = song.id,
                name = song.name,
                artists = song.artists,
                coverUrl = song.coverUrl,
                platform = platform
            )
            favoriteSongDao.insertFavoriteSong(favoriteSong)
        }

        Log.d(TAG, "批量添加 ${songs.size} 首歌曲到我喜欢")

        if (songs.all { isLocalSong(it.id, platform) }) {
            return
        }

        // 后台异步批量下载封面
        GlobalScope.launch(Dispatchers.IO) {
            var successCount = 0
            songs.forEach { song ->
                try {
                    val localCoverPath = downloadCoverForSong(song, platform)
                    if (localCoverPath != null) {
                        favoriteSongDao.updateSongCoverUrl(song.id, localCoverPath)
                        successCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "封面异步下载失败: ${song.name}, ${e.message}")
                }
            }
            Log.d(TAG, "批量封面异步下载完成: $successCount/${songs.size}")
        }
    }

    suspend fun removeFromFavorites(songId: String) {
        favoriteSongDao.deleteFavoriteSongById(songId)
        Log.d(TAG, "从我喜欢移除歌曲: $songId")
    }

    suspend fun removeFromFavorites(song: FavoriteSongEntity) {
        favoriteSongDao.deleteFavoriteSong(song)
        Log.d(TAG, "从我喜欢移除歌曲: ${song.name}")
    }

    fun getFavoriteCount(): Flow<Int> {
        return favoriteSongDao.getFavoriteCount()
    }

    suspend fun getFavoriteCountSync(): Int {
        return favoriteSongDao.getFavoriteCountSync()
    }

    suspend fun toggleFavorite(song: Song, platform: String): Boolean {
        return if (isFavorite(song.id)) {
            removeFromFavorites(song.id)
            false
        } else {
            addToFavorites(song, platform)
            true
        }
    }

    suspend fun getFavoriteSongById(songId: String): FavoriteSongEntity? {
        return favoriteSongDao.getFavoriteSongById(songId)
    }

    /**
     * 刷新所有喜欢歌曲的封面图片
     */
    suspend fun refreshFavoriteCovers() {
        val songs = favoriteSongDao.getAllFavoriteSongsSync()
        var updatedCount = 0

        songs.forEach { song ->
            if (isLocalSong(song.id, song.platform)) {
                return@forEach
            }
            // 使用小写的平台名称（与CoverImageManager缓存键一致）
            val cachePlatform = song.platform.lowercase()
            val existingPath = CoverImageManager.getCoverPath(context, song.id, cachePlatform)

            if (existingPath == null) {
                val coverPath = CoverImageManager.downloadAndCacheCover(
                    context,
                    song.id,
                    cachePlatform
                )

                if (coverPath != null) {
                    favoriteSongDao.updateSongCoverUrl(song.id, coverPath)
                    updatedCount++
                }
            }
        }

        Log.d(TAG, "刷新喜欢歌曲封面完成，更新了 $updatedCount 首歌曲")
    }

    /**
     * 回填本地歌曲（LOCAL）封面到“我喜欢的”历史数据
     * @param localCoverMap key=songId(local_xxx), value=coverUrl
     * @return 更新条数
     */
    suspend fun backfillLocalSongCovers(localCoverMap: Map<String, String>): Int {
        if (localCoverMap.isEmpty()) return 0

        val favorites = favoriteSongDao.getAllFavoriteSongsSync()
        var updatedCount = 0

        favorites.forEach { song ->
            if (!isLocalSong(song.id, song.platform)) return@forEach

            val cover = localCoverMap[song.id]
                ?: localCoverMap[buildNameArtistKey(song.name, song.artists)]
            if (!cover.isNullOrBlank() && cover != song.coverUrl) {
                favoriteSongDao.updateSongCoverUrl(song.id, cover)
                updatedCount++
            }
        }

        if (updatedCount > 0) {
            Log.d(TAG, "本地歌曲封面回填完成(我喜欢): $updatedCount")
        }
        return updatedCount
    }

    private fun buildNameArtistKey(name: String, artists: String): String {
        return "name_artist:${normalizeForMatch(name)}|${normalizeForMatch(artists)}"
    }

    private fun normalizeForMatch(value: String?): String {
        return value.orEmpty()
            .replace(Regex("[\\(（\\[【].*?[\\)）\\]】]"), "")
            .lowercase()
            .replace(Regex("[\\s\\p{P}\\p{S}]"), "")
    }
}
