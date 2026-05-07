package com.tacke.music.recognition.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 音频采集管理器
 * 用于录制原始 PCM 音频数据
 */
class AudioCaptureManager {

    companion object {
        const val TAG = "AudioCaptureManager"

        // AcoustID 推荐使用 11025 Hz 采样率
        const val SAMPLE_RATE = 11025
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 录音时长（毫秒）- AcoustID 建议 30 秒以获得最佳识别效果
        const val RECORDING_DURATION_MS = 30000

        // 缓冲区大小
        const val BUFFER_SIZE = 1024

        // 进度更新间隔（毫秒）
        const val PROGRESS_UPDATE_INTERVAL_MS = 100
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 开始录音并返回 PCM 数据
     * @return Pair<ShortArray, Int> - PCM 数据和采样率
     */
    suspend fun startRecording(
        onProgress: ((Int) -> Unit)? = null
    ): Pair<ShortArray, Int>? = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize.coerceAtLeast(BUFFER_SIZE * 2)
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            return@withContext null
        }

        isRecording = true
        audioRecord?.startRecording()

        val audioData = mutableListOf<Short>()
        val buffer = ShortArray(BUFFER_SIZE)
        val startTime = System.currentTimeMillis()
        val maxDuration = RECORDING_DURATION_MS.toLong()
        var lastProgressUpdateTime = 0L

        try {
            while (isRecording && isActive) {
                val elapsedTime = System.currentTimeMillis() - startTime
                if (elapsedTime >= maxDuration) {
                    Log.d(TAG, "录音达到最大时长，自动停止")
                    break
                }

                // 限制进度更新频率，避免过于频繁的UI更新
                if (onProgress != null && elapsedTime - lastProgressUpdateTime >= PROGRESS_UPDATE_INTERVAL_MS) {
                    val progress = ((elapsedTime * 100) / maxDuration).toInt()
                    // 在主线程回调进度
                    mainHandler.post { onProgress.invoke(progress) }
                    lastProgressUpdateTime = elapsedTime
                }

                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (read > 0) {
                    // 将读取的数据添加到列表
                    for (i in 0 until read) {
                        audioData.add(buffer[i])
                    }
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord 读取错误: $read")
                    break
                }

                // 短暂延迟避免 CPU 占用过高
                delay(1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "录音过程出错", e)
        } finally {
            stopRecording()
        }

        val totalSamples = audioData.size
        val duration = (totalSamples * 1000) / SAMPLE_RATE

        Log.d(TAG, "录音完成，总样本数: $totalSamples, 时长: ${duration}ms")

        if (totalSamples == 0) {
            return@withContext null
        }

        // 转换为 ShortArray
        val resultArray = ShortArray(audioData.size) { i -> audioData[i] }

        Pair(resultArray, SAMPLE_RATE)
    }

    /**
     * 停止录音
     */
    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "停止录音出错", e)
        }
        audioRecord = null
    }

    /**
     * 检查是否正在录音
     */
    fun isRecording(): Boolean = isRecording

    /**
     * 将 ShortArray 转换为 ByteArray（小端序）
     */
    fun shortArrayToByteArray(shortArray: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(shortArray.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        shortArray.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    /**
     * 计算音频数据的 RMS 音量（用于检测是否有声音输入）
     */
    fun calculateRms(audioData: ShortArray): Double {
        if (audioData.isEmpty()) return 0.0

        var sum = 0.0
        for (sample in audioData) {
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / audioData.size)
    }

    /**
     * 检测音频是否有足够的音量
     * 阈值降低以适应安静环境
     */
    fun hasValidAudio(audioData: ShortArray, threshold: Int = 100): Boolean {
        val rms = calculateRms(audioData)
        Log.d(TAG, "音频 RMS: $rms, 阈值: $threshold")
        return rms > threshold
    }
}
