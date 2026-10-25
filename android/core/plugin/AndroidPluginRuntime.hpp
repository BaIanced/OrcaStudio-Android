#pragma once
// The Orca plugin runtime as the Android app sees it: one embedded CPython, the plugins installed
// under <data dir>/orca_plugins, and the calls the app makes into them. Every function is safe to
// call from any thread; Python work is serialized by the GIL.

#include <functional>
#include <memory>
#include <string>

#include <nlohmann/json.hpp>

namespace Slic3r {
class DynamicPrintConfig;
class PresetBundle;
class PythonGILState;
} // namespace Slic3r

namespace orca::plugins {

// What the plugin host needs from the app.
struct Host
{
    std::function<Slic3r::PresetBundle *()>                                      preset_bundle;
    std::function<std::string()>                                                 language;
    // A Pages capability posted a message to its open page (window.orca.onMessage).
    std::function<void(const std::string &plugin_key, const std::string &capability, const std::string &json)> page_message;
    // Python's webbrowser.open() (a plugin's sign-in in the real browser).
    std::function<void(const std::string &url)> open_url;
};
void set_host(Host host);
const Host &host();

// Plugins read the preset bundle that Host::preset_bundle hands out without the engine lock, from
// their own Python threads, and may keep the pointer. Python only touches it with the GIL held, so
// the engine changes or replaces the bundle inside one of these: it holds the GIL while Python is
// running and does nothing otherwise. Take it with the engine lock already held, never the other
// way round (plugin code holding the GIL never waits for the engine lock). Nesting is fine.
class BundleWriteGuard
{
public:
    BundleWriteGuard();
    ~BundleWriteGuard();
    BundleWriteGuard(const BundleWriteGuard &)            = delete;
    BundleWriteGuard &operator=(const BundleWriteGuard &) = delete;

    // True while Python is running: a plugin may still hold a pointer to the current bundle.
    bool python_running() const { return m_gil != nullptr; }

private:
    std::unique_ptr<Slic3r::PythonGILState> m_gil;
};

// Starts Python (home <data dir>/python, extracted by the app) and loads every installed plugin
// of the signed-in Orca Cloud user. Returns {"plugins": [...]} or {"error": "..."}.
nlohmann::json start(const std::string &cloud_user_id);
// Installs a downloaded package (.py or .whl) for `cloud_uuid` and loads it.
nlohmann::json install(const std::string &package_path, const std::string &cloud_uuid, const std::string &name, const std::string &version);
// Unloads a plugin and deletes its package; returns list() or {"error": "..."}.
nlohmann::json remove(const std::string &plugin_key);
// Installed plugins with their capabilities and load errors.
nlohmann::json list();

// Pages: the page's HTML (get_ui), messages from the page (on_message), and closing it.
nlohmann::json page_open(const std::string &plugin_key, const std::string &capability);
nlohmann::json page_message(const std::string &plugin_key, const std::string &capability, const std::string &json);
void           page_close(const std::string &plugin_key, const std::string &capability);

// Capability settings (the desktop Plugins dialog's config pane, PluginConfig.cpp
// get_config_response / save_config_response / restore_config_response) in the global layer:
// {"config", "custom_html", "error"} and {"ok", "config", "error"}. `type` is the type key
// (plugin_capability_type_to_string).
nlohmann::json config_open(const std::string &plugin_key, const std::string &capability, const std::string &type);
nlohmann::json config_save(const std::string &plugin_key, const std::string &capability, const std::string &type, const std::string &config);
nlohmann::json config_restore(const std::string &plugin_key, const std::string &capability, const std::string &type);

nlohmann::json run_script(const std::string &plugin_key, const std::string &capability);

// Runs the slicing-pipeline plugins the print config selects at Step.psGCodePostProcess on an
// exported G-code file, in place (PostProcessor.cpp run_post_process_plugins). Returns
// {"messages": [...]} or {"error": "..."}.
nlohmann::json post_process(const Slic3r::DynamicPrintConfig &config, const std::string &gcode_path, const std::string &host_name,
                            const std::string &output_name);

} // namespace orca::plugins
