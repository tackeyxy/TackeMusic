package com.tacke.music.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.tacke.music.data.db.AppDatabase
import com.tacke.music.data.db.FavoriteSongEntity
import com.tacke.music.data.db.PlaylistEntity
import com.tacke.music.data.db.PlaylistSongCrossRef
import com.tacke.music.data.model.BackupImportResult
import com.tacke.music.data.model.FavoriteSongBackupItem
import com.tacke.music.data.model.PlaylistBackupItem
import com.tacke.music.data.model.PlaylistSongBackupItem
import com.tacke.music.data.model.PlaylistSongEntity
import com.tacke.music.data.model.UserDataBackup
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

class UserDataBackupRepository(context: Context) {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val favoriteSongDao = database.favoriteSongDao()
    private val playlistDao = database.playlistDao()
    private val playlistSongEntityDao = database.playlistSongEntityDao()
    private val favoriteRepository = FavoriteRepository(appContext)
    private val playlistRepository = PlaylistRepository(appContext)
    private val localMusicInfoRepository = LocalMusicInfoRepository(appContext)

    companion object {
        private const val TAG = "UserDataBackupRepo"
        private val coverSyncRunning = AtomicBoolean(false)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun exportBackupJson(): String {
        val favorites = favoriteSongDao.getAllFavoriteSongsSync().map {
            FavoriteSongBackupItem(
                id = it.id,
                name = it.name,
                artists = it.artists,
                coverUrl = it.coverUrl,
                platform = it.platform,
                addedTime = it.addedTime
            )
        }

        val playlists = playlistDao.getAllPlaylistsSync().map { playlist ->
            val songs = playlistDao.getSongsInPlaylistEntitiesSync(playlist.id)
            PlaylistBackupItem(
                id = playlist.id,
                name = playlist.name,
                description = playlist.description,
                coverUrl = playlist.coverUrl,
                iconColor = playlist.iconColor,
                createTime = playlist.createTime,
                updateTime = playlist.updateTime,
                songs = songs.mapIndexed { index, song ->
                    PlaylistSongBackupItem(
                        id = song.id,
                        name = song.name,
                        artists = song.artists,
                        coverUrl = song.coverUrl,
                        platform = song.platform,
                        addedTime = song.addedTime,
                        orderIndex = index
                    )
                }
            )
        }

        return json.encodeToString(
            UserDataBackup.serializer(),
            UserDataBackup(
                favorites = favorites,
                playlists = playlists
            )
        )
    }

    suspend fun importBackupJson(backupJson: String): BackupImportResult {
        val backup = json.decodeFromString(UserDataBackup.serializer(), backupJson)

        val favoriteEntities = backup.favorites
            .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.platform.isNotBlank() }
            .groupBy { it.id }
            .mapNotNull { (_, items) ->
                items.maxByOrNull { it.addedTime }?.toFavoriteEntity()
            }

        val playlistEntities = mutableListOf<PlaylistEntity>()
        val songEntityMap = linkedMapOf<String, PlaylistSongEntity>()
        val crossRefs = mutableListOf<PlaylistSongCrossRef>()
        backup.playlists
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .forEach { playlist ->
                val validSongs = playlist.songs
                    .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.platform.isNotBlank() }
                    .sortedBy { it.orderIndex }

                playlistEntities.add(
                    PlaylistEntity(
                        id = playlist.id,
                        name = playlist.name,
                        description = playlist.description,
                        coverUrl = playlist.coverUrl,
                        iconColor = playlist.iconColor,
                        createTime = playlist.createTime,
                        updateTime = playlist.updateTime,
                        songCount = validSongs.size
                    )
                )

                validSongs.forEachIndexed { index, song ->
                    val existingSong = songEntityMap[song.id]
                    val candidateSong = song.toPlaylistSongEntity()
                    songEntityMap[song.id] = mergeSongEntity(existingSong, candidateSong)
                    crossRefs.add(
                        PlaylistSongCrossRef(
                            playlistId = playlist.id,
                            songId = song.id,
                            addedTime = song.addedTime,
                            orderIndex = index
                        )
                    )
                }
            }

        database.withTransaction {
            favoriteSongDao.deleteAllFavoriteSongs()
            if (favoriteEntities.isNotEmpty()) {
                favoriteSongDao.insertFavoriteSongs(favoriteEntities)
            }

            playlistDao.clearAllPlaylistSongCrossRefs()
            playlistDao.deleteAllPlaylists()
            playlistSongEntityDao.deleteAllSongs()

            if (songEntityMap.isNotEmpty()) {
                playlistSongEntityDao.insertSongs(songEntityMap.values.toList())
            }
            if (playlistEntities.isNotEmpty()) {
                playlistDao.insertPlaylists(playlistEntities)
            }
            if (crossRefs.isNotEmpty()) {
                playlistDao.insertPlaylistSongCrossRefs(crossRefs)
            }
        }

        return BackupImportResult(
            favoriteCount = favoriteEntities.size,
            playlistCount = playlistEntities.size,
            playlistSongCount = crossRefs.size
        )
    }

    private fun mergeSongEntity(
        old: PlaylistSongEntity?,
        new: PlaylistSongEntity
    ): PlaylistSongEntity {
        if (old == null) return new
        val mergedCover = when {
            !new.coverUrl.isNullOrBlank() -> new.coverUrl
            else -> old.coverUrl
        }
        return old.copy(
            name = if (new.name.isNotBlank()) new.name else old.name,
            artists = if (new.artists.isNotBlank()) new.artists else old.artists,
            coverUrl = mergedCover,
            platform = if (new.platform.isNotBlank()) new.platform else old.platform,
            addedTime = maxOf(old.addedTime, new.addedTime)
        )
    }

    private fun FavoriteSongBackupItem.toFavoriteEntity(): FavoriteSongEntity {
        return FavoriteSongEntity(
            id = id,
            name = name,
            artists = artists,
            coverUrl = coverUrl,
            platform = platform,
            addedTime = addedTime
        )
    }

    private fun PlaylistSongBackupItem.toPlaylistSongEntity(): PlaylistSongEntity {
        return PlaylistSongEntity(
            id = id,
            name = name,
            artists = artists,
            coverUrl = coverUrl,
            platform = platform,
            addedTime = addedTime
        )
    }

    private fun isLocalSong(songId: String, platform: String): Boolean {
        return platform.equals("LOCAL", ignoreCase = true) || songId.startsWith("local_")
    }

    suspend fun syncCoversAfterImport() {
        if (!coverSyncRunning.compareAndSet(false, true)) {
            Log.d(TAG, "导入后补图任务已在执行，跳过重复触发")
            return
        }
        try {
            val localTargets = mutableMapOf<String, LocalSongTarget>()
            favoriteSongDao.getAllFavoriteSongsSync().forEach { song ->
                if (isLocalSong(song.id, song.platform)) {
                    localTargets[song.id] = LocalSongTarget(song.id, song.name, song.artists)
                }
            }
            playlistSongEntityDao.getAllSongsSync().forEach { song ->
                if (isLocalSong(song.id, song.platform)) {
                    localTargets[song.id] = LocalSongTarget(song.id, song.name, song.artists)
                }
            }

            val localCoverMap = buildLocalCoverMap(localTargets)
            if (localCoverMap.isNotEmpty()) {
                favoriteRepository.backfillLocalSongCovers(localCoverMap)
                playlistRepository.backfillLocalSongCovers(localCoverMap)
            }

            favoriteRepository.refreshFavoriteCovers()
            val playlists = playlistRepository.getAllPlaylistsSync()
            playlists.forEach { playlist ->
                playlistRepository.refreshPlaylistCovers(playlist.id)
                playlistRepository.updatePlaylistCoverToLatest(playlist.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "导入后自动补图失败", e)
        } finally {
            coverSyncRunning.set(false)
        }
    }

    private suspend fun buildLocalCoverMap(localTargets: Map<String, LocalSongTarget>): Map<String, String> {
        if (localTargets.isEmpty()) return emptyMap()

        val localMusicList = localMusicInfoRepository.getAllCachedMusic()
        if (localMusicList.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, String>()
        localTargets.values.forEach { target ->
            val matchedMusic = findMatchedLocalMusic(localMusicList, target) ?: return@forEach

            val resolvedInfo = localMusicInfoRepository.getLocalMusicInfo(
                localMusic = matchedMusic,
                forceRefresh = false
            )

            val cover = resolvedInfo?.coverUrl ?: matchedMusic.coverUri
            if (!cover.isNullOrBlank()) {
                result[target.id] = cover
                result[buildNameArtistKey(target.name, target.artists)] = cover
            }
        }
        return result
    }

    private fun findMatchedLocalMusic(
        localMusicList: List<com.tacke.music.ui.LocalMusic>,
        target: LocalSongTarget
    ): com.tacke.music.ui.LocalMusic? {
        val hash = target.id.removePrefix("local_").toIntOrNull()
        if (hash != null) {
            localMusicList.firstOrNull { it.path.hashCode() == hash }?.let { return it }
        }

        val normalizedName = normalizeForMatch(target.name)
        if (normalizedName.isEmpty()) return null

        val normalizedArtist = normalizeForMatch(target.artists)
        val titleMatched = localMusicList.filter { isTitleMatched(normalizedName, normalizeForMatch(it.title)) }
        if (titleMatched.isEmpty()) return null

        val artistMatched = titleMatched.firstOrNull { local ->
            isArtistMatched(normalizedArtist, normalizeForMatch(local.artist))
        }
        if (artistMatched != null) return artistMatched

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

    private fun buildNameArtistKey(name: String, artists: String): String {
        return "name_artist:${normalizeForMatch(name)}|${normalizeForMatch(artists)}"
    }

    private fun normalizeForMatch(value: String?): String {
        return value.orEmpty()
            .replace(Regex("[\\(（\\[【].*?[\\)）\\]】]"), "")
            .lowercase()
            .replace(Regex("[\\s\\p{P}\\p{S}]"), "")
    }

    private data class LocalSongTarget(
        val id: String,
        val name: String,
        val artists: String
    )
}
