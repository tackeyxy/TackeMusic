package com.tacke.music.data.api

import com.tacke.music.data.model.ParseResponse
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

data class ParseRequest(
    val platform: String,
    val ids: String,
    val quality: String
)

interface TunefreeApi {
    
    @Headers(
        "user-agent: Dart/3.9 (dart:io)",
        "content-type: application/json",
        "accept-encoding: gzip"
    )
    @POST("tunefree/parse")
    suspend fun parseSong(@Body request: ParseRequest): ParseResponse
}
