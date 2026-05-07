package com.tacke.music.recognition.audio

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class ShazamSignature {

    var sampleRateHz: Int = 16000
    var numberSamples: Int = 0
    val frequencyBandToPeaks: MutableMap<FrequencyBand, MutableList<FrequencyPeak>> = mutableMapOf()

    fun encodeToUri(): String {
        val binary = encodeToBinary()
        val base64 = Base64.encodeToString(binary, Base64.NO_WRAP)
        return "data:audio/vnd.shazam.sig;base64,$base64"
    }

    private fun encodeToBinary(): ByteArray {
        val output = ByteArrayOutputStream()

        // 计算总内容长度
        val totalContentLength = calculateTotalContentLength()

        // 1. 写入头部 (48 bytes)
        writeHeader(output, totalContentLength)

        // 2. 写入固定 TLV
        writeFixedTlv(output, totalContentLength)

        // 3. 写入所有频带 TLV
        writeBandTlvs(output)

        val messageWithoutCrc = output.toByteArray()

        // 4. 计算并写入 CRC-32 (从偏移 8 开始)
        val crc = calculateCrc32(messageWithoutCrc, 8, messageWithoutCrc.size - 8)
        writeIntLe(messageWithoutCrc, 4, crc)

        return messageWithoutCrc
    }

    private fun calculateTotalContentLength(): Int {
        var length = 8 // 固定 TLV 的 type + length

        FrequencyBand.values().sortedBy { it.id }.forEach { band ->
            val peaks = frequencyBandToPeaks[band] ?: emptyList()
            if (peaks.isNotEmpty()) {
                val bandDataSize = calculateBandDataSize(peaks)
                val paddedSize = ((bandDataSize + 3) and 0xFFFFFFFC.toInt()) // 4字节对齐
                length += 8 + paddedSize // type + length + padded value
            }
        }

        return length
    }

    private fun calculateBandDataSize(peaks: List<FrequencyPeak>): Int {
        var size = 0
        var lastFftPass = 0

        peaks.sortedBy { it.fftPassNumber }.forEach { peak ->
            val delta = peak.fftPassNumber - lastFftPass
            if (delta >= 255) {
                size += 5 // 0xFF + 4字节绝对帧号
            } else {
                size += 1 // 1字节增量
            }
            size += 4 // 2字节 magnitude + 2字节 frequency bin
            lastFftPass = peak.fftPassNumber
        }

        return size
    }

    private fun writeHeader(output: ByteArrayOutputStream, contentLength: Int) {
        // magic1
        writeIntLe(output, 0xCAFE2580.toInt())
        // crc32 (暂时填0，后面计算)
        writeIntLe(output, 0)
        // size_minus_header
        writeIntLe(output, contentLength)
        // magic2
        writeIntLe(output, 0x94119C00.toInt())
        // void1 (3 * 4 = 12 bytes)
        writeIntLe(output, 0)
        writeIntLe(output, 0)
        writeIntLe(output, 0)
        // shifted_sample_rate_id
        val sampleRateId = when (sampleRateHz) {
            8000 -> 1
            11025 -> 2
            16000 -> 3
            32000 -> 4
            44100 -> 5
            48000 -> 6
            else -> 3
        }
        writeIntLe(output, sampleRateId shl 27)
        // void2 (2 * 4 = 8 bytes)
        writeIntLe(output, 0)
        writeIntLe(output, 0)
        // number_samples_plus_divided_sample_rate
        val value = (numberSamples + sampleRateHz * 0.24).toInt()
        writeIntLe(output, value)
        // magic3
        writeIntLe(output, 0x007C0000)
    }

    private fun writeFixedTlv(output: ByteArrayOutputStream, contentLength: Int) {
        writeIntLe(output, 0x40000000)
        writeIntLe(output, contentLength)
    }

    private fun writeBandTlvs(output: ByteArrayOutputStream) {
        FrequencyBand.values().sortedBy { it.id }.forEach { band ->
            val peaks = frequencyBandToPeaks[band]?.sortedBy { it.fftPassNumber }
            if (!peaks.isNullOrEmpty()) {
                val bandData = encodeBandData(peaks)
                val paddedSize = ((bandData.size + 3) and 0xFFFFFFFC.toInt())

                // Type
                writeIntLe(output, 0x60030040 + band.id)
                // Length
                writeIntLe(output, bandData.size)
                // Value
                output.write(bandData)
                // Padding
                val padding = paddedSize - bandData.size
                repeat(padding) { output.write(0) }
            }
        }
    }

    private fun encodeBandData(peaks: List<FrequencyPeak>): ByteArray {
        val bandOutput = ByteArrayOutputStream()
        var lastFftPass = 0

        peaks.forEach { peak ->
            val delta = peak.fftPassNumber - lastFftPass
            if (delta >= 255) {
                bandOutput.write(0xFF)
                writeIntLe(bandOutput, peak.fftPassNumber)
                lastFftPass = peak.fftPassNumber
            } else {
                bandOutput.write(delta)
                lastFftPass = peak.fftPassNumber
            }
            writeShortLe(bandOutput, peak.peakMagnitude)
            writeShortLe(bandOutput, peak.correctedPeakFrequencyBin)
        }

        return bandOutput.toByteArray()
    }

    private fun writeIntLe(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }

    private fun writeShortLe(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    private fun writeIntLe(byteArray: ByteArray, offset: Int, value: Int) {
        byteArray[offset] = (value and 0xFF).toByte()
        byteArray[offset + 1] = ((value shr 8) and 0xFF).toByte()
        byteArray[offset + 2] = ((value shr 16) and 0xFF).toByte()
        byteArray[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun calculateCrc32(data: ByteArray, offset: Int, length: Int): Int {
        val crc32 = CRC32()
        crc32.update(data, offset, length)
        return crc32.value.toInt()
    }
}
