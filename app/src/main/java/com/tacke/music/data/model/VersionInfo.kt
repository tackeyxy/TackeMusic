package com.tacke.music.data.model

import kotlinx.serialization.Serializable

@Serializable
data class VersionInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val fileSize: String,
    val releaseNotes: String,
    val publishTime: String
)

@Serializable
data class VersionResponse(
    val status: Int,
    val data: VersionInfo
)