package com.tacke.music.data.api

import com.tacke.music.data.model.VersionResponse
import retrofit2.http.GET

interface VersionApi {
    @GET("tackeyxy/TackeMusic/blob/master/version.json")
    suspend fun checkVersion(): VersionResponse
}