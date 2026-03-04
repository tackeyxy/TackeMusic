package com.tacke.music.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.SongDetail
import com.tacke.music.data.model.SongInfo

class PlaybackPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "playback_preferences"

        // 播放状态
        private const val KEY_CURRENT_SONG_ID = "current_song_id"
        private const val KEY_CURRENT_POSITION = "current_position"
        private const val KEY_IS_PLAYING = "is_playing"
        private const val KEY_CURRENT_QUALITY = "current_quality"

        // 播放列表
        private const val KEY_PLAYLIST = "playlist"
        private const val KEY_CURRENT_INDEX = "current_index"

        // 播放模式
        private const val KEY_PLAY_MODE = "play_mode"

        // 歌曲详情缓存（用于恢复播放）
        private const val KEY_SONG_DETAIL_PREFIX = "song_detail_"
        private const val KEY_SONG_DETAIL_URL = "url"
        private const val KEY_SONG_DETAIL_COVER = "cover"
        private const val KEY_SONG_DETAIL_LYRICS = "lyrics"

        // 单例
        @Volatile
        private var instance: PlaybackPreferences? = null

        fun getInstance(context: Context): PlaybackPreferences {
            return instance ?: synchronized(this) {
                instance ?: PlaybackPreferences(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // 当前播放的歌曲ID
    var currentSongId: String?
        get() = prefs.getString(KEY_CURRENT_SONG_ID, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_SONG_ID, value).apply()

    // 当前播放位置（毫秒）
    var currentPosition: Long
        get() = prefs.getLong(KEY_CURRENT_POSITION, 0)
        set(value) = prefs.edit().putLong(KEY_CURRENT_POSITION, value).apply()

    // 是否正在播放
    var isPlaying: Boolean
        get() = prefs.getBoolean(KEY_IS_PLAYING, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PLAYING, value).apply()

    // 当前音质
    var currentQuality: String
        get() = prefs.getString(KEY_CURRENT_QUALITY, "320k") ?: "320k"
        set(value) = prefs.edit().putString(KEY_CURRENT_QUALITY, value).apply()

    // 当前播放索引
    var currentIndex: Int
        get() = prefs.getInt(KEY_CURRENT_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_CURRENT_INDEX, value).apply()

    // 播放模式: 0-顺序播放, 1-随机播放, 2-列表循环, 3-单曲循环
    var playMode: Int
        get() = prefs.getInt(KEY_PLAY_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_PLAY_MODE, value).apply()

    // 保存播放列表
    fun savePlaylist(playlist: List<PlaylistSong>) {
        val json = PlaylistSong.listToJson(playlist)
        prefs.edit().putString(KEY_PLAYLIST, json).apply()
    }

    // 获取播放列表
    fun getPlaylist(): List<PlaylistSong> {
        val json = prefs.getString(KEY_PLAYLIST, null) ?: return emptyList()
        return PlaylistSong.listFromJson(json)
    }

    // 清除所有播放状态
    fun clearPlaybackState() {
        prefs.edit().apply {
            remove(KEY_CURRENT_SONG_ID)
            remove(KEY_CURRENT_POSITION)
            remove(KEY_IS_PLAYING)
            remove(KEY_CURRENT_QUALITY)
            remove(KEY_PLAYLIST)
            remove(KEY_CURRENT_INDEX)
            apply()
        }
    }

    // 保存当前播放状态
    fun savePlaybackState(
        songId: String?,
        position: Long,
        playing: Boolean,
        quality: String = "320k",
        playlist: List<PlaylistSong> = emptyList(),
        index: Int = 0
    ) {
        prefs.edit().apply {
            putString(KEY_CURRENT_SONG_ID, songId)
            putLong(KEY_CURRENT_POSITION, position)
            putBoolean(KEY_IS_PLAYING, playing)
            putString(KEY_CURRENT_QUALITY, quality)
            putInt(KEY_CURRENT_INDEX, index)
            apply()
        }
        savePlaylist(playlist)
    }

    // 保存歌曲详情（用于恢复播放）
    fun saveSongDetail(songId: String, detail: SongDetail) {
        prefs.edit().apply {
            putString("${KEY_SONG_DETAIL_PREFIX}${songId}_$KEY_SONG_DETAIL_URL", detail.url)
            putString("${KEY_SONG_DETAIL_PREFIX}${songId}_$KEY_SONG_DETAIL_COVER", detail.cover)
            putString("${KEY_SONG_DETAIL_PREFIX}${songId}_$KEY_SONG_DETAIL_LYRICS", detail.lyrics)
            putString("${KEY_SONG_DETAIL_PREFIX}${songId}_info_name", detail.info.name)
            putString("${KEY_SONG_DETAIL_PREFIX}${songId}_info_artist", detail.info.artist)
            apply()
        }
    }

    // 获取歌曲详情（用于恢复播放）
    fun getSongDetail(songId: String): SongDetail? {
        val url = prefs.getString("${KEY_SONG_DETAIL_PREFIX}${songId}_$KEY_SONG_DETAIL_URL", null)
        val infoName = prefs.getString("${KEY_SONG_DETAIL_PREFIX}${songId}_info_name", null)
        val infoArtist = prefs.getString("${KEY_SONG_DETAIL_PREFIX}${songId}_info_artist", null)
        return if (url != null && infoName != null && infoArtist != null) {
            SongDetail(
                url = url,
                info = SongInfo(name = infoName, artist = infoArtist),
                cover = prefs.getString("${KEY_SONG_DETAIL_PREFIX}${songId}_$KEY_SONG_DETAIL_COVER", null),
                lyrics = prefs.getString("${KEY_SONG_DETAIL_PREFIX}${songId}_$KEY_SONG_DETAIL_LYRICS", null)
            )
        } else null
    }

    // 清除指定歌曲的详情缓存
    fun clearSongDetail(songId: String) {
        prefs.edit().apply {
            remove("${KEY_SONG_DETAIL_PREFIX}${songId}_$KEY_SONG_DETAIL_URL")
            remove("${KEY_SONG_DETAIL_PREFIX}${songId}_$KEY_SONG_DETAIL_COVER")
            remove("${KEY_SONG_DETAIL_PREFIX}${songId}_$KEY_SONG_DETAIL_LYRICS")
            remove("${KEY_SONG_DETAIL_PREFIX}${songId}_info_name")
            remove("${KEY_SONG_DETAIL_PREFIX}${songId}_info_artist")
            apply()
        }
    }
}
