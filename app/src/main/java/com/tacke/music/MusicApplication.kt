package com.tacke.music

import android.app.Application
import android.content.Context
import com.tacke.music.data.repository.CachedMusicRepository
import com.tacke.music.util.AppLogger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MusicApplication : Application() {

    companion object {
        lateinit var context: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext

        // 初始化日志系统
        initLogger()

        // 记录应用启动日志
        AppLogger.i("MusicApplication", "应用启动")

        // 清理过期缓存
        cleanExpiredCache()
    }

    /**
     * 清理过期的歌曲详情缓存
     */
    private fun cleanExpiredCache() {
        GlobalScope.launch {
            try {
                val cachedMusicRepository = CachedMusicRepository(context)
                cachedMusicRepository.cleanExpiredCache()
                AppLogger.d("MusicApplication", "过期缓存清理完成")
            } catch (e: Exception) {
                AppLogger.e("MusicApplication", "清理过期缓存失败", e)
            }
        }
    }

    private fun initLogger() {
        // 初始化 AppLogger（单例模式，会自动创建）
        val logger = AppLogger.getInstance(this)

        // 设置日志级别（DEBUG 级别会记录 DEBUG 及以上级别的日志）
        // 可选值：VERBOSE, DEBUG, INFO, WARN, ERROR
        logger.setLogLevel(AppLogger.LEVEL_DEBUG)

        AppLogger.d("MusicApplication", "日志系统初始化完成")
    }
}
