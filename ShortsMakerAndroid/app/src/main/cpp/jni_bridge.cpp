// Thin JNI bridge around whisper.cpp's public C API (whisper.h).
// Verify these function/struct names against the exact whisper.cpp commit you vendor -
// the core full-transcribe API below has been stable for a long time, but always check
// examples/whisper.android in https://github.com/ggerganov/whisper.cpp if something doesn't
// link. See NATIVE_SETUP.md.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define LOG_TAG "WhisperBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_shortsmaker_app_pipeline_WhisperBridge_nativeInitContext(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // Android GPU (Vulkan/OpenCL) backends are not reliable across
                               // devices yet; CPU with the quantized tiny/base models is the
                               // safe default. Flip this on once you've tested your target phones.
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_shortsmaker_app_pipeline_WhisperBridge_nativeFreeContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    if (ctxPtr != 0) {
        whisper_free(reinterpret_cast<struct whisper_context *>(ctxPtr));
    }
}

// audio: mono, 16 kHz, float32 PCM in [-1, 1]. Returns a flat array of
// [tokenCount, (startCentiSec, endCentiSec, prob*1000, textUtf8Len, textUtf8Bytes...) ...] is
// awkward over JNI, so instead we build the word list directly here and hand back a single
// "\u0001"-joined string of "text\u0002start\u0002end\u0002prob" records for Kotlin to parse -
// simplest thing that reliably survives the JNI boundary for all three scripts.
JNIEXPORT jstring JNICALL
Java_com_shortsmaker_app_pipeline_WhisperBridge_nativeFullTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong ctxPtr, jfloatArray audio, jstring language,
        jint numThreads) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (ctx == nullptr) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(audio);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(audio, 0, n, samples.data());

    const char *langC = nullptr;
    std::string langStr;
    if (language != nullptr) {
        const char *l = env->GetStringUTFChars(language, nullptr);
        langStr = l;
        env->ReleaseStringUTFChars(language, l);
        langC = langStr.empty() ? nullptr : langStr.c_str();  // null = auto-detect
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.translate = false;
    params.language = langC;
    params.n_threads = numThreads > 0 ? numThreads : 4;
    params.token_timestamps = true;     // needed for word-level timing
    params.max_len = 1;                 // ~one token per emitted "segment" -> word-level chunks
    params.split_on_word = true;

    if (whisper_full(ctx, params, samples.data(), (int) samples.size()) != 0) {
        LOGE("whisper_full failed");
        return env->NewStringUTF("");
    }

    std::string out;
    const int nSeg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSeg; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        int64_t t0 = whisper_full_get_segment_t0(ctx, i);   // centiseconds
        int64_t t1 = whisper_full_get_segment_t1(ctx, i);
        if (text == nullptr || text[0] == '\0') continue;
        out += text;
        out += '\x02';
        out += std::to_string(t0 / 100.0);
        out += '\x02';
        out += std::to_string(t1 / 100.0);
        out += '\x02';
        out += "1.0";   // whisper.cpp exposes per-token logprob, not a 0-1 prob; default confident
        out += '\x01';
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_shortsmaker_app_pipeline_WhisperBridge_nativeDetectedLanguage(
        JNIEnv *env, jobject /*thiz*/, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (ctx == nullptr) return env->NewStringUTF("en");
    int id = whisper_full_lang_id(ctx);
    return env->NewStringUTF(whisper_lang_str(id));
}

}  // extern "C"
