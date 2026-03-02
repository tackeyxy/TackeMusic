package com.tacke.music.data.api

import com.tacke.music.data.model.ChartResponse
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

interface ChartApi {

    @Headers(
        "accept: */*",
        "accept-language: zh-CN,zh;q=0.9",
        "priority: u=1, i",
        "referer: https://music.xcloudv.top/",
        "sec-ch-ua: \"Not:A-Brand\";v=\"99\", \"Google Chrome\";v=\"145\", \"Chromium\";v=\"145\"",
        "sec-ch-ua-mobile: ?0",
        "sec-ch-ua-platform: \"Windows\"",
        "sec-fetch-dest: empty",
        "sec-fetch-mode: cors",
        "sec-fetch-site: same-origin",
        "user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    )
    @GET("php/toplist.php")
    suspend fun getChartList(
        @Query("type") type: String
    ): ChartResponse
}

enum class ChartType(val value: String) {
    SOARING("soaring"),
    NEW("new"),
    ORIGINAL("original"),
    HOT("hot")
}
