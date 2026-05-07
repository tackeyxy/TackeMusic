package com.tacke.music.recognition.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class AudioFingerprinter {

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE = 1024
        const val HOP_SIZE = 128
        const val RECORDING_DURATION_MS = 8000 // 8秒录音

        // 频带边界 (Hz)
        val BAND_RANGES = listOf(
            250f to 520f,
            520f to 1450f,
            1450f to 3500f,
            3500f to 5500f
        )
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    fun startRecording(onPeakDetected: ((FrequencyBand, FrequencyPeak) -> Unit)? = null): ShazamSignature? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioFingerprinter", "AudioRecord 初始化失败")
            return null
        }

        isRecording = true
        audioRecord?.startRecording()

        val signature = ShazamSignature()
        signature.sampleRateHz = SAMPLE_RATE

        val buffer = ShortArray(FRAME_SIZE)
        val window = createHanningWindow(FRAME_SIZE)
        var fftPassNumber = 0
        val maxSamples = (SAMPLE_RATE * RECORDING_DURATION_MS / 1000)
        var totalSamples = 0

        val bandPeaks = mutableMapOf<FrequencyBand, MutableList<FrequencyPeak>>()

        val startTime = System.currentTimeMillis()
        val maxDurationMs = RECORDING_DURATION_MS.toLong()

        // 启动一个定时器，在最大录音时间后强制停止
        val timer = java.util.Timer("RecordingTimer", true)
        timer.schedule(object : java.util.TimerTask() {
            override fun run() {
                Log.d("AudioFingerprinter", "定时器触发，强制停止录音")
                stopRecording()
            }
        }, maxDurationMs)

        try {
            while (isRecording && totalSamples < maxSamples) {
                val read = try {
                    audioRecord?.read(buffer, 0, FRAME_SIZE) ?: 0
                } catch (e: Exception) {
                    Log.e("AudioFingerprinter", "read() 被中断或出错", e)
                    -1
                }

                if (read > 0) {
                    // 应用窗函数
                    val windowedBuffer = applyWindow(buffer, window)

                    // 执行 FFT
                    val fftResult = performFFT(windowedBuffer)

                    // 计算幅度谱
                    val magnitudes = calculateMagnitudes(fftResult)

                    // 转换为分贝并归一化
                    val dbMagnitudes = magnitudesToDb(magnitudes)

                    // 提取每个频带的峰值
                    extractPeaks(dbMagnitudes, fftPassNumber, bandPeaks)

                    fftPassNumber++
                    totalSamples += HOP_SIZE
                } else if (read < 0) {
                    // 读取错误或被中断，退出循环
                    Log.e("AudioFingerprinter", "AudioRecord 读取错误或被中断: $read")
                    break
                }
                // read == 0 时继续循环，但添加短暂延迟避免CPU占用过高
                if (read == 0) {
                    Thread.sleep(1)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioFingerprinter", "录音过程出错", e)
        } finally {
            Log.d("AudioFingerprinter", "进入 finally 块，清理资源")
            timer.cancel()
            stopRecordingInternal()
        }

        val elapsedTime = System.currentTimeMillis() - startTime
        Log.d("AudioFingerprinter", "录音完成，耗时: ${elapsedTime}ms, 共 $fftPassNumber 帧，样本数: $totalSamples")
        Log.d("AudioFingerprinter", "峰值统计: ${bandPeaks.map { "${it.key.name}=${it.value.size}" }}")

        signature.numberSamples = totalSamples
        signature.frequencyBandToPeaks.putAll(bandPeaks)

        return signature
    }

    private fun stopRecordingInternal() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("AudioFingerprinter", "停止录音出错", e)
        }
        audioRecord = null
    }

    fun stopRecording() {
        // 设置标志位让循环退出
        isRecording = false
        // 强制停止 AudioRecord 以中断阻塞的 read() 调用
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e("AudioFingerprinter", "强制停止录音出错", e)
        }
    }

    fun isRecording(): Boolean = isRecording

    /**
     * 从音频文件生成指纹签名
     * 支持格式：16kHz 单声道 16-bit PCM 的 WAV 文件或原始 PCM 文件
     * @param file 音频文件
     * @param maxDurationMs 最大处理时长（毫秒），默认8秒
     * @return ShazamSignature 或 null（如果处理失败）
     */
    fun recognizeFromFile(file: File, maxDurationMs: Int = RECORDING_DURATION_MS): ShazamSignature? {
        if (!file.exists() || !file.canRead()) {
            Log.e("AudioFingerprinter", "文件不存在或无法读取: ${file.absolutePath}")
            return null
        }

        Log.d("AudioFingerprinter", "开始处理文件: ${file.absolutePath}, 大小: ${file.length()} bytes")

        val signature = ShazamSignature()
        signature.sampleRateHz = SAMPLE_RATE

        val window = createHanningWindow(FRAME_SIZE)
        var fftPassNumber = 0
        val maxSamples = (SAMPLE_RATE * maxDurationMs / 1000)
        var totalSamples = 0

        val bandPeaks = mutableMapOf<FrequencyBand, MutableList<FrequencyPeak>>()

        try {
            FileInputStream(file).use { fis ->
                // 检查是否是 WAV 文件
                val header = ByteArray(44)
                val headerRead = fis.read(header)

                val isWav = headerRead >= 44 &&
                        header[0] == 'R'.code.toByte() &&
                        header[1] == 'I'.code.toByte() &&
                        header[2] == 'F'.code.toByte() &&
                        header[3] == 'F'.code.toByte()

                val dataInputStream = if (isWav) {
                    // 解析 WAV 头
                    val sampleRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val bitsPerSample = ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    val numChannels = ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    val dataOffset = findDataChunkOffset(header) ?: 44

                    Log.d("AudioFingerprinter", "WAV 文件: sampleRate=$sampleRate, bits=$bitsPerSample, channels=$numChannels, dataOffset=$dataOffset")

                    // 跳过到数据部分
                    if (dataOffset > 44) {
                        fis.skip((dataOffset - 44).toLong())
                    }
                    fis
                } else {
                    // 不是 WAV，当作原始 PCM 处理，需要重新从头读取
                    fis.channel.position(0)
                    fis
                }

                // 读取并处理音频数据
                val buffer = ShortArray(FRAME_SIZE)
                val byteBuffer = ByteArray(FRAME_SIZE * 2)

                while (totalSamples < maxSamples) {
                    val bytesRead = dataInputStream.read(byteBuffer)
                    if (bytesRead < FRAME_SIZE * 2) {
                        break // 文件结束
                    }

                    // 转换字节到 short (16-bit PCM)
                    for (i in 0 until FRAME_SIZE) {
                        buffer[i] = ByteBuffer.wrap(byteBuffer, i * 2, 2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .short
                    }

                    // 应用窗函数
                    val windowedBuffer = applyWindow(buffer, window)

                    // 执行 FFT
                    val fftResult = performFFT(windowedBuffer)

                    // 计算幅度谱
                    val magnitudes = calculateMagnitudes(fftResult)

                    // 转换为分贝并归一化
                    val dbMagnitudes = magnitudesToDb(magnitudes)

                    // 提取每个频带的峰值
                    extractPeaks(dbMagnitudes, fftPassNumber, bandPeaks)

                    fftPassNumber++
                    totalSamples += HOP_SIZE
                }
            }
        } catch (e: Exception) {
            Log.e("AudioFingerprinter", "处理文件出错", e)
            return null
        }

        Log.d("AudioFingerprinter", "文件处理完成，共 $fftPassNumber 帧，样本数: $totalSamples")
        Log.d("AudioFingerprinter", "峰值统计: ${bandPeaks.map { "${it.key.name}=${it.value.size}" }}")

        signature.numberSamples = totalSamples
        signature.frequencyBandToPeaks.putAll(bandPeaks)

        return signature
    }

    /**
     * 从 URI 识别音频文件
     */
    fun recognizeFromUri(context: Context, uri: Uri, maxDurationMs: Int = RECORDING_DURATION_MS): ShazamSignature? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // 创建临时文件
                val tempFile = File.createTempFile("audio_recognition_", ".pcm", context.cacheDir)
                tempFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                val result = recognizeFromFile(tempFile, maxDurationMs)
                tempFile.delete()
                result
            }
        } catch (e: Exception) {
            Log.e("AudioFingerprinter", "从 URI 读取文件出错", e)
            null
        }
    }

    private fun findDataChunkOffset(header: ByteArray): Int? {
        // 查找 "data" chunk
        for (i in 36 until header.size - 4) {
            if (header[i] == 'd'.code.toByte() &&
                header[i + 1] == 'a'.code.toByte() &&
                header[i + 2] == 't'.code.toByte() &&
                header[i + 3] == 'a'.code.toByte()) {
                return i + 8 // 跳过 "data" 和 4字节大小
            }
        }
        return null
    }

    private fun createHanningWindow(size: Int): DoubleArray {
        return DoubleArray(size) { i ->
            0.5 * (1 - cos(2 * PI * i / (size - 1)))
        }
    }

    private fun applyWindow(buffer: ShortArray, window: DoubleArray): DoubleArray {
        return DoubleArray(buffer.size) { i ->
            buffer[i].toDouble() * window[i]
        }
    }

    private fun performFFT(input: DoubleArray): DoubleArray {
        val n = input.size
        val output = DoubleArray(n * 2)

        // 复制输入到实部
        for (i in input.indices) {
            output[i * 2] = input[i]
            output[i * 2 + 1] = 0.0
        }

        // 位反转置换
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempReal = output[i * 2]
                val tempImag = output[i * 2 + 1]
                output[i * 2] = output[j * 2]
                output[i * 2 + 1] = output[j * 2 + 1]
                output[j * 2] = tempReal
                output[j * 2 + 1] = tempImag
            }
            var bit = n shr 1
            while (j >= bit) {
                j -= bit
                bit = bit shr 1
            }
            j += bit
        }

        // Cooley-Tukey FFT
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val tableStep = n / len
            for (i in 0 until n step len) {
                var k = 0
                for (j in 0 until halfLen) {
                    val uReal = output[(i + j) * 2]
                    val uImag = output[(i + j) * 2 + 1]
                    val angle = -2 * PI * k / len
                    val tReal = output[(i + j + halfLen) * 2] * cos(angle) - output[(i + j + halfLen) * 2 + 1] * sin(angle)
                    val tImag = output[(i + j + halfLen) * 2] * sin(angle) + output[(i + j + halfLen) * 2 + 1] * cos(angle)
                    output[(i + j) * 2] = uReal + tReal
                    output[(i + j) * 2 + 1] = uImag + tImag
                    output[(i + j + halfLen) * 2] = uReal - tReal
                    output[(i + j + halfLen) * 2 + 1] = uImag - tImag
                    k += tableStep
                }
            }
            len = len shl 1
        }

        return output
    }

    private fun calculateMagnitudes(fftResult: DoubleArray): DoubleArray {
        val n = fftResult.size / 2
        return DoubleArray(n / 2) { i ->
            val real = fftResult[i * 2]
            val imag = fftResult[i * 2 + 1]
            sqrt(real * real + imag * imag)
        }
    }

    private fun magnitudesToDb(magnitudes: DoubleArray): DoubleArray {
        return DoubleArray(magnitudes.size) { i ->
            20 * log10(magnitudes[i] + 1e-6)
        }
    }

    private fun extractPeaks(
        dbMagnitudes: DoubleArray,
        fftPassNumber: Int,
        bandPeaks: MutableMap<FrequencyBand, MutableList<FrequencyPeak>>
    ) {
        val binFreq = SAMPLE_RATE.toFloat() / FRAME_SIZE
        val minDb = -120.0
        val maxDb = dbMagnitudes.maxOrNull() ?: 0.0

        BAND_RANGES.forEachIndexed { bandIndex, (minFreq, maxFreq) ->
            val minBin = (minFreq / binFreq).toInt().coerceIn(0, dbMagnitudes.size - 1)
            val maxBin = (maxFreq / binFreq).toInt().coerceIn(0, dbMagnitudes.size - 1)

            if (minBin >= maxBin) return@forEachIndexed

            // 找到频带内最大幅度
            var maxMag = minDb
            var peakBin = minBin
            for (i in minBin..maxBin) {
                if (dbMagnitudes[i] > maxMag) {
                    maxMag = dbMagnitudes[i]
                    peakBin = i
                }
            }

            // 抛物线插值
            val correctedBin = if (peakBin > 0 && peakBin < dbMagnitudes.size - 1) {
                val ym1 = dbMagnitudes[peakBin - 1]
                val y0 = dbMagnitudes[peakBin]
                val y1 = dbMagnitudes[peakBin + 1]
                val p = interpolatePeak(ym1.toFloat(), y0.toFloat(), y1.toFloat())
                peakBin + p
            } else {
                peakBin.toFloat()
            }

            // 归一化到 0-65535
            val normalizedMag = ((maxMag - minDb) / (maxDb - minDb) * 65535)
                .toInt()
                .coerceIn(0, 65535)

            // 修正后的频率 bin (乘以64并四舍五入)
            val correctedFreqBin = (correctedBin * 64).roundToInt().coerceIn(0, 1023)

            val band = FrequencyBand.fromId(bandIndex) ?: return@forEachIndexed
            val peak = FrequencyPeak(
                fftPassNumber = fftPassNumber,
                peakMagnitude = normalizedMag,
                correctedPeakFrequencyBin = correctedFreqBin,
                sampleRateHz = SAMPLE_RATE
            )

            bandPeaks.getOrPut(band) { mutableListOf() }.add(peak)
        }
    }

    private fun interpolatePeak(ym1: Float, y0: Float, y1: Float): Float {
        val denom = (y1 - y0) + (y0 - ym1)
        if (denom == 0f) return 0f
        val p = 0.5f * (y1 - ym1) / denom
        return p.coerceIn(-0.5f, 0.5f)
    }
}
