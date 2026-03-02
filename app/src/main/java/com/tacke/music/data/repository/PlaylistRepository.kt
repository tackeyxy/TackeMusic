package com.tacke.music.data.repository

import android.content.Context
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.db.PlaylistEntity
import com.tacke.music.data.model.Playlist
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlaylistRepository(context: Context) {

    private val playlistDao = AppDatabase.getDatabase(context).playlistDao()

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
            description = description
        )
        playlistDao.insertPlaylist(playlist)
        return playlist.toPlaylist()
    }

    suspend fun updatePlaylist(playlist: Playlist) {
        playlistDao.updatePlaylist(playlist.toEntity())
    }

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylistById(playlistId)
    }

    suspend fun addSongToPlaylist(playlistId: String, song: Song, platform: String) {
        val playlistSong = PlaylistSong(
            id = song.id,
            name = song.name,
            artists = song.artists,
            coverUrl = song.coverUrl,
            platform = platform
        )
        playlistDao.addSongToPlaylist(playlistId, playlistSong)
    }

    suspend fun addSongsToPlaylist(playlistId: String, songs: List<Song>, platform: String) {
        val playlistSongs = songs.map { song ->
            PlaylistSong(
                id = song.id,
                name = song.name,
                artists = song.artists,
                coverUrl = song.coverUrl,
                platform = platform
            )
        }
        playlistDao.addSongsToPlaylist(playlistId, playlistSongs)
    }

    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) {
        playlistDao.removeSongFromPlaylist(playlistId, songId)
        playlistDao.updatePlaylistSongCount(playlistId)
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

    private fun PlaylistEntity.toPlaylist(): Playlist {
        return Playlist(
            id = id,
            name = name,
            description = description,
            coverUrl = coverUrl,
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
            createTime = createTime,
            updateTime = updateTime,
            songCount = songCount
        )
    }
}
