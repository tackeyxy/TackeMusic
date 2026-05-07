package com.tacke.music.recognition

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tacke.music.MusicApplication
import com.tacke.music.recognition.api.DoresoApi
import com.tacke.music.recognition.api.DoresoTrack
import com.tacke.music.recognition.api.DoresoUploadResponse
import com.tacke.music.recognition.audio.AudioCaptureManager
import com.tacke.music.recognition.audio.AudioFileDecoder
import com.tacke.music.util.NetworkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Doreso/aha-music 听歌识曲识别器
 * 支持录音识别和文件识别
 */
class DoresoRecognizer(private val context: Context) {

    companion object {
        const val TAG = "DoresoRecognizer"
        const val BASE_URL = "https://api.doreso.com/"
        const val AHA_MUSIC_URL = "https://aha-music.com/"

        // 支持的音频MIME类型（常用音乐格式）
        val SUPPORTED_AUDIO_TYPES = listOf(
            // MP3
            "audio/mpeg",
            "audio/mp3",
            // M4A/AAC
            "audio/mp4",
            "audio/x-m4a",
            "audio/aac",
            "audio/x-aac",
            // FLAC
            "audio/flac",
            "audio/x-flac",
            // WAV
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            // OGG
            "audio/ogg",
            "audio/x-ogg",
            "audio/vorbis",
            "audio/x-vorbis",
            // OPUS
            "audio/opus",
            // WMA
            "audio/wma",
            "audio/x-ms-wma",
            "audio/x-wma",
            // AIFF
            "audio/aiff",
            "audio/x-aiff",
            // AU/SND
            "audio/basic",
            "audio/au",
            "audio/x-au",
            "audio/snd",
            // MKA
            "audio/x-matroska",
            "audio/webm"
        )

        // 支持的文件扩展名
        val SUPPORTED_EXTENSIONS = setOf(
            "mp3", "mp4", "m4a", "aac", "flac", "wav", "wave",
            "ogg", "oga", "opus", "wma", "aiff", "aif", "au", "snd", "mka", "webm"
        )

        /**
         * 检查MIME类型是否受支持
         */
        fun isSupportedMimeType(mimeType: String?): Boolean {
            if (mimeType.isNullOrEmpty()) return false
            return SUPPORTED_AUDIO_TYPES.any { mimeType.equals(it, ignoreCase = true) } ||
                   mimeType.startsWith("audio/", ignoreCase = true)
        }

        /**
         * 检查文件扩展名是否受支持
         */
        fun isSupportedExtension(extension: String?): Boolean {
            if (extension.isNullOrEmpty()) return false
            return SUPPORTED_EXTENSIONS.contains(extension.lowercase())
        }
    }

    private val networkLogger: NetworkLogger by lazy {
        NetworkLogger.getInstance(MusicApplication.context)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(networkLogger)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val doresoApi: DoresoApi = retrofit.create(DoresoApi::class.java)
    private val audioCaptureManager = AudioCaptureManager()
    private val audioFileDecoder = AudioFileDecoder()

    private var isRecognizing = false

    /**
     * 录音识别
     * @param onProgress 进度回调 (0-100)
     * @return 识别结果
     */
    suspend fun recognizeFromRecording(
        onProgress: ((Int) -> Unit)? = null
    ): RecognitionResult = withContext(Dispatchers.IO) {
        if (isRecognizing) {
            Log.w(TAG, "识别正在进行中，无法开始新识别")
            return@withContext RecognitionResult.Error("识别正在进行中")
        }

        isRecognizing = true
        var tempFile: File? = null

        try {
            Log.d(TAG, "开始录音识别流程")
            onProgress?.invoke(10)

            // 1. 录制音频（录制约10秒用于识别）
            Log.d(TAG, "步骤1: 开始录音...")
            onProgress?.invoke(20)

            val recordingResult = audioCaptureManager.startRecording { progress ->
                onProgress?.invoke(20 + (progress * 0.3).toInt())
            }

            if (recordingResult == null) {
                Log.e(TAG, "录音失败，返回 null")
                return@withContext RecognitionResult.Error("录音失败")
            }

            val (pcmData, sampleRate) = recordingResult
            onProgress?.invoke(50)
            Log.d(TAG, "录音完成，样本数: ${pcmData.size}, 采样率: $sampleRate")

            // 检查音频有效性
            if (!audioCaptureManager.hasValidAudio(pcmData)) {
                Log.w(TAG, "音频有效性检查失败")
                return@withContext RecognitionResult.Error("未能检测到有效的音频输入，请确保环境有音乐播放")
            }

            // 2. 将PCM数据转换为WAV文件
            Log.d(TAG, "步骤2: 转换音频格式...")
            onProgress?.invoke(60)

            tempFile = convertPcmToWavFile(pcmData, sampleRate)
            if (tempFile == null) {
                return@withContext RecognitionResult.Error("音频格式转换失败")
            }

            onProgress?.invoke(70)

            // 3. 上传到Doreso API
            Log.d(TAG, "步骤3: 上传音频到Doreso...")
            onProgress?.invoke(80)

            val result = uploadAndRecognize(tempFile, onProgress)

            // 清理临时文件
            tempFile.delete()

            result

        } catch (e: Exception) {
            Log.e(TAG, "录音识别过程发生异常", e)
            RecognitionResult.Error(e.message ?: "未知错误")
        } finally {
            isRecognizing = false
            tempFile?.delete()
            Log.d(TAG, "录音识别流程结束")
        }
    }

    /**
     * 从音频文件识别
     * @param uri 音频文件 URI
     * @param onProgress 进度回调 (0-100)
     * @return 识别结果
     */
    suspend fun recognizeFromFile(
        uri: Uri,
        onProgress: ((Int) -> Unit)? = null
    ): RecognitionResult = withContext(Dispatchers.IO) {
        if (isRecognizing) {
            return@withContext RecognitionResult.Error("识别正在进行中")
        }

        isRecognizing = true
        var tempFile: File? = null

        try {
            Log.d(TAG, "开始文件识别: $uri")
            onProgress?.invoke(10)

            // 1. 获取文件信息并复制到临时文件
            Log.d(TAG, "步骤1: 准备音频文件...")
            onProgress?.invoke(30)

            tempFile = copyUriToTempFile(uri)
            if (tempFile == null) {
                return@withContext RecognitionResult.Error("无法读取音频文件")
            }

            onProgress?.invoke(50)

            // 2. 上传到Doreso API
            Log.d(TAG, "步骤2: 上传音频到Doreso...")
            onProgress?.invoke(70)

            val result = uploadAndRecognize(tempFile, onProgress)

            // 清理临时文件
            tempFile.delete()

            result

        } catch (e: Exception) {
            Log.e(TAG, "文件识别失败", e)
            RecognitionResult.Error(e.message ?: "未知错误")
        } finally {
            isRecognizing = false
            tempFile?.delete()
            Log.d(TAG, "文件识别流程结束")
        }
    }

    /**
     * 上传并识别音频文件
     */
    private suspend fun uploadAndRecognize(
        file: File,
        onProgress: ((Int) -> Unit)? = null
    ): RecognitionResult = withContext(Dispatchers.IO) {
        try {
            // 1. 上传文件
            val requestFile = file.asRequestBody("audio/mp4".toMediaTypeOrNull())
            val multipartBody = MultipartBody.Part.createFormData(
                "file",
                file.name,
                requestFile
            )

            Log.d(TAG, "上传文件: ${file.name}, 大小: ${file.length()} bytes")

            val uploadResponse = doresoApi.uploadAudio(multipartBody)

            if (!uploadResponse.isSuccessful) {
                Log.e(TAG, "上传失败: ${uploadResponse.code()}")
                return@withContext RecognitionResult.Error("上传失败: ${uploadResponse.code()}")
            }

            val uploadBody = uploadResponse.body()
            val musicId = uploadBody?.data?.id

            if (musicId.isNullOrEmpty()) {
                Log.e(TAG, "上传响应中没有music_id")
                return@withContext RecognitionResult.Error("上传响应无效")
            }

            Log.d(TAG, "上传成功, musicId: $musicId")
            onProgress?.invoke(85)

            // 2. 等待并获取识别结果（可能需要轮询）
            Log.d(TAG, "步骤3: 获取识别结果...")

            // 轮询获取结果
            var attempts = 0
            val maxAttempts = 10
            val delayMs = 1000L

            while (attempts < maxAttempts) {
                val resultResponse = doresoApi.getRecognitionResult(
                    musicId = musicId,
                    referer = "$AHA_MUSIC_URL"
                )

                if (resultResponse.isSuccessful) {
                    val resultBody = resultResponse.body()
                    val track = parseResult(resultBody)

                    if (track != null) {
                        Log.d(TAG, "识别成功: ${track.title} - ${track.artist}")
                        onProgress?.invoke(100)
                        return@withContext RecognitionResult.Success(track)
                    }
                }

                attempts++
                if (attempts < maxAttempts) {
                    Log.d(TAG, "识别结果未就绪，等待 ${delayMs}ms 后重试 (${attempts}/${maxAttempts})")
                    delay(delayMs)
                }
            }

            Log.d(TAG, "识别未找到结果")
            RecognitionResult.NotFound

        } catch (e: Exception) {
            Log.e(TAG, "上传或识别过程出错", e)
            RecognitionResult.Error(e.message ?: "识别过程出错")
        }
    }

    /**
     * 解析识别结果
     */
    private fun parseResult(response: com.tacke.music.recognition.api.DoresoRecognitionResponse?): DoresoTrack? {
        if (response == null) return null

        return try {
            val dataList = response.data
            if (dataList.isNullOrEmpty()) return null

            val firstItem = dataList[0]
            val results = firstItem.results
            val musicList = results?.music

            if (musicList.isNullOrEmpty()) return null

            val musicResult = musicList[0].result
            val title = musicResult?.title ?: return null
            val artists = musicResult.artists

            val artistName = if (!artists.isNullOrEmpty()) {
                artists.mapNotNull { it.name }.joinToString(", ")
            } else {
                "未知歌手"
            }

            DoresoTrack(
                title = title,
                artist = artistName,
                album = "未知专辑",
                coverUrl = null,
                score = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析识别结果出错", e)
            null
        }
    }

    /**
     * 将PCM数据转换为WAV文件
     */
    private fun convertPcmToWavFile(pcmData: ShortArray, sampleRate: Int): File? {
        return try {
            val tempFile = File.createTempFile("recording_", ".wav", context.cacheDir)
            FileOutputStream(tempFile).use { outputStream ->
                // 写入WAV文件头
                val byteRate = sampleRate * 2 // 16-bit mono
                val totalDataLen = pcmData.size * 2 + 36
                val totalAudioLen = pcmData.size * 2

                // RIFF chunk
                outputStream.writeBytes("RIFF")
                outputStream.writeInt(totalDataLen)
                outputStream.writeBytes("WAVE")

                // fmt chunk
                outputStream.writeBytes("fmt ")
                outputStream.writeInt(16) // Subchunk1Size
                outputStream.writeShort(1) // AudioFormat (PCM)
                outputStream.writeShort(1) // NumChannels (mono)
                outputStream.writeInt(sampleRate)
                outputStream.writeInt(byteRate)
                outputStream.writeShort(2) // BlockAlign
                outputStream.writeShort(16) // BitsPerSample

                // data chunk
                outputStream.writeBytes("data")
                outputStream.writeInt(totalAudioLen)

                // 写入PCM数据
                for (sample in pcmData) {
                    outputStream.writeShort(sample.toInt())
                }
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "PCM转WAV失败", e)
            null
        }
    }

    /**
     * 将URI复制到临时文件
     */
    private fun copyUriToTempFile(uri: Uri): File? {
        return try {
            val mimeType = context.contentResolver.getType(uri) ?: "audio/mpeg"
            val extension = getExtensionFromMimeType(mimeType)

            val tempFile = File.createTempFile("audio_", extension, context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "复制文件失败", e)
            null
        }
    }

    /**
     * 根据MIME类型获取文件扩展名
     */
    private fun getExtensionFromMimeType(mimeType: String): String {
        return when {
            // MP3
            mimeType.contains("mpeg") || mimeType.contains("mp3") -> ".mp3"
            // M4A/AAC
            mimeType.contains("mp4") -> ".m4a"
            mimeType.contains("m4a") -> ".m4a"
            mimeType.contains("aac") -> ".aac"
            // FLAC
            mimeType.contains("flac") -> ".flac"
            // WAV
            mimeType.contains("wav") || mimeType.contains("wave") -> ".wav"
            // OGG/Vorbis
            mimeType.contains("ogg") -> ".ogg"
            mimeType.contains("vorbis") -> ".ogg"
            // OPUS
            mimeType.contains("opus") -> ".opus"
            // WMA
            mimeType.contains("wma") || mimeType.contains("ms-wma") -> ".wma"
            // AIFF
            mimeType.contains("aiff") || mimeType.contains("aif") -> ".aiff"
            // AU/SND
            mimeType.contains("basic") || mimeType.contains("au") || mimeType.contains("snd") -> ".au"
            // MKA/WebM
            mimeType.contains("matroska") || mimeType.contains("webm") -> ".mka"
            // 默认
            else -> ".mp3"
        }
    }

    /**
     * 取消识别
     */
    fun cancelRecognition() {
        audioCaptureManager.stopRecording()
        isRecognizing = false
    }

    /**
     * 检查是否正在识别
     */
    fun isRecognizing(): Boolean = isRecognizing

    /**
     * 识别结果密封类
     */
    sealed class RecognitionResult {
        data class Success(val track: DoresoTrack) : RecognitionResult()
        data object NotFound : RecognitionResult()
        data class Error(val message: String) : RecognitionResult()
    }

    // 辅助函数：写入字节
    private fun java.io.OutputStream.writeBytes(str: String) {
        write(str.toByteArray(Charsets.US_ASCII))
    }

    // 辅助函数：写入Int（小端序）
    private fun java.io.OutputStream.writeInt(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    // 辅助函数：写入Short（小端序）
    private fun java.io.OutputStream.writeShort(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
