package com.tacke.music.data.model

data class Playlist(
    val id: String,
    val name: String,
    val description: String = "",
    val coverUrl: String? = null, // 歌单封面（最晚加入的歌曲封面）
    val iconColor: String? = null, // 歌单图标随机颜色
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
    val songCount: Int = 0
) {
    /**
     * 获取最终显示的封面路径
     * @return 封面路径（本地文件路径或网络URL），null表示使用默认封面
     */
    fun getDisplayCoverPath(): String? {
        return coverUrl
    }

    /**
     * 获取图标背景颜色
     * @return 颜色代码，如果没有则返回null
     */
    fun getIconBackgroundColor(): String? {
        return iconColor
    }

    /**
     * 检查是否有有效的封面
     * @return true表示有封面可显示
     */
    fun hasValidCover(): Boolean {
        return !coverUrl.isNullOrEmpty()
    }
}
