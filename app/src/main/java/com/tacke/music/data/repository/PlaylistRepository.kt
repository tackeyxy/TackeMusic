package com.tacke.music.data.repository

import android.content.Context
import android.util.Log
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.db.PlaylistEntity
import com.tacke.music.data.model.Playlist
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.Song
import com.tacke.music.utils.CoverImageManager
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
        // 下载并缓存封面图片
        val localCoverPath = CoverImageManager.downloadAndCacheCover(
            context,
            song.id,
            platform
        )
        
        val playlistSong = PlaylistSong(
            id = song.id,
            name = song.name,
            artists = song.artists,
            coverUrl = localCoverPath ?: song.coverUrl,
            platform = platform
        )
        playlistDao.addSongToPlaylist(playlistId, playlistSong, songEntityDao)
        
        Log.d(TAG, "添加歌曲到歌单: ${song.name}, 封面路径: $localCoverPath")
    }

    suspend fun addSongsToPlaylist(playlistId: String, songs: List<Song>, platform: String) {
        // 批量下载封面图片
        val coverPaths = CoverImageManager.downloadAndCacheCovers(
            context,
            songs.map { it.id to platform }
        )
        
        val playlistSongs = songs.map { song ->
            val localCoverPath = coverPaths[song.id]
            PlaylistSong(
                id = song.id,
                name = song.name,
                artists = song.artists,
                coverUrl = localCoverPath ?: song.coverUrl,
                platform = platform
            )
        }
        playlistDao.addSongsToPlaylist(playlistId, playlistSongs, songEntityDao)
        
        Log.d(TAG, "批量添加 ${songs.size} 首歌曲到歌单，成功下载 ${coverPaths.size} 张封面")
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
