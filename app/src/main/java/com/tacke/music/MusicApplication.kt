package com.tacke.music

import android.app.Application
import android.content.Context
import com.tacke.music.util.AppLogger

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
