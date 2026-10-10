// JNI entry points for app.orcaandroid.plugins.PluginNative: the Orca plugin runtime
// (plugin/AndroidPluginRuntime.hpp). Results are JSON strings, failures {"error": "..."}.

#include <jni.h>

#include <mutex>
#include <vector>

#include <libslic3r/AppConfig.hpp>
#include <libslic3r/PrintConfig.hpp>

#include "AndroidPluginRuntime.hpp"
#include "OrcaEngine.hpp"

using json = nlohmann::json;

namespace {

JavaVM   *g_vm       = nullptr;
jobject   g_listener = nullptr; // PluginNative.Listener
jmethodID g_on_page_message = nullptr;
jmethodID g_on_open_url     = nullptr;
std::mutex g_listener_mutex;

std::string str(JNIEnv *env, jstring s)
{
    if (s == nullptr)
        return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

template<class Fn> jstring guarded(JNIEnv *env, Fn &&fn)
{
    json out;
    try {
        out = fn();
    } catch (const std::exception &ex) {
        out = {{"error", ex.what()}};
    } catch (...) {
        out = {{"error", "Unknown native error"}};
    }
    return env->NewStringUTF(out.dump(-1, ' ', false, json::error_handler_t::replace).c_str());
}

// Calls the Kotlin listener from any thread (plugins post from Python threads), attaching it to
// the JVM as needed. `make_args` builds the Java arguments with the thread's JNIEnv.
template<class MakeArgs> void call_listener(jmethodID method, MakeArgs &&make_args)
{
    std::lock_guard<std::mutex> lock(g_listener_mutex);
    if (g_vm == nullptr || g_listener == nullptr || method == nullptr)
        return;
    JNIEnv *env      = nullptr;
    bool    attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
            return;
        attached = true;
    }
    std::vector<jstring> args = make_args(env);
    if (args.size() == 3)
        env->CallVoidMethod(g_listener, method, args[0], args[1], args[2]);
    else if (args.size() == 1)
        env->CallVoidMethod(g_listener, method, args[0]);
    if (env->ExceptionCheck())
        env->ExceptionClear();
    for (jstring a : args)
        env->DeleteLocalRef(a);
    if (attached)
        g_vm->DetachCurrentThread();
}

void post_page_message(const std::string &plugin_key, const std::string &capability, const std::string &message)
{
    call_listener(g_on_page_message, [&](JNIEnv *env) {
        return std::vector<jstring>{env->NewStringUTF(plugin_key.c_str()), env->NewStringUTF(capability.c_str()),
                                    env->NewStringUTF(message.c_str())};
    });
}

void open_url(const std::string &url)
{
    call_listener(g_on_open_url, [&](JNIEnv *env) { return std::vector<jstring>{env->NewStringUTF(url.c_str())}; });
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_app_orcaandroid_plugins_PluginNative_start(JNIEnv *env, jobject, jstring cloud_user_id, jstring language,
                                                                          jobject listener)
{
    return guarded(env, [&] {
        {
            std::lock_guard<std::mutex> lock(g_listener_mutex);
            env->GetJavaVM(&g_vm);
            if (g_listener != nullptr)
                env->DeleteGlobalRef(g_listener);
            g_listener = env->NewGlobalRef(listener);
            jclass cls = env->GetObjectClass(listener);
            g_on_page_message = env->GetMethodID(cls, "onPageMessage", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
            g_on_open_url     = env->GetMethodID(cls, "onOpenUrl", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cls);
        }
        orca::plugins::set_host({
            [] { return orca::OrcaEngine::instance().preset_bundle(); },
            [lang = str(env, language)] { return lang; },
            post_page_message,
            open_url,
        });
        return orca::plugins::start(str(env, cloud_user_id));
    });
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_plugins_PluginNative_install(JNIEnv *env, jobject, jstring path, jstring uuid, jstring name,
                                                                            jstring version)
{
    return guarded(env, [&] { return orca::plugins::install(str(env, path), str(env, uuid), str(env, name), str(env, version)); });
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_plugins_PluginNative_list(JNIEnv *env, jobject)
{
    return guarded(env, [&] { return orca::plugins::list(); });
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_plugins_PluginNative_pageOpen(JNIEnv *env, jobject, jstring key, jstring capability)
{
    return guarded(env, [&] { return orca::plugins::page_open(str(env, key), str(env, capability)); });
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_plugins_PluginNative_pageMessage(JNIEnv *env, jobject, jstring key, jstring capability,
                                                                                jstring message)
{
    return guarded(env, [&] { return orca::plugins::page_message(str(env, key), str(env, capability), str(env, message)); });
}

JNIEXPORT void JNICALL Java_app_orcaandroid_plugins_PluginNative_pageClose(JNIEnv *env, jobject, jstring key, jstring capability)
{
    orca::plugins::page_close(str(env, key), str(env, capability));
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_plugins_PluginNative_runScript(JNIEnv *env, jobject, jstring key, jstring capability)
{
    return guarded(env, [&] { return orca::plugins::run_script(str(env, key), str(env, capability)); });
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_plugins_PluginNative_postProcess(JNIEnv *env, jobject, jint plate, jstring gcode_path,
                                                                                jstring host, jstring output_name)
{
    return guarded(env, [&]() -> json {
        auto config = orca::OrcaEngine::instance().slice_config(plate);
        if (!config)
            return {{"messages", json::array()}};
        return orca::plugins::post_process(*config, str(env, gcode_path), str(env, host), str(env, output_name));
    });
}

} // extern "C"
