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

        // 后台异步下载封面
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val localCoverPath = CoverImageManager.downloadAndCacheCover(
                    context,
                    song.id,
                    platform
                )
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

        // 后台异步批量下载封面
        GlobalScope.launch(Dispatchers.IO) {
            var successCount = 0
            songs.forEach { song ->
                try {
                    val localCoverPath = CoverImageManager.downloadAndCacheCover(
                        context,
                        song.id,
                        platform
                    )
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
            val existingPath = CoverImageManager.getCoverPath(context, song.id, song.platform)

            if (existingPath == null) {
                val coverPath = CoverImageManager.downloadAndCacheCover(
                    context,
                    song.id,
                    song.platform
                )

                if (coverPath != null) {
                    favoriteSongDao.updateSongCoverUrl(song.id, coverPath)
                    updatedCount++
                }
            }
        }

        Log.d(TAG, "刷新喜欢歌曲封面完成，更新了 $updatedCount 首歌曲")
    }
}
