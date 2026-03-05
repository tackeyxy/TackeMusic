package com.tacke.music.data.repository

import android.content.Context
import android.util.Log
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.db.PlaylistEntity
import com.tacke.music.data.model.Playlist
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.Song
import com.tacke.music.utils.CoverImageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlaylistRepository(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val playlistDao = database.playlistDao()
    private val songEntityDao = database.playlistSongEntityDao()

    companion object {
        private const val TAG = "PlaylistRepository"
    }

    fun getAllPlaylists(): Flow<List<Playlist>> {
        return playlistDao.getAllPlaylists().map { entities ->
            entities.map { it.toPlaylist() }
        }
    }

    suspend fun getAllPlaylistsSync(): List<Playlist> {
        return playlistDao.getAllPlaylistsSync().map { it.toPlaylist() }
    }

    suspend fun getPlaylistById(playlistId: String): Playlist? {
        return playlistDao.getPlaylistById(playlistId)?.toPlaylist()
    }

    fun getPlaylistWithSongs(playlistId: String) = playlistDao.getPlaylistWithSongs(playlistId)

    suspend fun createPlaylist(name: String, description: String = ""): Playlist {
        val playlist = PlaylistEntity(
            id = System.currentTimeMillis().toString(),
            name = name,
            description = description,
            iconColor = generateRandomColor()
        )
        playlistDao.insertPlaylist(playlist)
        return playlist.toPlaylist()
    }

    /**
     * 生成随机颜色代码
     * @return 十六进制颜色字符串，如 "#FF6B35"
     */
    private fun generateRandomColor(): String {
        val hue = (0..360).random().toFloat()
        val saturation = (60..90).random() / 100f
        val lightness = (45..65).random() / 100f
        
        return hslToHex(hue, saturation, lightness)
    }

    /**
     * 将 HSL 颜色转换为十六进制
     */
    private fun hslToHex(h: Float, s: Float, l: Float): String {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1))
        val m = l - c / 2

        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = ((r1 + m) * 255).toInt()
        val g = ((g1 + m) * 255).toInt()
        val b = ((b1 + m) * 255).toInt()

        return String.format("#%02X%02X%02X", r, g, b)
    }

    suspend fun updatePlaylist(playlist: Playlist) {
        playlistDao.updatePlaylist(playlist.toEntity())
    }

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylistById(playlistId)
    }

    suspend fun addSongToPlaylist(playlistId: String, song: Song, platform: String) {
        // 先使用原始封面URL快速添加到歌单，不等待封面下载
        val playlistSong = PlaylistSong(
            id = song.id,
            name = song.name,
            artists = song.artists,
            coverUrl = song.coverUrl,
            platform = platform
        )
        playlistDao.addSongToPlaylist(playlistId, playlistSong, songEntityDao)
        
        Log.d(TAG, "添加歌曲到歌单: ${song.name}, 封面将在后台异步下载")
        
        // 后台异步下载封面
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val localCoverPath = CoverImageManager.downloadAndCacheCover(
                    context,
                    song.id,
                    platform
                )
                if (localCoverPath != null) {
                    // 下载成功，更新数据库中的封面路径
                    playlistDao.updateSongCoverUrl(playlistId, song.id, localCoverPath)
                    Log.d(TAG, "封面异步下载完成: ${song.name}, 路径: $localCoverPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "封面异步下载失败: ${song.name}, ${e.message}")
            }
        }
    }

    suspend fun addSongsToPlaylist(playlistId: String, songs: List<Song>, platform: String) {
        // 先使用原始封面URL快速将所有歌曲添加到歌单，不等待封面下载
        val playlistSongs = songs.map { song ->
            PlaylistSong(
                id = song.id,
                name = song.name,
                artists = song.artists,
                coverUrl = song.coverUrl,
                platform = platform
            )
        }
        playlistDao.addSongsToPlaylist(playlistId, playlistSongs, songEntityDao)
        
        Log.d(TAG, "批量添加 ${songs.size} 首歌曲到歌单，封面将在后台异步下载")
        
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
                        // 下载成功，更新数据库中的封面路径
                        playlistDao.updateSongCoverUrl(playlistId, song.id, localCoverPath)
                        successCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "封面异步下载失败: ${song.name}, ${e.message}")
                }
            }
            Log.d(TAG, "批量封面异步下载完成: $successCount/${songs.size}")
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) {
        playlistDao.removeSongFromPlaylist(playlistId, songId)
    }

    suspend fun isSongInPlaylist(playlistId: String, songId: String): Boolean {
        return playlistDao.isSongInPlaylist(playlistId, songId)
    }

    fun getSongsInPlaylist(playlistId: String): Flow<List<PlaylistSong>> {
        return playlistDao.getSongsInPlaylist(playlistId)
    }

    suspend fun getPlaylistCount(): Int {
        return playlistDao.getPlaylistCount()
    }

    suspend fun getPlaylistCover(playlistId: String): String? {
        return playlistDao.getPlaylistCover(playlistId)
    }

    /**
     * 获取歌单封面
     * 逻辑：默认以歌单列表内添加最晚的一首歌的歌曲图片作为歌单封面
     * （若无法获取该歌曲封面则使用比该歌曲稍早的歌曲的图片）
     * 除非歌单无歌曲或无法获取歌单内符合要求的歌曲的图片
     * @param playlistId 歌单ID
     * @return 封面路径，null表示无有效封面
     */
    suspend fun getPlaylistCoverWithFallback(playlistId: String): String? {
        // 首先尝试获取最晚添加且有有效封面的歌曲
        val latestValidCover = playlistDao.getLatestValidSongCover(playlistId)
        if (!latestValidCover.isNullOrEmpty()) {
            return latestValidCover
        }

        // 如果没有有效封面，返回null
        return null
    }

    /**
     * 更新歌单封面为最晚添加的歌曲封面
     * @param playlistId 歌单ID
     */
    suspend fun updatePlaylistCoverToLatest(playlistId: String) {
        val latestCover = getPlaylistCoverWithFallback(playlistId)
        playlistDao.updatePlaylistCover(playlistId, latestCover)
    }

    /**
     * 更新歌曲封面路径
     * @param playlistId 歌单ID
     * @param songId 歌曲ID
     * @param coverPath 封面本地路径
     */
    suspend fun updateSongCover(playlistId: String, songId: String, coverPath: String) {
        playlistDao.updateSongCoverUrl(playlistId, songId, coverPath)
    }

    /**
     * 刷新歌单中所有歌曲的封面图片
     * 用于首次升级后，为已有歌曲下载封面
     */
    suspend fun refreshPlaylistCovers(playlistId: String) {
        val songs = playlistDao.getSongsInPlaylistSync(playlistId)
        var updatedCount = 0
        
        songs.forEach { song ->
            // 检查是否已经有本地缓存的封面
            val existingPath = CoverImageManager.getCoverPath(context, song.id, song.platform)
            
            if (existingPath == null) {
                // 没有缓存，尝试下载
                val coverPath = CoverImageManager.downloadAndCacheCover(
                    context,
                    song.id,
                    song.platform
                )
                
                if (coverPath != null) {
                    // 更新数据库中的封面路径
                    playlistDao.updateSongCoverUrl(playlistId, song.id, coverPath)
                    updatedCount++
                }
            }
        }
        
        Log.d(TAG, "刷新歌单 $playlistId 封面完成，更新了 $updatedCount 首歌曲")
    }

    private fun PlaylistEntity.toPlaylist(): Playlist {
        return Playlist(
            id = id,
            name = name,
            description = description,
            coverUrl = coverUrl,
            iconColor = iconColor,
            createTime = createTime,
            updateTime = updateTime,
            songCount = songCount
        )
    }

    private fun Playlist.toEntity(): PlaylistEntity {
        return PlaylistEntity(
            id = id,
            name = name,
            description = description,
            coverUrl = coverUrl,
            iconColor = iconColor,
            createTime = createTime,
            updateTime = updateTime,
            songCount = songCount
        )
    }
}
