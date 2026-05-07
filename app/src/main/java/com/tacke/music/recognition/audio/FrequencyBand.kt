package com.tacke.music.recognition.audio

enum class FrequencyBand(val id: Int, val minFreq: Int, val maxFreq: Int) {
    BAND_250_520(0, 250, 520),
    BAND_520_1450(1, 520, 1450),
    BAND_1450_3500(2, 1450, 3500),
    BAND_3500_5500(3, 3500, 5500);

    companion object {
        fun fromFrequency(freqHz: Float): FrequencyBand? {
            return values().find { freqHz >= it.minFreq && freqHz <= it.maxFreq }
        }

        fun fromId(id: Int): FrequencyBand? {
            return values().find { it.id == id }
        }
    }
}
