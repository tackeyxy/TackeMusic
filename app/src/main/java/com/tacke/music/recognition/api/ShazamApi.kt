package com.tacke.music.recognition.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface ShazamApi {

    @Headers(
        "X-Shazam-Platform: IPHONE",
        "X-Shazam-AppVersion: 14.1.0",
        "Accept: */*",
        "Accept-Encoding: gzip, deflate",
        "User-Agent: Shazam/3685 CFNetwork/1197 Darwin/20.0.0",
        "Content-Type: application/json"
    )
    @POST("discovery/v5/{lang}/{region}/iphone/-/tag/{uuidA}/{uuidB}")
    suspend fun recognize(
        @Path("lang") lang: String,
        @Path("region") region: String,
        @Path("uuidA") uuidA: String,
        @Path("uuidB") uuidB: String,
        @QueryMap params: Map<String, String>,
        @Header("Accept-Language") acceptLanguage: String,
        @Body request: ShazamRecognitionRequest
    ): ShazamRecognitionResponse
}

data class ShazamRecognitionRequest(
    val timezone: String = "Asia/Shanghai",
    val signature: SignatureData,
    val timestamp: Long = System.currentTimeMillis(),
    val context: Map<String, Any> = emptyMap(),
    val geolocation: Map<String, Any> = emptyMap()
)

data class SignatureData(
    val uri: String,
    val samplems: Int
)

data class ShazamRecognitionResponse(
    val matches: List<ShazamMatch>?,
    val track: ShazamTrack?,  // 有些版本直接返回track
    val timestamp: Long?,
    val tagid: String?
)

data class ShazamMatch(
    val id: String?,
    val offset: Double?,
    val channel: String?,
    val timeskew: Double?,
    val frequencyskew: Double?,
    val track: ShazamTrack?
)

data class ShazamTrack(
    val key: String?,
    val title: String?,
    val subtitle: String?,
    val share: ShazamShare?,
    val images: ShazamImages?,
    val hub: ShazamHub?,
    val artists: List<ShazamArtist>?,
    val sections: List<ShazamSection>?
)

data class ShazamShare(
    val subject: String?,
    val text: String?,
    val href: String?,
    val image: String?,
    val twitter: String?,
    val html: String?,
    val avatar: String?,
    val snapchat: String?
)

data class ShazamImages(
    val background: String?,
    val coverart: String?,
    @SerializedName("coverarthq") val coverArtHq: String?,
    val joecolor: String?
)

data class ShazamHub(
    val type: String?,
    val image: String?,
    val actions: List<ShazamAction>?,
    val options: List<ShazamOption>?,
    val providers: List<ShazamProvider>?,
    val explicit: Boolean?,
    val displayname: String?
)

data class ShazamAction(
    val name: String?,
    val type: String?,
    val id: String?,
    val uri: String?
)

data class ShazamOption(
    val caption: String?,
    val actions: List<ShazamAction>?,
    val beacondata: ShazamBeaconData?,
    val image: String?,
    val type: String?,
    val listcaption: String?,
    val overflowimage: String?,
    val colouroverflowimage: Boolean?,
    val providername: String?
)

data class ShazamBeaconData(
    val type: String?,
    val providername: String?
)

data class ShazamProvider(
    val caption: String?,
    val images: ShazamProviderImages?,
    val actions: List<ShazamAction>?,
    val type: String?
)

data class ShazamProviderImages(
    val overflow: String?,
    val default: String?
)

data class ShazamArtist(
    val id: String?,
    val adamid: String?,
    val name: String?
)

data class ShazamSection(
    val type: String?,
    val metapages: List<ShazamMetaPage>?,
    val tabname: String?,
    val metadata: List<ShazamMetadata>?
)

data class ShazamMetaPage(
    val caption: String?,
    val image: String?
)

data class ShazamMetadata(
    val title: String?,
    val text: String?
)
