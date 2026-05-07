package com.tacke.music.recognition.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频文件解码器
 * 将各种格式的音频文件（MP3、AAC、FLAC 等）解码为 PCM 数据
 *
 * 支持的格式：
 * - MP3 (.mp3)
 * - FLAC (.flac)
 * - M4A/AAC (.m4a, .aac)
 * - WAV (.wav)
 * - OGG (.ogg)
 * - OPUS (.opus)
 * - WMA (.wma) - 部分设备支持
 */
class AudioFileDecoder {

    companion object {
        const val TAG = "AudioFileDecoder"
        const val TIMEOUT_US = 10000L

        // 支持的音频 MIME 类型
        val SUPPORTED_FORMATS = mapOf(
            "audio/mpeg" to "MP3",
            "audio/mp3" to "MP3",
            "audio/mp4" to "M4A/AAC",
            "audio/aac" to "AAC",
            "audio/flac" to "FLAC",
            "audio/x-flac" to "FLAC",
            "audio/wav" to "WAV",
            "audio/x-wav" to "WAV",
            "audio/ogg" to "OGG",
            "audio/x-ogg" to "OGG",
            "audio/opus" to "OPUS",
            "audio/vorbis" to "OGG Vorbis",
            "audio/x-matroska" to "MKA",
            "audio/wma" to "WMA",
            "audio/x-ms-wma" to "WMA"
        )

        // 支持的文件扩展名
        val SUPPORTED_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "wav", "wave",
            "ogg", "oga", "opus", "wma", "aiff", "au", "snd"
        )
    }

    /**
     * 检查设备是否支持解码指定格式
     */
    fun isFormatSupported(mimeType: String): Boolean {
        if (mimeType.isEmpty()) return false

        // 获取所有可用的解码器
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) {
                for (supportedType in codecInfo.supportedTypes) {
                    if (supportedType.equals(mimeType, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * 解码音频文件为 PCM 数据
     * 从文件中间位置提取指定时长的音频片段，提高识别成功率
     *
     * @param context 上下文
     * @param uri 音频文件 URI
     * @param maxDurationMs 最大处理时长（毫秒），AcoustID 建议至少 30 秒
     * @param startOffsetMs 开始偏移（毫秒），从开头开始提取更好
     * @return DecodedAudio 解码后的音频数据和元数据
     */
    suspend fun decodeAudioFile(
        context: Context,
        uri: Uri,
        maxDurationMs: Int = 30000,
        startOffsetMs: Int = -1, // -1 表示跳过前 5 秒
        onProgress: ((Int) -> Unit)? = null
    ): DecodedAudio? = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            Log.d(TAG, "开始解码音频文件: $uri")

            // 创建 MediaExtractor
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // 找到音频轨道
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                Log.e(TAG, "未找到音频轨道")
                return@withContext null
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)

            Log.d(TAG, "音频格式: $mime, 采样率: $sampleRate, 声道数: $channelCount, 时长: ${durationUs / 1000000}s")

            // 检查格式是否受支持
            if (!isFormatSupported(mime)) {
                Log.w(TAG, "设备可能不支持此格式: $mime")
            }

            // 计算开始位置（默认跳过前 5 秒，避开前奏/静音）
            val startTimeUs = if (startOffsetMs < 0) {
                // 默认从文件开头开始（跳过前 5 秒的前奏/静音）
                (5 * 1000000L).coerceAtMost(durationUs / 2)
            } else {
                (startOffsetMs * 1000L).coerceIn(0, durationUs)
            }

            // 计算结束位置
            val endTimeUs = (startTimeUs + maxDurationMs * 1000L).coerceAtMost(durationUs)
            val actualDurationMs = ((endTimeUs - startTimeUs) / 1000).toInt()

            Log.d(TAG, "提取音频片段: ${startTimeUs / 1000000}s - ${endTimeUs / 1000000}s, 时长: ${actualDurationMs}ms")

            // 定位到开始位置
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // 创建解码器
            try {
                codec = MediaCodec.createDecoderByType(mime)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "设备不支持此音频格式: $mime", e)
                return@withContext null
            }

            codec.configure(format, null, null, 0)
            codec.start()

            val pcmData = mutableListOf<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            val maxSamples = (sampleRate * actualDurationMs / 1000)

            while (!sawOutputEOS && isActive) {
                // 处理输入
                if (!sawInputEOS) {
                    val inputBufferId = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferId >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferId)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            val sampleTime = extractor.sampleTime

                            if (sampleSize < 0 || sampleTime > endTimeUs) {
                                codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                                Log.d(TAG, "输入结束: sampleTime=${sampleTime}us, endTime=${endTimeUs}us")
                            } else {
                                codec.queueInputBuffer(inputBufferId, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 处理输出
                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferId >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferId)
                    if (outputBuffer != null) {
                        // 将解码后的数据转换为 PCM ShortArray
                        val chunk = decodeOutputBuffer(outputBuffer, bufferInfo, channelCount)
                        pcmData.addAll(chunk.toList())

                        // 检查是否达到最大样本数
                        if (pcmData.size >= maxSamples) {
                            sawOutputEOS = true
                            Log.d(TAG, "达到最大样本数: ${pcmData.size}")
                        }

                        // 更新进度
                        val progress = ((pcmData.size.toFloat() / maxSamples) * 100).toInt().coerceIn(0, 100)
                        onProgress?.invoke(progress)
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.d(TAG, "输出格式改变: $newFormat")
                }
            }

            Log.d(TAG, "解码完成，PCM 样本数: ${pcmData.size} (目标: $maxSamples)")

            if (pcmData.isEmpty()) {
                return@withContext null
            }

            val pcmArray = pcmData.toShortArray()
            val finalDurationSec = pcmArray.size / sampleRate
            Log.d(TAG, "最终 PCM 数据，样本数: ${pcmArray.size}, 采样率: $sampleRate, 声道数: $channelCount, 时长: ${finalDurationSec}秒")

            DecodedAudio(
                pcmData = pcmArray,
                sampleRate = sampleRate,
                channels = channelCount,
                durationSeconds = finalDurationSec
            )

        } catch (e: Exception) {
            Log.e(TAG, "解码音频文件失败", e)
            null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放解码器失败", e)
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放提取器失败", e)
            }
        }
    }

    /**
     * 找到音频轨道
     */
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    /**
     * 解码输出缓冲区为 ShortArray
     */
    private fun decodeOutputBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo, channelCount: Int): ShortArray {
        val byteBuffer = buffer.duplicate()
        byteBuffer.position(info.offset)
        byteBuffer.limit(info.offset + info.size)

        // 如果是多声道，转换为单声道
        val samples = mutableListOf<Short>()
        val shortBuffer = byteBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()

        while (shortBuffer.hasRemaining()) {
            if (channelCount == 1) {
                samples.add(shortBuffer.get())
            } else {
                // 多声道转单声道：取平均值
                var sum = 0
                for (i in 0 until channelCount) {
                    if (shortBuffer.hasRemaining()) {
                        sum += shortBuffer.get().toInt()
                    }
                }
                samples.add((sum / channelCount).toShort())
            }
        }

        return samples.toShortArray()
    }

    /**
     * 获取音频文件信息
     */
    fun getAudioFileInfo(context: Context, uri: Uri): AudioFileInfo? {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) return null

            val format = extractor.getTrackFormat(trackIndex)

            AudioFileInfo(
                mimeType = format.getString(MediaFormat.KEY_MIME) ?: "",
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000,
                formatName = SUPPORTED_FORMATS[format.getString(MediaFormat.KEY_MIME) ?: ""] ?: "未知格式"
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取音频信息失败", e)
            null
        } finally {
            try {
                extractor?.release()
            } catch (e: Exception) {
                Log.e(TAG, "释放提取器失败", e)
            }
        }
    }

    /**
     * 获取文件格式友好名称
     */
    fun getFormatDisplayName(mimeType: String): String {
        return SUPPORTED_FORMATS[mimeType] ?: mimeType.substringAfter("audio/", "未知")
    }

    /**
     * 解码后的音频数据
     */
    data class DecodedAudio(
        val pcmData: ShortArray,
        val sampleRate: Int,
        val channels: Int,
        val durationSeconds: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DecodedAudio

            if (sampleRate != other.sampleRate) return false
            if (channels != other.channels) return false
            if (durationSeconds != other.durationSeconds) return false
            if (!pcmData.contentEquals(other.pcmData)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = pcmData.contentHashCode()
            result = 31 * result + sampleRate
            result = 31 * result + channels
            result = 31 * result + durationSeconds
            return result
        }
    }

    /**
     * 音频文件信息
     */
    data class AudioFileInfo(
        val mimeType: String,
        val sampleRate: Int,
        val channelCount: Int,
        val durationMs: Long,
        val formatName: String = ""
    )
}
