#pragma once
// The Orca plugin runtime as the Android app sees it: one embedded CPython, the plugins installed
// under <data dir>/orca_plugins, and the calls the app makes into them. Every function is safe to
// call from any thread; Python work is serialized by the GIL.

#include <functional>
#include <string>

#include <nlohmann/json.hpp>

namespace Slic3r {
class DynamicPrintConfig;
class PresetBundle;
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

// Starts Python (home <data dir>/python, extracted by the app) and loads every installed plugin
// of the signed-in Orca Cloud user. Returns {"plugins": [...]} or {"error": "..."}.
nlohmann::json start(const std::string &cloud_user_id);
// Installs a downloaded package (.py or .whl) for `cloud_uuid` and loads it.
nlohmann::json install(const std::string &package_path, const std::string &cloud_uuid, const std::string &name, const std::string &version);
// Installed plugins with their capabilities and load errors.
nlohmann::json list();

// Pages: the page's HTML (get_ui), messages from the page (on_message), and closing it.
nlohmann::json page_open(const std::string &plugin_key, const std::string &capability);
nlohmann::json page_message(const std::string &plugin_key, const std::string &capability, const std::string &json);
void           page_close(const std::string &plugin_key, const std::string &capability);

nlohmann::json run_script(const std::string &plugin_key, const std::string &capability);

// Runs the slicing-pipeline plugins the print config selects at Step.psGCodePostProcess on an
// exported G-code file, in place (PostProcessor.cpp run_post_process_plugins). Returns
// {"messages": [...]} or {"error": "..."}.
nlohmann::json post_process(const Slic3r::DynamicPrintConfig &config, const std::string &gcode_path, const std::string &host_name,
                            const std::string &output_name);

} // namespace orca::plugins
