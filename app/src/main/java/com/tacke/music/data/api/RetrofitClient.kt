package com.tacke.music.data.api

import com.tacke.music.MusicApplication
import com.tacke.music.util.NetworkLogger
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // API 基础 URL 配置
    private const val KUWO_SEARCH_BASE_URL = "http://search.kuwo.cn/"
    private const val NETEASE_BASE_URL = "http://interface.music.163.com/"
    private const val CHART_BASE_URL = "https://music.xcloudv.top/"
    private const val NETEASE_PLAYLIST_BASE_URL = "https://music.163.com/"
    // GdStudio API - 用于获取歌曲播放URL
    private const val GDSTUDIO_BASE_URL = "https://music-api.gdstudio.xyz/"
    // Music Search API - 用于搜索本地歌曲信息
    private const val MUSIC_SEARCH_BASE_URL = "https://music-api.gdstudio.xyz/"

    private val networkLogger: NetworkLogger by lazy {
        NetworkLogger.getInstance(MusicApplication.context)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(networkLogger)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val kuwoSearchRetrofit = Retrofit.Builder()
        .baseUrl(KUWO_SEARCH_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val neteaseRetrofit = Retrofit.Builder()
        .baseUrl(NETEASE_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val chartRetrofit = Retrofit.Builder()
        .baseUrl(CHART_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val playlistRetrofit = Retrofit.Builder()
        .baseUrl(NETEASE_PLAYLIST_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val gdStudioRetrofit = Retrofit.Builder()
        .baseUrl(GDSTUDIO_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val musicSearchRetrofit = Retrofit.Builder()
        .baseUrl(MUSIC_SEARCH_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val kuwoApi: KuwoApi = kuwoSearchRetrofit.create(KuwoApi::class.java)
    val neteaseApi: NeteaseApi = neteaseRetrofit.create(NeteaseApi::class.java)
    val chartApi: ChartApi = chartRetrofit.create(ChartApi::class.java)
    val playlistApi: PlaylistApi = playlistRetrofit.create(PlaylistApi::class.java)
    val gdStudioApi: GdStudioApi = gdStudioRetrofit.create(GdStudioApi::class.java)
    val musicSearchApi: MusicSearchApi = musicSearchRetrofit.create(MusicSearchApi::class.java)
}
