// JNI bridge to open-bamboo-networking (obn), which is linked statically into this library
// (android/obn/obn.cmake). It gives the app signed LAN printing for Bambu Lab printers that keep
// "LAN Only" and Developer Mode off: obn signs MQTT print commands with the user's own slicer
// credentials, installs the app certificate on the printer, uploads the .gcode.3mf and sends the
// (encrypted) project_file command.
//
// Threading: obn calls its message / connection callbacks on its own threads. They only append
// to a queue that Kotlin drains with poll(); no JNI call is made from an obn thread. print() is
// synchronous, and obn reports its progress on the calling thread, so the listener is invoked
// with that thread's JNIEnv.

#include <jni.h>

#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "obn/bambu_networking.hpp"

// obn's exported entry points (src/abi_*.cpp). Declared here rather than through a header because
// obn ships them only as plugin exports; the signatures must match those definitions exactly.
extern "C" {
void *bambu_network_create_agent(std::string log_dir);
int bambu_network_set_config_dir(void *agent, std::string config_dir);
int bambu_network_set_cert_file(void *agent, std::string folder, std::string filename);
int bambu_network_start(void *agent);
int bambu_network_connect_printer(void *agent, std::string dev_id, std::string dev_ip, std::string username,
                                  std::string password, bool use_ssl);
int bambu_network_disconnect_printer(void *agent);
int bambu_network_send_message_to_printer(void *agent, std::string dev_id, std::string json_str, int qos, int flag);
void bambu_network_install_device_cert(void *agent, std::string dev_id, bool lan_only);
int bambu_network_set_on_local_connect_fn(void *agent, BBL::OnLocalConnectedFn fn);
int bambu_network_set_on_local_message_fn(void *agent, BBL::OnMessageFn fn);
int bambu_network_set_on_message_fn(void *agent, BBL::OnMessageFn fn);
int bambu_network_start_local_print(void *agent, BBL::PrintParams params, BBL::OnUpdateStatusFn update_fn,
                                    BBL::WasCancelledFn cancel_fn);
std::string bambu_network_get_version();
// Bambu account (cloud sign-in), as Bambu Studio's login dialog uses them.
std::string bambu_network_get_bambulab_host(void *agent);
int bambu_network_get_my_token(void *agent, std::string ticket, unsigned int *http_code, std::string *http_body);
int bambu_network_get_my_profile(void *agent, std::string token, unsigned int *http_code, std::string *http_body);
int bambu_network_change_user(void *agent, std::string user_info);
bool bambu_network_is_user_login(void *agent);
std::string bambu_network_get_user_name(void *agent);
int bambu_network_user_logout(void *agent, bool request);
int bambu_network_get_user_print_info(void *agent, unsigned int *http_code, std::string *http_body);
// Web single sign-on ticket (MakerWorld), as the desktop MakerWorld tab uses it.
int bambu_network_request_bind_ticket(void *agent, std::string *ticket);
// Cloud user presets, as the desktop's preset sync uses them.
int bambu_network_get_setting_list(void *agent, std::string bundle_version, BBL::ProgressFn pro_fn, BBL::WasCancelledFn cancel_fn);
int bambu_network_get_user_presets(void *agent, std::map<std::string, std::map<std::string, std::string>> *user_presets);
// Cloud channel (only with block_cloud = 0 in obn.conf): MQTT reports and commands through the
// Bambu account, and cloud printing.
int bambu_network_connect_server(void *agent);
bool bambu_network_is_server_connected(void *agent);
int bambu_network_add_subscribe(void *agent, std::vector<std::string> dev_list);
int bambu_network_send_message(void *agent, std::string dev_id, std::string json_str, int qos, int flag);
int bambu_network_start_print(void *agent, BBL::PrintParams params, BBL::OnUpdateStatusFn update_fn,
                              BBL::WasCancelledFn cancel_fn, BBL::OnWaitFn wait_fn);
}

namespace {

struct Event {
    std::string kind; // "connect" | "message"
    std::string dev_id;
    int status = 0;
    std::string text;
};

std::mutex g_mu;
std::condition_variable g_cv;
void *g_agent = nullptr;
std::string g_dir;
std::deque<Event> g_events;
bool g_cert_installed = false;
bool g_cancel = false;
constexpr size_t kMaxEvents = 512;

void push_event(Event e)
{
    std::lock_guard<std::mutex> lk(g_mu);
    if (e.kind == "message" && e.text.find("device_cert_installed") != std::string::npos) g_cert_installed = true;
    if (g_events.size() >= kMaxEvents) g_events.pop_front();
    g_events.push_back(std::move(e));
    g_cv.notify_all();
}

std::string jstr(JNIEnv *env, jstring s)
{
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Minimal JSON string escaping for the poll() payload (event text is printer JSON or plain text).
std::string esc(const std::string &s)
{
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
        case '"': o += "\\\""; break;
        case '\\': o += "\\\\"; break;
        case '\n': o += "\\n"; break;
        case '\r': o += "\\r"; break;
        case '\t': o += "\\t"; break;
        default:
            if (c < 0x20) {
                char buf[8];
                snprintf(buf, sizeof buf, "\\u%04x", c);
                o += buf;
            } else {
                o += static_cast<char>(c);
            }
        }
    }
    return o;
}

void *current_agent()
{
    std::lock_guard<std::mutex> lk(g_mu);
    return g_agent;
}

// {"rc":..,"http":..,"body":"..."} for obn's HTTP-style calls; body is the raw response text.
jstring http_result(JNIEnv *env, int rc, unsigned int http, const std::string &body)
{
    const std::string out = "{\"rc\":" + std::to_string(rc) + ",\"http\":" + std::to_string(http) + ",\"body\":\"" + esc(body) + "\"}";
    return env->NewStringUTF(out.c_str());
}

} // namespace

extern "C" {

// Creates the agent once. dir holds obn.conf, the user's slicer_*.pem files, the printer
// certificates obn caches and obn.log. Returns the obn version, or "" on failure.
JNIEXPORT jstring JNICALL Java_app_orcaandroid_net_ObnNative_init(JNIEnv *env, jobject, jstring dir)
{
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_agent) {
        const std::string d = jstr(env, dir);
        void *agent = bambu_network_create_agent(d);
        if (!agent) return env->NewStringUTF("");
        bambu_network_set_config_dir(agent, d);
        // obn reads Bambu's printer CA as <folder>/printer.cer (ObnCredentials.installPrinterCa puts
        // it in d); without it LAN MQTT fails while TLS verification is on. No file name: with one,
        // obn would also use <folder>/<name> as the cloud MQTT CA, which stays as it was.
        bambu_network_set_cert_file(agent, d, "");
        bambu_network_set_on_local_connect_fn(agent, [](int status, std::string dev_id, std::string msg) {
            push_event({"connect", std::move(dev_id), status, std::move(msg)});
        });
        auto on_msg = [](std::string dev_id, std::string msg) {
            push_event({"message", std::move(dev_id), 0, std::move(msg)});
        };
        bambu_network_set_on_local_message_fn(agent, on_msg);
        bambu_network_set_on_message_fn(agent, on_msg);
        bambu_network_start(agent);
        g_agent = agent;
        g_dir   = d;
    }
    return env->NewStringUTF(bambu_network_get_version().c_str());
}

JNIEXPORT jint JNICALL Java_app_orcaandroid_net_ObnNative_connect(JNIEnv *env, jobject, jstring dev_id, jstring ip,
                                                                 jstring access_code)
{
    void *agent;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        agent = g_agent;
        g_cert_installed = false;
    }
    if (!agent) return BAMBU_NETWORK_ERR_INVALID_HANDLE;
    return bambu_network_connect_printer(agent, jstr(env, dev_id), jstr(env, ip), "bblp", jstr(env, access_code),
                                         true);
}

JNIEXPORT void JNICALL Java_app_orcaandroid_net_ObnNative_disconnect(JNIEnv *, jobject)
{
    void *agent;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        agent = g_agent;
    }
    if (agent) bambu_network_disconnect_printer(agent);
}

JNIEXPORT jint JNICALL Java_app_orcaandroid_net_ObnNative_send(JNIEnv *env, jobject, jstring dev_id, jstring json)
{
    void *agent;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        agent = g_agent;
    }
    if (!agent) return BAMBU_NETWORK_ERR_INVALID_HANDLE;
    // obn's send_message: the LAN session when it is connected to this printer, else the cloud
    // channel (refused while block_cloud is on). Print commands are signed on both.
    return bambu_network_send_message(agent, jstr(env, dev_id), jstr(env, json), 0, 0);
}

// Re-reads obn.conf (block_cloud, cloud_print, ...): obn reloads it whenever the config dir is set
// again (Agent::set_config_dir, written to be repeatable).
JNIEXPORT jint JNICALL Java_app_orcaandroid_net_ObnNative_reloadConfig(JNIEnv *, jobject)
{
    void       *agent;
    std::string dir;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        agent = g_agent;
        dir   = g_dir;
    }
    return agent ? bambu_network_set_config_dir(agent, dir) : 0;
}

// Connects the cloud channel (if not yet) and subscribes to the printer's reports; waits up to
// timeoutMs for the connection. Returns 0 when connected.
JNIEXPORT jint JNICALL Java_app_orcaandroid_net_ObnNative_cloudConnect(JNIEnv *env, jobject, jstring dev_id, jint timeout_ms)
{
    void *agent = current_agent();
    if (!agent) return BAMBU_NETWORK_ERR_INVALID_HANDLE;
    if (!bambu_network_is_server_connected(agent)) {
        const int rc = bambu_network_connect_server(agent);
        if (rc != 0) return rc;
    }
    // obn keeps the subscription and applies it once the connection is up (CloudSession::add_subscribe).
    bambu_network_add_subscribe(agent, {jstr(env, dev_id)});
    for (int waited = 0; !bambu_network_is_server_connected(agent) && waited < timeout_ms; waited += 100)
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    return bambu_network_is_server_connected(agent) ? 0 : BAMBU_NETWORK_ERR_CONNECT_FAILED;
}

// Asks the printer to trust the user's slicer certificate and waits up to timeoutMs for the
// printer's own certificate in reply (obn needs it to encrypt the print URL). Returns true once
// the exchange has completed in this session.
JNIEXPORT jboolean JNICALL Java_app_orcaandroid_net_ObnNative_installCert(JNIEnv *env, jobject, jstring dev_id,
                                                                         jint timeout_ms)
{
    void *agent;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        agent = g_agent;
        if (g_cert_installed) return JNI_TRUE;
    }
    if (!agent) return JNI_FALSE;
    const std::string dev = jstr(env, dev_id);
    bambu_network_install_device_cert(agent, dev, false);
    std::unique_lock<std::mutex> lk(g_mu);
    g_cv.wait_for(lk, std::chrono::milliseconds(timeout_ms), [] { return g_cert_installed; });
    return g_cert_installed ? JNI_TRUE : JNI_FALSE;
}

// Drains queued events as a JSON array: [{"kind":..,"dev":..,"status":..,"text":..}, ...].
JNIEXPORT jstring JNICALL Java_app_orcaandroid_net_ObnNative_poll(JNIEnv *env, jobject)
{
    std::deque<Event> evs;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        evs.swap(g_events);
    }
    std::string out = "[";
    for (size_t i = 0; i < evs.size(); ++i) {
        const auto &e = evs[i];
        if (i) out += ',';
        out += "{\"kind\":\"" + esc(e.kind) + "\",\"dev\":\"" + esc(e.dev_id) +
               "\",\"status\":" + std::to_string(e.status) + ",\"text\":\"" + esc(e.text) + "\"}";
    }
    out += "]";
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL Java_app_orcaandroid_net_ObnNative_cancelPrint(JNIEnv *, jobject)
{
    std::lock_guard<std::mutex> lk(g_mu);
    g_cancel = true;
}

// Uploads file (a .gcode.3mf) and starts it on the printer. Blocks until obn has sent the print
// command or failed. listener.onProgress(stage, percent, message) mirrors obn's update callback
// (stages are BBL::PrintingStage*). Returns 0 or a BAMBU_NETWORK_ERR_* code.
JNIEXPORT jint JNICALL Java_app_orcaandroid_net_ObnNative_print(JNIEnv *env, jobject, jstring dev_id, jstring ip,
                                                               jstring access_code, jstring file,
                                                               jstring project_name, jint plate_index,
                                                               jboolean use_ams, jstring ams_mapping,
                                                               jboolean cloud, jobject listener)
{
    void *agent;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        agent = g_agent;
        g_cancel = false;
    }
    if (!agent) return BAMBU_NETWORK_ERR_INVALID_HANDLE;

    BBL::PrintParams p{};
    p.dev_id = jstr(env, dev_id);
    p.dev_ip = jstr(env, ip);
    p.username = "bblp";
    p.password = jstr(env, access_code);
    p.use_ssl_for_ftp = true;
    p.use_ssl_for_mqtt = true;
    p.filename = jstr(env, file);
    p.project_name = jstr(env, project_name);
    p.task_name = p.project_name;
    p.plate_index = plate_index;
    p.connection_type = "lan";
    p.task_bed_leveling = true;
    p.task_use_ams = use_ams == JNI_TRUE;
    p.ams_mapping = jstr(env, ams_mapping);
    p.try_emmc_print = false; // FTPS :990 upload, the path Orca-Android already uses for Bambu

    jclass cls = listener ? env->GetObjectClass(listener) : nullptr;
    jmethodID on_progress = cls ? env->GetMethodID(cls, "onProgress", "(IILjava/lang/String;)V") : nullptr;
    auto update = [&](int stage, int code, std::string msg) {
        if (!on_progress) return;
        jstring jmsg = env->NewStringUTF(msg.c_str());
        env->CallVoidMethod(listener, on_progress, jint(stage), jint(code), jmsg);
        env->DeleteLocalRef(jmsg);
        if (env->ExceptionCheck()) env->ExceptionClear();
    };
    auto cancelled = [] {
        std::lock_guard<std::mutex> lk(g_mu);
        return g_cancel;
    };
    if (cloud == JNI_TRUE) {
        // start_print: with cloud_print = try_lan_first (patch 0002) a LAN upload when the printer is
        // reachable, else Bambu's cloud print (upload, POST /my/task).
        p.connection_type = "cloud";
        return bambu_network_start_print(agent, p, update, cancelled, BBL::OnWaitFn());
    }
    return bambu_network_start_local_print(agent, p, update, cancelled);
}

// --- Bambu account ------------------------------------------------------------------------------
// The sign-in page hands over a one-time ticket; it is exchanged for tokens (getMyToken), the
// profile is read (getMyProfile) and the resulting user_login JSON is given to obn (changeUser),
// which keeps the session in obn.auth.json. The account's printer list (userPrintInfo) carries
// each printer's serial and LAN access code.

JNIEXPORT jstring JNICALL Java_app_orcaandroid_net_ObnNative_loginHost(JNIEnv *env, jobject)
{
    void *agent = current_agent();
    return env->NewStringUTF(agent ? bambu_network_get_bambulab_host(agent).c_str() : "");
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_net_ObnNative_getMyToken(JNIEnv *env, jobject, jstring ticket)
{
    void *agent = current_agent();
    unsigned int http = 0;
    std::string body;
    const int rc = agent ? bambu_network_get_my_token(agent, jstr(env, ticket), &http, &body) : BAMBU_NETWORK_ERR_INVALID_HANDLE;
    return http_result(env, rc, http, body);
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_net_ObnNative_getMyProfile(JNIEnv *env, jobject, jstring token)
{
    void *agent = current_agent();
    unsigned int http = 0;
    std::string body;
    const int rc = agent ? bambu_network_get_my_profile(agent, jstr(env, token), &http, &body) : BAMBU_NETWORK_ERR_INVALID_HANDLE;
    return http_result(env, rc, http, body);
}

JNIEXPORT jint JNICALL Java_app_orcaandroid_net_ObnNative_changeUser(JNIEnv *env, jobject, jstring user_info)
{
    void *agent = current_agent();
    return agent ? bambu_network_change_user(agent, jstr(env, user_info)) : BAMBU_NETWORK_ERR_INVALID_HANDLE;
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_net_ObnNative_userName(JNIEnv *env, jobject)
{
    void *agent = current_agent();
    const std::string name = agent && bambu_network_is_user_login(agent) ? bambu_network_get_user_name(agent) : std::string();
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT void JNICALL Java_app_orcaandroid_net_ObnNative_logout(JNIEnv *, jobject)
{
    void *agent = current_agent();
    if (agent) bambu_network_user_logout(agent, true);
}

JNIEXPORT jstring JNICALL Java_app_orcaandroid_net_ObnNative_userPrintInfo(JNIEnv *env, jobject)
{
    void *agent = current_agent();
    unsigned int http = 0;
    std::string body;
    const int rc = agent ? bambu_network_get_user_print_info(agent, &http, &body) : BAMBU_NETWORK_ERR_INVALID_HANDLE;
    return http_result(env, rc, http, body);
}

// One-time web sign-in ticket for the signed-in account (obn request_bind_ticket), exchanged by
// MakerWorld at <host>api/sign-in/ticket like the desktop's MakerWorld tab does. "" on failure.
JNIEXPORT jstring JNICALL Java_app_orcaandroid_net_ObnNative_webTicket(JNIEnv *env, jobject)
{
    void *agent = current_agent();
    std::string ticket;
    if (!agent || bambu_network_request_bind_ticket(agent, &ticket) != 0) ticket.clear();
    return env->NewStringUTF(ticket.c_str());
}

// Downloads the account's cloud presets for profile bundle `version` (get_setting_list, then
// get_user_presets, as the desktop's sync does). Returns {"rc": Int, "presets": {name: {key: value}}}
// with option values serialized as libslic3r writes them.
JNIEXPORT jstring JNICALL Java_app_orcaandroid_net_ObnNative_cloudPresets(JNIEnv *env, jobject, jstring version)
{
    void *agent = current_agent();
    const int rc = agent ? bambu_network_get_setting_list(agent, jstr(env, version), BBL::ProgressFn(), BBL::WasCancelledFn())
                         : BAMBU_NETWORK_ERR_INVALID_HANDLE;
    std::map<std::string, std::map<std::string, std::string>> presets;
    if (rc == 0) bambu_network_get_user_presets(agent, &presets);
    std::string out = "{\"rc\":" + std::to_string(rc) + ",\"presets\":{";
    for (auto p = presets.begin(); p != presets.end(); ++p) {
        if (p != presets.begin()) out += ',';
        out += "\"" + esc(p->first) + "\":{";
        for (auto v = p->second.begin(); v != p->second.end(); ++v) {
            if (v != p->second.begin()) out += ',';
            out += "\"" + esc(v->first) + "\":\"" + esc(v->second) + "\"";
        }
        out += '}';
    }
    out += "}}";
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
