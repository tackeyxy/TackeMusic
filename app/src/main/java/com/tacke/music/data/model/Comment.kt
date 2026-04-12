package com.tacke.music.data.model

data class Comment(
    val id: String,
    val content: String?,
    val userName: String?,
    val userAvatar: String?,
    val time: Any?, // 可以是Long（时间戳）或String（格式化后的时间）
    val likeCount: Int?
)