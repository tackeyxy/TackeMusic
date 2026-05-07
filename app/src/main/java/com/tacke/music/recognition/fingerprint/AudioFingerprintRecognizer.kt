package com.tacke.music.recognition.fingerprint

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 音频指纹识别器 - 基于 Python 项目 api1.py 实现
 */
class AudioFingerprintRecognizer(private val context: Context) {

    companion object {
        const val TAG = "AudioFingerprintRecognizer"
        const val TARGET_SAMPLE_RATE = 8000
        const val DEFAULT_DURATION = 3 // 默认3秒，与Python一致
        const val RECORD_DURATION = 5  // 录音5秒

        val SUPPORTED_AUDIO_TYPES = listOf(
            "audio/mpeg", "audio/mp3",
            "audio/mp4", "audio/x-m4a", "audio/aac",
            "audio/flac", "audio/x-flac",
            "audio/wav", "audio/x-wav", "audio/wave",
            "audio/ogg", "audio/x-ogg", "audio/vorbis",
            "audio/opus"
        )

        val SUPPORTED_EXTENSIONS = setOf(
            "mp3", "mp4", "m4a", "aac", "flac",
            "wav", "wave", "ogg", "oga", "opus"
        )
    }

    private val musicRecognizerApi = MusicRecognizerApi()
    private var isRecognizing = false

    /**
     * 录音识别 - 参考 api1.py record_audio
     */
    suspend fun recognizeFromRecording(
        duration: Int = RECORD_DURATION,
        onProgress: ((Int) -> Unit)? = null
    ): RecognitionResult = withContext(Dispatchers.IO) {
        if (isRecognizing) {
            return@withContext RecognitionResult.Error("识别正在进行中")
        }

        isRecognizing = true

        try {
            Log.d(TAG, "开始录音识别，时长: ${duration}秒")
            onProgress?.invoke(10)

            // 录音 - 参考 api1.py record_audio
            val samples = recordAudio(duration) { progress ->
                onProgress?.invoke(10 + (progress * 0.4).toInt())
            }

            if (samples == null) {
                return@withContext RecognitionResult.Error("录音失败")
            }

            Log.d(TAG, "录音完成: ${samples.size} 样本")
            onProgress?.invoke(50)

            // 生成指纹 - 参考 api1.py generate_fp_with_pythonmonkey
            val fingerprint = generateFingerprint(samples) { progress ->
                onProgress?.invoke(50 + (progress * 0.3).toInt())
            }

            if (fingerprint == null || fingerprint.startsWith("ERROR:")) {
                Log.e(TAG, "指纹生成失败: $fingerprint")
                return@withContext RecognitionResult.Error("指纹生成失败")
            }

            Log.d(TAG, "指纹生成成功，长度: ${fingerprint.length}")
            onProgress?.invoke(80)

            // API识别 - 参考 api1.py recognize_with_api
            val results = musicRecognizerApi.recognize(fingerprint, duration)
            onProgress?.invoke(100)

            if (results.isNullOrEmpty()) {
                Log.d(TAG, "未找到匹配结果")
                return@withContext RecognitionResult.NotFound
            }

            val bestMatch = results.first()
            Log.d(TAG, "识别成功: ${bestMatch.songName} - ${bestMatch.artistName}")
            RecognitionResult.Success(bestMatch)

        } catch (e: Exception) {
            Log.e(TAG, "录音识别失败", e)
            RecognitionResult.Error(e.message ?: "未知错误")
        } finally {
            isRecognizing = false
        }
    }

    /**
     * 文件识别 - 参考 api1.py load_audio
     */
    suspend fun recognizeFromFile(
        uri: Uri,
        duration: Int = DEFAULT_DURATION,
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

            // 复制到临时文件
            tempFile = copyUriToTempFile(uri)
            if (tempFile == null) {
                return@withContext RecognitionResult.Error("无法读取音频文件")
            }

            onProgress?.invoke(20)

            // 加载音频 - 参考 api1.py load_audio
            val samples = loadAudio(tempFile.absolutePath, duration)
            if (samples == null) {
                return@withContext RecognitionResult.Error("音频加载失败")
            }

            Log.d(TAG, "音频加载完成: ${samples.size} 样本")
            onProgress?.invoke(50)

            // 生成指纹
            val fingerprint = generateFingerprint(samples) { progress ->
                onProgress?.invoke(50 + (progress * 0.3).toInt())
            }

            if (fingerprint == null || fingerprint.startsWith("ERROR:")) {
                Log.e(TAG, "指纹生成失败: $fingerprint")
                return@withContext RecognitionResult.Error("指纹生成失败")
            }

            Log.d(TAG, "指纹生成成功，长度: ${fingerprint.length}")
            onProgress?.invoke(80)

            // API识别
            val results = musicRecognizerApi.recognize(fingerprint, duration)
            onProgress?.invoke(100)

            if (results.isNullOrEmpty()) {
                Log.d(TAG, "未找到匹配结果")
                return@withContext RecognitionResult.NotFound
            }

            val bestMatch = results.first()
            Log.d(TAG, "识别成功: ${bestMatch.songName} - ${bestMatch.artistName}")
            RecognitionResult.Success(bestMatch)

        } catch (e: Exception) {
            Log.e(TAG, "文件识别失败", e)
            RecognitionResult.Error(e.message ?: "未知错误")
        } finally {
            isRecognizing = false
            tempFile?.delete()
        }
    }

    /**
     * 加载音频 - 参考 api1.py load_audio
     * 使用 Android MediaExtractor 加载并重采样音频
     */
    private fun loadAudio(audioPath: String, duration: Int): FloatArray? {
        return try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(audioPath)

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex == -1) {
                Log.e(TAG, "No audio track found")
                return null
            }

            val format = extractor.getTrackFormat(audioTrackIndex)
            extractor.selectTrack(audioTrackIndex)

            val originalSampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)

            Log.d(TAG, "Original: ${originalSampleRate}Hz, ${channelCount}ch")

            // 解码音频
            val samples = decodeAudio(extractor, format, duration)
            extractor.release()

            if (samples.isEmpty()) {
                Log.e(TAG, "No samples decoded")
                return null
            }

            // 转单声道
            val monoData = if (channelCount > 1) {
                convertToMono(samples, channelCount)
            } else {
                samples
            }

            // 重采样到 8000Hz
            val resampledData = if (originalSampleRate != TARGET_SAMPLE_RATE) {
                resampleAudio(monoData, originalSampleRate, TARGET_SAMPLE_RATE)
            } else {
                monoData
            }

            // 补齐或截断到目标长度
            val targetSamples = duration * TARGET_SAMPLE_RATE
            if (resampledData.size < targetSamples) {
                resampledData + FloatArray(targetSamples - resampledData.size)
            } else {
                resampledData.copyOf(targetSamples)
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载音频失败: ${e.message}", e)
            null
        }
    }

    /**
     * 解码音频
     */
    private fun decodeAudio(
        extractor: android.media.MediaExtractor,
        format: android.media.MediaFormat,
        duration: Int
    ): FloatArray {
        val mime = format.getString(android.media.MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("Unknown MIME type")
        val samples = mutableListOf<Float>()

        val mediaCodec = android.media.MediaCodec.createDecoderByType(mime)

        try {
            mediaCodec.configure(format, null, null, 0)
            mediaCodec.start()

            var outputDone = false
            var inputEOS = false
            val maxSamples = duration * 44100 * 2 // 安全限制

            while (!outputDone && samples.size < maxSamples) {
                if (!inputEOS) {
                    val inputBufferIndex = mediaCodec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex) ?: continue
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            mediaCodec.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            if (presentationTimeUs > duration * 1_000_000L) {
                                mediaCodec.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0,
                                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEOS = true
                            } else {
                                mediaCodec.queueInputBuffer(
                                    inputBufferIndex, 0, sampleSize, presentationTimeUs, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val bufferInfo = android.media.MediaCodec.BufferInfo()
                val outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000)

                when {
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val shortBuffer = outputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                            while (shortBuffer.hasRemaining() && samples.size < maxSamples) {
                                samples.add(shortBuffer.get().toFloat() / 32768f)
                            }
                        }

                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false)

                        if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outputBufferIndex == android.media.MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputEOS) Thread.sleep(10)
                    }
                }
            }
        } finally {
            try {
                mediaCodec.stop()
                mediaCodec.release()
            } catch (e: Exception) {
                // Ignore
            }
        }

        return samples.toFloatArray()
    }

    /**
     * 录音 - 参考 api1.py record_audio
     */
    private suspend fun recordAudio(
        duration: Int,
        onProgress: ((Int) -> Unit)? = null
    ): FloatArray? = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(
            TARGET_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size")
            return@withContext null
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            TARGET_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(bufferSize * 2, TARGET_SAMPLE_RATE * 2)
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            return@withContext null
        }

        try {
            val targetSamples = duration * TARGET_SAMPLE_RATE
            val audioData = ShortArray(targetSamples)
            var samplesRead = 0

            audioRecord.startRecording()
            val startTime = System.currentTimeMillis()
            val timeoutMillis = duration * 1000L

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = ((elapsed * 100) / timeoutMillis).toInt().coerceIn(0, 100)
                onProgress?.invoke(progress)

                if (elapsed >= timeoutMillis || samplesRead >= targetSamples) {
                    break
                }

                val remaining = targetSamples - samplesRead
                val buffer = ShortArray(minOf(1024, remaining))
                val read = audioRecord.read(buffer, 0, buffer.size)

                if (read > 0) {
                    val toCopy = minOf(read, remaining)
                    System.arraycopy(buffer, 0, audioData, samplesRead, toCopy)
                    samplesRead += toCopy
                }
            }

            audioRecord.stop()

            if (samplesRead == 0) {
                Log.e(TAG, "No audio recorded")
                return@withContext null
            }

            // 转换为 FloatArray
            FloatArray(samplesRead) { i -> audioData[i] / 32768f }

        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}", e)
            null
        } finally {
            try {
                audioRecord.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * 生成指纹 - 参考 api1.py _generate_fp_async
     */
    private suspend fun generateFingerprint(
        samples: FloatArray,
        onProgress: ((Int) -> Unit)? = null
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var webView: WebView? = null

            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    continuation.resume("ERROR: Timeout")
                    webView?.destroy()
                }
            }
            handler.postDelayed(timeoutRunnable, 30000)

            handler.post {
                try {
                    webView = WebView(context)
                    webView?.settings?.javaScriptEnabled = true
                    webView?.settings?.domStorageEnabled = true

                    // 读取 JS 文件内容
                    val afpJs = context.assets.open("afp.js").bufferedReader().use { it.readText() }
                    val wasmJs = context.assets.open("afp.wasm.js").bufferedReader().use { it.readText() }

                    val samplesJson = samples.joinToString(",", "[", "]")

                    webView?.addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onResult(result: String) {
                                handler.removeCallbacks(timeoutRunnable)
                                if (continuation.isActive) {
                                    continuation.resume(result)
                                }
                                handler.post { webView?.destroy() }
                            }

                            @JavascriptInterface
                            fun onError(error: String) {
                                handler.removeCallbacks(timeoutRunnable)
                                if (continuation.isActive) {
                                    continuation.resume("ERROR: $error")
                                }
                                handler.post { webView?.destroy() }
                            }
                        },
                        "AndroidBridge"
                    )

                    // 构建 HTML - 参考 api1.py _generate_fp_async
                    val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
</head>
<body>
<script>
// WASM binary loader
$wasmJs
window.WASM_BINARY = WASM_BINARY;

// AFP runtime
$afpJs

async function generateFingerprint() {
    try {
        let fpRuntime = AudioFingerprintRuntime();
        
        // Wait for initialization
        await new Promise((resolve, reject) => {
            let check = setInterval(() => {
                if (typeof fpRuntime.ExtractQueryFP === 'function') {
                    clearInterval(check);
                    resolve();
                }
            }, 10);
            setTimeout(() => {
                clearInterval(check);
                if (typeof fpRuntime.ExtractQueryFP !== 'function') {
                    reject('WASM init timeout');
                } else {
                    resolve();
                }
            }, 5000);
        });

        let PCMBuffer = new Float32Array($samplesJson);
        let fp_vector = fpRuntime.ExtractQueryFP(PCMBuffer.buffer);

        let result_buf = new Uint8Array(fp_vector.size());
        for (let t = 0; t < fp_vector.size(); t++) {
            result_buf[t] = fp_vector.get(t);
        }

        // Base64 encode
        let binary = '';
        let len = result_buf.byteLength;
        for (let i = 0; i < len; i++) {
            binary += String.fromCharCode(result_buf[i]);
        }
        return btoa(binary);
    } catch (err) {
        return 'ERROR: ' + err.toString();
    }
}

generateFingerprint().then(result => {
    if (result.startsWith('ERROR:')) {
        window.AndroidBridge.onError(result.substring(7));
    } else {
        window.AndroidBridge.onResult(result);
    }
}).catch(err => {
    window.AndroidBridge.onError(err.toString());
});
</script>
</body>
</html>
                    """.trimIndent()

                    webView?.loadDataWithBaseURL(
                        "file:///android_asset/",
                        html,
                        "text/html",
                        "UTF-8",
                        null
                    )

                    continuation.invokeOnCancellation {
                        handler.removeCallbacks(timeoutRunnable)
                        handler.post { webView?.destroy() }
                    }
                } catch (e: Exception) {
                    handler.removeCallbacks(timeoutRunnable)
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    /**
     * 转单声道
     */
    private fun convertToMono(samples: FloatArray, channels: Int): FloatArray {
        if (channels == 1) return samples
        val monoSize = samples.size / channels
        return FloatArray(monoSize) { i ->
            var sum = 0f
            for (ch in 0 until channels) {
                sum += samples[i * channels + ch]
            }
            sum / channels
        }
    }

    /**
     * 重采样
     */
    private fun resampleAudio(
        samples: FloatArray,
        originalRate: Int,
        targetRate: Int
    ): FloatArray {
        if (originalRate == targetRate) return samples

        val ratio = targetRate.toDouble() / originalRate
        val targetLength = (samples.size * ratio).toInt()

        return FloatArray(targetLength) { i ->
            val srcIndex = i / ratio
            val srcIdx = srcIndex.toInt()
            val fraction = srcIndex - srcIdx

            when {
                srcIdx >= samples.size - 1 -> samples.last()
                srcIdx < 0 -> samples.first()
                else -> {
                    val s0 = samples[srcIdx]
                    val s1 = samples[srcIdx + 1]
                    (s0 * (1 - fraction) + s1 * fraction).toFloat()
                }
            }
        }
    }

    /**
     * 复制 URI 到临时文件
     */
    private fun copyUriToTempFile(uri: Uri): File? {
        return try {
            val tempFile = File.createTempFile("audio_", ".tmp", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "复制文件失败: ${e.message}", e)
            null
        }
    }

    fun cancelRecognition() {
        isRecognizing = false
    }

    fun isRecognizing(): Boolean = isRecognizing

    fun isSupportedMimeType(mimeType: String?): Boolean {
        if (mimeType.isNullOrEmpty()) return false
        return SUPPORTED_AUDIO_TYPES.any { mimeType.equals(it, ignoreCase = true) }
    }

    fun isSupportedExtension(extension: String?): Boolean {
        if (extension.isNullOrEmpty()) return false
        return SUPPORTED_EXTENSIONS.contains(extension.lowercase())
    }

    sealed class RecognitionResult {
        data class Success(val song: MusicRecognizerApi.SongResult) : RecognitionResult()
        data object NotFound : RecognitionResult()
        data class Error(val message: String) : RecognitionResult()
    }
}
