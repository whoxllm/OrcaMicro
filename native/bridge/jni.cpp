// SPDX-License-Identifier: AGPL-3.0-or-later
//
// JNI entry points for io.github.whoxllm.orcamicro.engine.NativeEngine.

#include <jni.h>

#include <string>

#include "engine.hpp"

namespace {

std::string to_string(JNIEnv *env, jstring value)
{
    if (value == nullptr)
        return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars != nullptr ? chars : "");
    if (chars != nullptr)
        env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_jstring(JNIEnv *env, const std::string &value)
{
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_whoxllm_orcamicro_engine_NativeEngine_nativeSlice(JNIEnv *env, jobject, jstring request_json)
{
    return to_jstring(env, orcamicro::slice(to_string(env, request_json)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_whoxllm_orcamicro_engine_NativeEngine_nativeProgress(JNIEnv *env, jobject)
{
    return to_jstring(env, orcamicro::progress());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_whoxllm_orcamicro_engine_NativeEngine_nativeVersion(JNIEnv *env, jobject)
{
    return to_jstring(env, orcamicro::version());
}
