package com.tacke.music.recognition.audio

data class FrequencyPeak(
    val fftPassNumber: Int,
    val peakMagnitude: Int,
    val correctedPeakFrequencyBin: Int,
    val sampleRateHz: Int = 16000
)
