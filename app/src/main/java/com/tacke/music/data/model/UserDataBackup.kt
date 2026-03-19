package com.tacke.music.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserDataBackup(
    val backupVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val favorites: List<FavoriteSongBackupItem> = emptyList(),
    val playlists: List<PlaylistBackupItem> = emptyList()
)

@Serializable
data class FavoriteSongBackupItem(
    val id: String,
    val name: String,
    val artists: String,
    val coverUrl: String? = null,
    val platform: String,
    val addedTime: Long = System.currentTimeMillis()
)

@Serializable
data class PlaylistBackupItem(
    val id: String,
    val name: String,
    val description: String = "",
    val coverUrl: String? = null,
    val iconColor: String? = null,
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
    val songs: List<PlaylistSongBackupItem> = emptyList()
)

@Serializable
data class PlaylistSongBackupItem(
    val id: String,
    val name: String,
    val artists: String,
    val coverUrl: String? = null,
    val platform: String,
    val addedTime: Long = System.currentTimeMillis(),
    val orderIndex: Int = 0
)

data class BackupImportResult(
    val favoriteCount: Int,
    val playlistCount: Int,
    val playlistSongCount: Int
)
