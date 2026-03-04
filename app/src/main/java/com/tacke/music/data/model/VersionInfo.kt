package com.tacke.music.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VersionInfo(
    val versionCode: Int,
    val versionName: String,
    @SerialName("download_url")
    val downloadUrl: String,
    @SerialName("file_size")
    val fileSize: String,
    @SerialName("release_notes")
    val releaseNotes: String,
    @SerialName("publish_time")
    val publishTime: String
)

@Serializable
data class VersionResponse(
    val status: Int,
    val data: VersionInfo
)