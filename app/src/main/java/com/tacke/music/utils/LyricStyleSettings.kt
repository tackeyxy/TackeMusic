package com.tacke.music.utils

import android.content.Context

object LyricStyleSettings {
    const val PREFS_NAME = "lyric_settings"

    const val KEY_FLOATING_LYRIC_COLOR = "floating_lyric_color"
    const val KEY_FLOATING_LYRIC_SIZE = "floating_lyric_size"
    const val KEY_PLAYER_LYRIC_COLOR = "player_lyric_color"
    const val KEY_PLAYER_LYRIC_SIZE = "player_lyric_size"
    const val KEY_FULLSCREEN_LYRIC_COLOR = "fullscreen_lyric_color"
    const val KEY_FULLSCREEN_LYRIC_SIZE = "fullscreen_lyric_size"

    const val DEFAULT_LYRIC_COLOR = 0xFF00CED1.toInt()
    const val DEFAULT_FLOATING_LYRIC_SIZE = 20f
    const val DEFAULT_PLAYER_LYRIC_SIZE = 18f
    const val DEFAULT_FULLSCREEN_LYRIC_SIZE = 20f

    const val MIN_LYRIC_SIZE = 12f
    const val MAX_LYRIC_SIZE = 32f
    const val LYRIC_SIZE_STEP = 2f

    val PRESET_FLOATING_LYRIC_COLORS = listOf(
        0xFF00CED1.toInt(),
        0xFFFF4081.toInt(),
        0xFF4CAF50.toInt(),
        0xFFFF9800.toInt(),
        0xFF9C27B0.toInt(),
        0xFF2196F3.toInt(),
        0xFFFFEB3B.toInt(),
        0xFFF44336.toInt(),
        0xFF00BCD4.toInt(),
        0xFFE91E63.toInt(),
        0xFF8BC34A.toInt()
    )

    val PRESET_PLAYER_LYRIC_COLORS = listOf(
        0xFF00CED1.toInt(),
        0xFFFF4081.toInt(),
        0xFF4CAF50.toInt(),
        0xFFFF9800.toInt(),
        0xFF9C27B0.toInt(),
        0xFF2196F3.toInt(),
        0xFFFFEB3B.toInt(),
        0xFFF44336.toInt(),
        0xFF00BCD4.toInt(),
        0xFFE91E63.toInt(),
        0xFF8BC34A.toInt()
    )

    val PRESET_FULLSCREEN_LYRIC_COLORS = PRESET_PLAYER_LYRIC_COLORS

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getColor(context: Context, key: String, defaultColor: Int): Int {
        return prefs(context).getInt(key, defaultColor)
    }

    private fun setColor(context: Context, key: String, color: Int) {
        prefs(context).edit().putInt(key, color).apply()
    }

    private fun getSize(context: Context, key: String, defaultSize: Float): Float {
        return prefs(context).getFloat(key, defaultSize).coerceIn(MIN_LYRIC_SIZE, MAX_LYRIC_SIZE)
    }

    private fun setSize(context: Context, key: String, size: Float) {
        prefs(context).edit().putFloat(key, size.coerceIn(MIN_LYRIC_SIZE, MAX_LYRIC_SIZE)).apply()
    }

    fun getFloatingLyricColor(context: Context): Int =
        getColor(context, KEY_FLOATING_LYRIC_COLOR, DEFAULT_LYRIC_COLOR)

    fun setFloatingLyricColor(context: Context, color: Int) =
        setColor(context, KEY_FLOATING_LYRIC_COLOR, color)

    fun getFloatingLyricSize(context: Context): Float =
        getSize(context, KEY_FLOATING_LYRIC_SIZE, DEFAULT_FLOATING_LYRIC_SIZE)

    fun setFloatingLyricSize(context: Context, size: Float) =
        setSize(context, KEY_FLOATING_LYRIC_SIZE, size)

    fun getPlayerLyricColor(context: Context): Int =
        getColor(context, KEY_PLAYER_LYRIC_COLOR, DEFAULT_LYRIC_COLOR)

    fun setPlayerLyricColor(context: Context, color: Int) =
        setColor(context, KEY_PLAYER_LYRIC_COLOR, color)

    fun getPlayerLyricSize(context: Context): Float =
        getSize(context, KEY_PLAYER_LYRIC_SIZE, DEFAULT_PLAYER_LYRIC_SIZE)

    fun setPlayerLyricSize(context: Context, size: Float) =
        setSize(context, KEY_PLAYER_LYRIC_SIZE, size)

    fun getFullscreenLyricColor(context: Context): Int =
        getColor(context, KEY_FULLSCREEN_LYRIC_COLOR, DEFAULT_LYRIC_COLOR)

    fun setFullscreenLyricColor(context: Context, color: Int) =
        setColor(context, KEY_FULLSCREEN_LYRIC_COLOR, color)

    fun getFullscreenLyricSize(context: Context): Float =
        getSize(context, KEY_FULLSCREEN_LYRIC_SIZE, DEFAULT_FULLSCREEN_LYRIC_SIZE)

    fun setFullscreenLyricSize(context: Context, size: Float) =
        setSize(context, KEY_FULLSCREEN_LYRIC_SIZE, size)
}
