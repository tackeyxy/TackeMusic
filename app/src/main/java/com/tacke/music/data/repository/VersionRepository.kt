package com.tacke.music.data.repository

import android.content.Context
import com.tacke.music.data.model.VersionInfo
import com.tacke.music.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class VersionRepository(private val context: Context) {

    companion object {
        private const val TAG = "VersionRepository"
        // 默认使用稳定版 URL
        private const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/tackeyxy/TackeMusic/main/version.json"
        private const val PREFS_NAME = "version_preferences"
        private const val KEY_IGNORED_VERSION_CODE = "ignored_version_code"

        @Volatile
        private var instance: VersionRepository? = null

        fun getInstance(context: Context): VersionRepository {
            return instance ?: synchronized(this) {
                instance ?: VersionRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 动态版本检查 URL
    private var versionUrl: String = DEFAULT_VERSION_URL

    /**
     * 设置版本检查 URL
     * @param url 版本检查 URL
     */
    fun setVersionUrl(url: String) {
        versionUrl = url
        AppLogger.d(TAG, "Version URL set to: $url")
    }

    /**
     * 检查版本更新
     * @param currentVersionCode 当前APP版本号
     * @return 如果有新版本返回VersionInfo，否则返回null
     */
    suspend fun checkForUpdate(currentVersionCode: Int): Result<VersionInfo?> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "Checking for update, current version: $currentVersionCode")

            val request = Request.Builder()
                .url(versionUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; TackeMusic)")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                AppLogger.e(TAG, "Update check failed: HTTP ${response.code}")
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response"))

            AppLogger.d(TAG, "Version response length: ${responseBody.length}")

            val versionResponse = json.decodeFromString<com.tacke.music.data.model.VersionResponse>(responseBody)

            if (versionResponse.status != 200) {
                return@withContext Result.failure(Exception("API error: ${versionResponse.status}"))
            }

            val latestVersion = versionResponse.data

            // 检查是否有新版本
            if (latestVersion.versionCode > currentVersionCode) {
                // 检查是否被忽略
                val ignoredVersionCode = getIgnoredVersionCode()
                if (latestVersion.versionCode <= ignoredVersionCode) {
                    AppLogger.d(TAG, "Version ${latestVersion.versionCode} was ignored")
                    return@withContext Result.success(null)
                }
                return@withContext Result.success(latestVersion)
            }

            Result.success(null)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check version", e)
            Result.failure(e)
        }
    }

    /**
     * 获取被忽略的版本号
     */
    fun getIgnoredVersionCode(): Int {
        return prefs.getInt(KEY_IGNORED_VERSION_CODE, 0)
    }

    /**
     * 设置忽略的版本号
     */
    fun setIgnoredVersionCode(versionCode: Int) {
        prefs.edit().putInt(KEY_IGNORED_VERSION_CODE, versionCode).apply()
        AppLogger.d(TAG, "Ignored version code set to: $versionCode")
    }

    /**
     * 清除忽略的版本记录（用于手动检查时）
     */
    fun clearIgnoredVersion() {
        prefs.edit().remove(KEY_IGNORED_VERSION_CODE).apply()
        AppLogger.d(TAG, "Cleared ignored version")
    }
}