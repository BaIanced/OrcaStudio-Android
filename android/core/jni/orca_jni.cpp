// JNI entry points for app.orcaandroid.core.OrcaNative.
//
// Besides init/slice/cancel, all engine functions go through call(method, argsJson): one generic
// entry point keeps the JNI surface small. Every call returns a JSON string; failures are
// reported as {"error": "..."} instead of Java exceptions so the Kotlin side has a single result path.

#include <jni.h>

#include <thread>

#include "EngineCalls.hpp"
#include "OrcaEngine.hpp"

using orca::json;
using orca::OrcaEngine;

namespace {

std::string to_string(JNIEnv *env, jstring s)
{
    if (s == nullptr)
        return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring to_jstring(JNIEnv *env, const json &j)
{
    // Invalid UTF-8 (e.g. from a mis-encoded file name) must not abort the dump.
    return env->NewStringUTF(j.dump(-1, ' ', false, json::error_handler_t::replace).c_str());
}

template<class Fn> jstring guarded(JNIEnv *env, Fn &&fn)
{
    try {
        return to_jstring(env, fn());
    } catch (const std::exception &ex) {
        return to_jstring(env, json{{"error", ex.what()}});
    } catch (...) {
        return to_jstring(env, json{{"error", "Unknown native error"}});
    }
}

// Forwards progress to a Kotlin ProgressListener. libslic3r also reports from TBB worker
// threads, which are not attached to the JVM; only the calling thread may use `env`, so
// updates from elsewhere are dropped.
orca::ProgressFn progress_forwarder(JNIEnv *env, jobject listener)
{
    jclass    cls         = env->GetObjectClass(listener);
    jmethodID on_progress = env->GetMethodID(cls, "onProgress", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(cls);
    const std::thread::id caller = std::this_thread::get_id();
    return [env, listener, on_progress, caller](int percent, const std::string &text) {
        if (std::this_thread::get_id() != caller)
            return;
        jstring jtext = env->NewStringUTF(text.c_str());
        env->CallVoidMethod(listener, on_progress, jint(percent), jtext);
        env->DeleteLocalRef(jtext);
        if (env->ExceptionCheck())
            env->ExceptionClear();
    };
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_app_orcaandroid_core_OrcaNative_init(JNIEnv *env, jobject, jstring resources_dir, jstring data_dir,
                                                                    jstring cache_dir)
{
    return guarded(env, [&] {
        OrcaEngine::instance().init(to_string(env, resources_dir), to_string(env, data_dir), to_string(env, cache_dir));
        return json{{"ok", true}};
    });
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_core_OrcaNative_call(JNIEnv *env, jobject, jstring method, jstring args)
{
    return guarded(env, [&] {
        const std::string args_str = to_string(env, args);
        return orca::dispatch(OrcaEngine::instance(), to_string(env, method), args_str.empty() ? json::object() : json::parse(args_str));
    });
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_core_OrcaNative_slice(JNIEnv *env, jobject, jint plate, jstring gcode_out,
                                                                     jstring preview_dir, jobject listener)
{
    return guarded(env, [&] {
        return OrcaEngine::instance().slice(plate, to_string(env, gcode_out), to_string(env, preview_dir), progress_forwarder(env, listener));
    });
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_core_OrcaNative_viewGcode(JNIEnv *env, jobject, jstring gcode, jstring preview_dir,
                                                                         jobject listener)
{
    return guarded(env, [&] {
        return OrcaEngine::instance().view_gcode(to_string(env, gcode), to_string(env, preview_dir), progress_forwarder(env, listener));
    });
}

JNIEXPORT void JNICALL Java_app_orcaandroid_core_OrcaNative_cancel(JNIEnv *, jobject)
{
    OrcaEngine::instance().cancel();
}

} // extern "C"
