// JNI entry points for com.orcaslicer.android.core.OrcaNative.
//
// Every call returns a JSON string; failures are reported as {"error": "..."} instead of Java
// exceptions so the Kotlin side has a single result path.

#include <jni.h>

#include <string>
#include <thread>
#include <vector>

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

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_orcaslicer_android_core_OrcaNative_init(JNIEnv *env, jobject, jstring resources_dir,
                                                                           jstring data_dir, jstring cache_dir)
{
    return guarded(env, [&] {
        OrcaEngine::instance().init(to_string(env, resources_dir), to_string(env, data_dir), to_string(env, cache_dir));
        return json{{"ok", true}};
    });
}

JNIEXPORT jstring JNICALL Java_com_orcaslicer_android_core_OrcaNative_loadPresets(JNIEnv *env, jobject)
{
    return guarded(env, [&] { return OrcaEngine::instance().load_presets(); });
}

JNIEXPORT jstring JNICALL Java_com_orcaslicer_android_core_OrcaNative_printerList(JNIEnv *env, jobject)
{
    return guarded(env, [&] { return OrcaEngine::instance().printer_list(); });
}

JNIEXPORT jstring JNICALL Java_com_orcaslicer_android_core_OrcaNative_selectPrinter(JNIEnv *env, jobject, jstring printer)
{
    return guarded(env, [&] { return OrcaEngine::instance().select_printer(to_string(env, printer)); });
}

JNIEXPORT jstring JNICALL Java_com_orcaslicer_android_core_OrcaNative_loadModel(JNIEnv *env, jobject, jobjectArray paths,
                                                                                jint copies, jstring mesh_out)
{
    return guarded(env, [&] {
        std::vector<std::string> files;
        const jsize              n = env->GetArrayLength(paths);
        for (jsize i = 0; i < n; ++i) {
            auto s = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
            files.push_back(to_string(env, s));
            env->DeleteLocalRef(s);
        }
        return OrcaEngine::instance().load_model(files, copies, to_string(env, mesh_out));
    });
}

JNIEXPORT jstring JNICALL Java_com_orcaslicer_android_core_OrcaNative_slice(JNIEnv *env, jobject, jstring print_preset,
                                                                            jstring filament_preset, jstring overrides,
                                                                            jstring gcode_out, jstring preview_out,
                                                                            jobject listener)
{
    return guarded(env, [&] {
        jclass    cls        = env->GetObjectClass(listener);
        jmethodID on_progress = env->GetMethodID(cls, "onProgress", "(ILjava/lang/String;)V");
        env->DeleteLocalRef(cls);

        // libslic3r reports some progress from TBB worker threads, which are not attached to the
        // JVM; only the calling thread may use `env`, so updates from elsewhere are dropped.
        const std::thread::id caller = std::this_thread::get_id();
        auto progress = [&](int percent, const std::string &text) {
            if (std::this_thread::get_id() != caller)
                return;
            jstring jtext = env->NewStringUTF(text.c_str());
            env->CallVoidMethod(listener, on_progress, jint(percent), jtext);
            env->DeleteLocalRef(jtext);
            if (env->ExceptionCheck())
                env->ExceptionClear();
        };

        const std::string overrides_str = to_string(env, overrides);
        const json        overrides_json = overrides_str.empty() ? json::object() : json::parse(overrides_str);
        return OrcaEngine::instance().slice(to_string(env, print_preset), to_string(env, filament_preset), overrides_json,
                                            to_string(env, gcode_out), to_string(env, preview_out), progress);
    });
}

JNIEXPORT jstring JNICALL Java_com_orcaslicer_android_core_OrcaNative_optionDefs(JNIEnv *env, jobject, jstring type)
{
    return guarded(env, [&] { return OrcaEngine::instance().option_defs(to_string(env, type)); });
}

JNIEXPORT jstring JNICALL Java_com_orcaslicer_android_core_OrcaNative_presetValues(JNIEnv *env, jobject, jstring type, jstring name)
{
    return guarded(env, [&] { return OrcaEngine::instance().preset_values(to_string(env, type), to_string(env, name)); });
}

JNIEXPORT jstring JNICALL Java_com_orcaslicer_android_core_OrcaNative_savePreset(JNIEnv *env, jobject, jstring type, jstring base,
                                                                                 jstring new_name, jstring overrides)
{
    return guarded(env, [&] {
        return OrcaEngine::instance().save_preset(to_string(env, type), to_string(env, base), to_string(env, new_name),
                                                  json::parse(to_string(env, overrides)));
    });
}

JNIEXPORT jstring JNICALL Java_com_orcaslicer_android_core_OrcaNative_deletePreset(JNIEnv *env, jobject, jstring type, jstring name)
{
    return guarded(env, [&] { return OrcaEngine::instance().delete_preset(to_string(env, type), to_string(env, name)); });
}

JNIEXPORT void JNICALL Java_com_orcaslicer_android_core_OrcaNative_cancel(JNIEnv *, jobject)
{
    OrcaEngine::instance().cancel();
}

} // extern "C"
