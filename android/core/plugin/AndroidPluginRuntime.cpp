#include "AndroidPluginRuntime.hpp"

#include "PluginManager.hpp"
#include "PythonInterpreter.hpp"
#include "pluginTypes/pages/PagesPluginCapability.hpp"
#include "pluginTypes/slicingPipeline/SlicingPipelinePluginCapability.hpp"

#include <libslic3r/PrintConfig.hpp>
#include <libslic3r_version.h>

#include <boost/filesystem.hpp>
#include <boost/log/trivial.hpp>
#include <boost/nowide/cstdlib.hpp>
#include <pybind11/embed.h>

#include <mutex>

using json = nlohmann::json;

namespace orca::plugins {
using namespace Slic3r;

namespace {
std::mutex g_host_mutex;
Host       g_host;
std::mutex g_start_mutex;
bool       g_started = false;

std::string result_status(PluginResult status)
{
    switch (status) {
    case PluginResult::Success: return "success";
    case PluginResult::Skipped: return "skipped";
    case PluginResult::RecoverableError: return "error";
    case PluginResult::FatalError: return "fatal";
    }
    return "unknown";
}

json result_json(const ExecutionResult &r) { return {{"status", result_status(r.status)}, {"message", r.message}, {"data", r.data}}; }

std::shared_ptr<PagesPluginCapability> page(const std::string &plugin_key, const std::string &capability)
{
    auto cap = std::dynamic_pointer_cast<PagesPluginCapability>(
        PluginManager::instance().get_plugin_capability({PluginCapabilityType::Pages, capability, plugin_key}));
    if (!cap)
        throw std::runtime_error("No page '" + capability + "' in plugin " + plugin_key);
    return cap;
}

// CPython's webbrowser module finds no browser on Android; route it to the app instead.
void register_android_browser()
{
    PythonGILState gil;
    if (!gil)
        return;
    try {
        pybind11::exec(R"PY(
import webbrowser, _orca_android
class _AndroidBrowser(webbrowser.BaseBrowser):
    def open(self, url, new=0, autoraise=True):
        _orca_android.open_url(url)
        return True
webbrowser.register("android", None, _AndroidBrowser("android"), preferred=True)
)PY");
    } catch (const std::exception &ex) {
        BOOST_LOG_TRIVIAL(warning) << "webbrowser shim failed: " << ex.what();
    }
}

void load_all()
{
    PluginManager &mgr = PluginManager::instance();
    mgr.discover_plugins(false, false);
    for (const PluginDescriptor &d : mgr.get_plugin_descriptors())
        if (!mgr.is_plugin_loaded(d.plugin_key))
            mgr.load_plugin(d.plugin_key);
}
} // namespace

void set_host(Host host)
{
    std::lock_guard<std::mutex> lock(g_host_mutex);
    g_host = std::move(host);
}

const Host &host() { return g_host; }

json start(const std::string &cloud_user_id)
{
    std::lock_guard<std::mutex> lock(g_start_mutex);
    PluginManager &mgr = PluginManager::instance();
    mgr.set_cloud_user(cloud_user_id);
    if (!g_started) {
        if (!mgr.initialize())
            return {{"error", "Python failed to start: " + PythonInterpreter::instance().last_error()}};
        register_android_browser();
        g_started = true;
    }
    load_all();
    return list();
}

json install(const std::string &package_path, const std::string &cloud_uuid, const std::string &name, const std::string &version)
{
    PluginManager   &mgr = PluginManager::instance();
    PluginDescriptor descriptor;
    descriptor.name    = name;
    descriptor.version = version;
    descriptor.cloud   = CloudPluginState{cloud_uuid, true, false, false, false, false};
    if (mgr.is_plugin_loaded(cloud_uuid))
        return {{"error", "Plugin is loaded; restart the app to update it"}};
    std::string error;
    if (!mgr.install_plugin(boost::filesystem::path(package_path), descriptor, error))
        return {{"error", error}};
    load_all();
    return list();
}

json list()
{
    PluginManager &mgr     = PluginManager::instance();
    json           plugins = json::array();
    for (const PluginDescriptor &d : mgr.get_plugin_descriptors(true)) {
        json caps = json::array();
        for (const auto &cap : mgr.get_plugin_capabilities(d.plugin_key, PluginCapabilityType::Unknown, false))
            caps.push_back({{"name", cap->name()},
                            {"type", plugin_capability_type_to_string(cap->type())},
                            {"enabled", cap->is_enabled()},
                            {"config_ui", cap->config_ui_available()}});
        plugins.push_back({{"key", d.plugin_key},
                           {"name", d.name},
                           {"version", d.installed_version.empty() ? d.version : d.installed_version},
                           {"cloud_uuid", d.cloud_uuid()},
                           {"loaded", mgr.is_plugin_loaded(d.plugin_key)},
                           {"error", d.normalized_error()},
                           {"capabilities", caps}});
    }
    return {{"plugins", plugins}};
}

json page_open(const std::string &plugin_key, const std::string &capability)
{
    auto cap = page(plugin_key, capability);
    cap->set_message_sender([plugin_key, capability](const std::string &message) {
        const auto &fn = host().page_message;
        if (fn)
            fn(plugin_key, capability, message);
    });
    return {{"html", cap->get_ui()}};
}

json page_message(const std::string &plugin_key, const std::string &capability, const std::string &message)
{
    page(plugin_key, capability)->on_message(message);
    return json::object();
}

void page_close(const std::string &plugin_key, const std::string &capability)
{
    try {
        page(plugin_key, capability)->clear_message_sender();
    } catch (const std::exception &) {}
}

json run_script(const std::string &plugin_key, const std::string &capability)
{
    std::string     error;
    ExecutionResult r = PluginManager::instance().run_script_capability(plugin_key, capability, error);
    if (!error.empty())
        return {{"error", error}};
    return result_json(r);
}

json post_process(const DynamicPrintConfig &config, const std::string &gcode_path, const std::string &host_name, const std::string &output_name)
{
    const auto *caps = config.opt<ConfigOptionStrings>("slicing_pipeline_plugin");
    if (caps == nullptr || caps->values.empty() || !g_started)
        return {{"messages", json::array()}};
    boost::nowide::setenv("SLIC3R_PP_OUTPUT_NAME", output_name.c_str(), 1);
    json        messages = json::array();
    std::string error;
    execute_capabilities_from_refs<SlicingPipelinePluginCapability>(
        *caps, config.opt<ConfigOptionStrings>("plugins"), PluginCapabilityType::SlicingPipeline,
        [&](std::shared_ptr<SlicingPipelinePluginCapability> cap, const PluginCapabilityRef &ref) {
            if (!error.empty())
                return;
            SlicingPipelineContext ctx;
            ctx.orca_version = SoftFever_VERSION;
            ctx.step         = SlicingPipelineStepPlugin::psGCodePostProcess;
            ctx.gcode_path   = gcode_path;
            ctx.host         = host_name;
            ctx.output_name  = output_name;
            ctx.full_config  = &config;
            ExecutionResult r;
            try {
                PythonGILState gil;
                r = cap->execute(ctx);
            } catch (const std::exception &ex) {
                error = "Post-processing plugin " + ref.capability_name + " raised an exception: " + ex.what();
                return;
            }
            if (r.status == PluginResult::RecoverableError || r.status == PluginResult::FatalError) {
                error = "Post-processing plugin " + ref.capability_name + " failed: " + r.message;
                return;
            }
            if (!boost::filesystem::exists(gcode_path)) {
                error = "Post-processing plugin " + ref.capability_name + " deleted the G-code file";
                return;
            }
            messages.push_back({{"capability", ref.capability_name}, {"result", result_json(r)}});
        });
    if (!error.empty()) {
        BOOST_LOG_TRIVIAL(error) << error;
        return {{"error", error}};
    }
    return {{"messages", messages}};
}

} // namespace orca::plugins

PYBIND11_EMBEDDED_MODULE(_orca_android, m)
{
    m.def("open_url", [](const std::string &url) {
        const auto &fn = orca::plugins::host().open_url;
        if (fn) {
            pybind11::gil_scoped_release release;
            fn(url);
        }
    });
}
