package com.tacke.music.recognition

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.tacke.music.recognition.api.DoresoTrack
import com.tacke.music.recognition.fingerprint.AudioFingerprintRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 识别引擎类型
 */
enum class RecognitionEngine {
    DORESO,      // Doreso/aha-music API
    FINGERPRINT  // WebAssembly指纹 + 网易云API
}

/**
 * 识别引擎管理器
 * 管理不同识别引擎的切换和使用
 */
class RecognitionEngineManager(private val context: Context) {

    companion object {
        const val PREFS_NAME = "recognition_settings"
        const val KEY_ENGINE = "recognition_engine"
        const val DEFAULT_ENGINE = "FINGERPRINT"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentEngine = MutableStateFlow(getSavedEngine())
    val currentEngine: StateFlow<RecognitionEngine> = _currentEngine.asStateFlow()

    // 各识别引擎实例
    private val doresoRecognizer: DoresoRecognizer by lazy { DoresoRecognizer(context) }
    private val fingerprintRecognizer: AudioFingerprintRecognizer by lazy { AudioFingerprintRecognizer(context) }

    /**
     * 获取当前保存的引擎设置
     */
    private fun getSavedEngine(): RecognitionEngine {
        val engineName = prefs.getString(KEY_ENGINE, DEFAULT_ENGINE)
        return try {
            RecognitionEngine.valueOf(engineName!!)
        } catch (e: Exception) {
            RecognitionEngine.FINGERPRINT
        }
    }

    /**
     * 切换识别引擎
     */
    fun switchEngine(engine: RecognitionEngine) {
        if (_currentEngine.value != engine) {
            _currentEngine.value = engine
            prefs.edit().putString(KEY_ENGINE, engine.name).apply()
        }
    }

    /**
     * 获取当前引擎名称（用于显示）
     */
    fun getCurrentEngineName(): String {
        reloadEngineSetting()
        return when (_currentEngine.value) {
            RecognitionEngine.DORESO -> "Doreso识别"
            RecognitionEngine.FINGERPRINT -> "网易云识别"
        }
    }

    /**
     * 获取所有可用引擎的显示信息
     */
    fun getAvailableEngines(): List<Pair<RecognitionEngine, String>> {
        return listOf(
            RecognitionEngine.DORESO to "Doreso识别",
            RecognitionEngine.FINGERPRINT to "网易云识别"
        )
    }

    /**
     * 重新加载保存的引擎设置
     */
    fun reloadEngineSetting() {
        val savedEngine = getSavedEngine()
        if (_currentEngine.value != savedEngine) {
            _currentEngine.value = savedEngine
        }
    }

    /**
     * 录音识别
     */
    suspend fun recognizeFromRecording(
        onProgress: ((Int) -> Unit)? = null
    ): RecognitionResult {
        // 每次识别前重新读取设置，确保获取最新配置
        reloadEngineSetting()
        return when (_currentEngine.value) {
            RecognitionEngine.DORESO -> {
                when (val result = doresoRecognizer.recognizeFromRecording(onProgress)) {
                    is DoresoRecognizer.RecognitionResult.Success -> {
                        RecognitionResult.Success(
                            track = RecognizedTrack(
                                title = result.track.title,
                                artist = result.track.artist,
                                album = result.track.album,
                                coverUrl = result.track.coverUrl,
                                score = result.track.score
                            ),
                            engine = RecognitionEngine.DORESO
                        )
                    }
                    is DoresoRecognizer.RecognitionResult.NotFound -> RecognitionResult.NotFound
                    is DoresoRecognizer.RecognitionResult.Error -> RecognitionResult.Error(result.message)
                }
            }
            RecognitionEngine.FINGERPRINT -> {
                when (val result = fingerprintRecognizer.recognizeFromRecording(onProgress = onProgress)) {
                    is AudioFingerprintRecognizer.RecognitionResult.Success -> {
                        RecognitionResult.Success(
                            track = RecognizedTrack(
                                title = result.song.songName,
                                artist = result.song.artistName,
                                album = result.song.albumName,
                                coverUrl = result.song.albumPicUrl,
                                score = result.song.score.toDouble()
                            ),
                            engine = RecognitionEngine.FINGERPRINT
                        )
                    }
                    is AudioFingerprintRecognizer.RecognitionResult.NotFound -> RecognitionResult.NotFound
                    is AudioFingerprintRecognizer.RecognitionResult.Error -> RecognitionResult.Error(result.message)
                }
            }
        }
    }

    /**
     * 文件识别
     */
    suspend fun recognizeFromFile(
        uri: Uri,
        onProgress: ((Int) -> Unit)? = null
    ): RecognitionResult {
        // 每次识别前重新读取设置，确保获取最新配置
        reloadEngineSetting()
        return when (_currentEngine.value) {
            RecognitionEngine.DORESO -> {
                when (val result = doresoRecognizer.recognizeFromFile(uri, onProgress)) {
                    is DoresoRecognizer.RecognitionResult.Success -> {
                        RecognitionResult.Success(
                            track = RecognizedTrack(
                                title = result.track.title,
                                artist = result.track.artist,
                                album = result.track.album,
                                coverUrl = result.track.coverUrl,
                                score = result.track.score
                            ),
                            engine = RecognitionEngine.DORESO
                        )
                    }
                    is DoresoRecognizer.RecognitionResult.NotFound -> RecognitionResult.NotFound
                    is DoresoRecognizer.RecognitionResult.Error -> RecognitionResult.Error(result.message)
                }
            }
            RecognitionEngine.FINGERPRINT -> {
                when (val result = fingerprintRecognizer.recognizeFromFile(uri, onProgress = onProgress)) {
                    is AudioFingerprintRecognizer.RecognitionResult.Success -> {
                        RecognitionResult.Success(
                            track = RecognizedTrack(
                                title = result.song.songName,
                                artist = result.song.artistName,
                                album = result.song.albumName,
                                coverUrl = result.song.albumPicUrl,
                                score = result.song.score.toDouble()
                            ),
                            engine = RecognitionEngine.FINGERPRINT
                        )
                    }
                    is AudioFingerprintRecognizer.RecognitionResult.NotFound -> RecognitionResult.NotFound
                    is AudioFingerprintRecognizer.RecognitionResult.Error -> RecognitionResult.Error(result.message)
                }
            }
        }
    }

    /**
     * 取消识别
     */
    fun cancelRecognition() {
        doresoRecognizer.cancelRecognition()
        fingerprintRecognizer.cancelRecognition()
    }

    /**
     * 检查是否正在识别
     */
    fun isRecognizing(): Boolean {
        return doresoRecognizer.isRecognizing() || fingerprintRecognizer.isRecognizing()
    }

    /**
     * 统一的识别结果数据类
     */
    data class RecognizedTrack(
        val title: String,
        val artist: String,
        val album: String = "未知专辑",
        val coverUrl: String? = null,
        val score: Double? = null
    )

    /**
     * 统一的识别结果密封类
     */
    sealed class RecognitionResult {
        data class Success(
            val track: RecognizedTrack,
            val engine: RecognitionEngine
        ) : RecognitionResult()
        data object NotFound : RecognitionResult()
        data class Error(val message: String) : RecognitionResult()
    }
}
