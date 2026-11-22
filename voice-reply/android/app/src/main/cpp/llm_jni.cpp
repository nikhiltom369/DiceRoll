// JNI bridge used by LlmEngine.kt. Strings cross the boundary as UTF-8 byte
// arrays, which avoids JNI's "modified UTF-8" problems with special characters.

#include "llm_core.h"

#include <android/log.h>
#include <jni.h>

#include <stdexcept>
#include <string>

#define TAG "VoiceReplyLLM"

namespace {

std::string to_string(JNIEnv * env, jbyteArray bytes) {
    const jsize n = env->GetArrayLength(bytes);
    std::string out(n, '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(out, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

void throw_java(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, message);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_voicereply_app_LlmEngine_nativeInit(JNIEnv * env, jobject, jstring backend_dir) {
    const char * dir = env->GetStringUTFChars(backend_dir, nullptr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "loading CPU backends from %s", dir);
    llm_global_init(dir);
    env->ReleaseStringUTFChars(backend_dir, dir);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_voicereply_app_LlmEngine_nativeLoad(JNIEnv * env, jobject, jstring model_path, jint n_ctx, jint n_threads) {
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    LlmSession * s = llm_load(path, n_ctx, n_threads);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicereply_app_LlmEngine_nativeReset(JNIEnv * env, jobject, jlong handle, jbyteArray system_prompt) {
    llm_reset(reinterpret_cast<LlmSession *>(handle), to_string(env, system_prompt));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_voicereply_app_LlmEngine_nativeReply(JNIEnv * env, jobject, jlong handle, jbyteArray user_text, jint max_tokens) {
    try {
        const std::string reply = llm_reply(reinterpret_cast<LlmSession *>(handle), to_string(env, user_text), max_tokens);
        return to_bytes(env, reply);
    } catch (const std::exception & e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "reply failed: %s", e.what());
        throw_java(env, e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicereply_app_LlmEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    llm_free(reinterpret_cast<LlmSession *>(handle));
}
