#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "chromaprint.h"

#define LOG_TAG "ChromaprintJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * 生成音频指纹
 * 
 * 参数:
 *   pcmData - 16-bit PCM 音频数据
 *   sampleRate - 采样率 (Hz)
 *   numChannels - 声道数
 * 
 * 返回:
 *   Base64 编码的指纹字符串
 */
JNIEXPORT jstring JNICALL
Java_com_tacke_music_recognition_native_ChromaprintNative_generateFingerprint(
        JNIEnv *env,
        jclass clazz,
        jshortArray pcmData,
        jint sampleRate,
        jint numChannels) {
    
    // 获取 PCM 数据
    jsize dataLength = env->GetArrayLength(pcmData);
    jshort *pcmArray = env->GetShortArrayElements(pcmData, nullptr);
    
    if (pcmArray == nullptr) {
        LOGE("Failed to get PCM data");
        return nullptr;
    }
    
    // 转换为 int16_t 数组
    std::vector<int16_t> audioData(dataLength);
    for (jsize i = 0; i < dataLength; i++) {
        audioData[i] = static_cast<int16_t>(pcmArray[i]);
    }
    
    env->ReleaseShortArrayElements(pcmData, pcmArray, JNI_ABORT);
    
    LOGI("Generating fingerprint: %d samples, %d Hz, %d channels", 
         dataLength, sampleRate, numChannels);
    
    // 创建 Chromaprint 上下文
    // 使用 TEST2 算法（与 AcoustID 兼容的标准算法）
    ChromaprintContext *ctx = chromaprint_new(CHROMAPRINT_ALGORITHM_TEST2);
    if (ctx == nullptr) {
        LOGE("Failed to create Chromaprint context");
        return nullptr;
    }
    
    // 开始处理
    if (!chromaprint_start(ctx, sampleRate, numChannels)) {
        LOGE("Failed to start Chromaprint");
        chromaprint_free(ctx);
        return nullptr;
    }
    
    // 发送音频数据
    if (!chromaprint_feed(ctx, audioData.data(), dataLength)) {
        LOGE("Failed to feed audio data");
        chromaprint_free(ctx);
        return nullptr;
    }
    
    // 完成处理
    if (!chromaprint_finish(ctx)) {
        LOGE("Failed to finish Chromaprint");
        chromaprint_free(ctx);
        return nullptr;
    }
    
    // 获取原始指纹
    uint32_t *rawFingerprint = nullptr;
    int rawSize = 0;
    if (!chromaprint_get_raw_fingerprint(ctx, &rawFingerprint, &rawSize)) {
        LOGE("Failed to get raw fingerprint");
        chromaprint_free(ctx);
        return nullptr;
    }
    
    LOGI("Raw fingerprint size: %d", rawSize);
    
    // 编码为压缩格式
    char *encodedFingerprint = nullptr;
    int encodedSize = 0;
    if (!chromaprint_encode_fingerprint(rawFingerprint, rawSize, 
                                         CHROMAPRINT_ALGORITHM_TEST2,
                                         &encodedFingerprint, &encodedSize, 
                                         1)) {
        LOGE("Failed to encode fingerprint");
        chromaprint_dealloc(rawFingerprint);
        chromaprint_free(ctx);
        return nullptr;
    }
    
    // 转换为 Java 字符串
    jstring result = env->NewStringUTF(encodedFingerprint);
    
    // 清理
    chromaprint_dealloc(rawFingerprint);
    chromaprint_dealloc(encodedFingerprint);
    chromaprint_free(ctx);
    
    LOGI("Fingerprint generated successfully");
    
    return result;
}

/**
 * 获取 Chromaprint 版本
 */
JNIEXPORT jstring JNICALL
Java_com_tacke_music_recognition_native_ChromaprintNative_getVersion(
        JNIEnv *env,
        jclass clazz) {
    return env->NewStringUTF(chromaprint_get_version());
}

} // extern "C"
