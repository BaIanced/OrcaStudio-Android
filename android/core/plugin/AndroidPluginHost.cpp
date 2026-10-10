// Android implementations of the plugin host parts that upstream binds to the wxWidgets app:
// orca.host app access (host/PluginHostApp.cpp) and orca.host.ui (host/PluginHostUi.cpp).
#include "AndroidPluginRuntime.hpp"

#include "host/PluginHostBindings.hpp"
#include "host/PluginHostUi.hpp"

#include <libslic3r/PresetBundle.hpp>
#include <pybind11/pybind11.h>

#include <stdexcept>

namespace py = pybind11;

namespace Slic3r {

// PluginConfig (extract_plugin_config.py): the bundle whose edited presets hold plugin overrides.
const PresetBundle* android_active_preset_bundle()
{
    const auto& fn = orca::plugins::host().preset_bundle;
    return fn ? fn() : nullptr;
}

namespace {
PresetBundle* current_preset_bundle()
{
    const auto& fn = orca::plugins::host().preset_bundle;
    PresetBundle* bundle = fn ? fn() : nullptr;
    if (bundle == nullptr)
        throw std::runtime_error("Preset bundle is not available");
    return bundle;
}
} // namespace

// The Android app has no Plater or live GUI model; plugins get the presets and the UI language.
void host_bindings::register_app(py::module_& host)
{
    host.def("preset_bundle", &current_preset_bundle, py::return_value_policy::reference);
    host.def("app_language", []() -> std::string {
        const auto& fn = orca::plugins::host().language;
        return fn ? fn() : std::string("en_US");
    });
}

// Native dialogs and HTML windows are not available on Android yet; Pages capabilities are the
// supported way to show plugin UI. The submodule exists so feature checks (getattr) behave.
void PluginHostUi::RegisterBindings(py::module_& host)
{
    auto ui = host.def_submodule("ui", "Plugin UI (Android: windows and dialogs are not supported; use a Pages capability)");
    ui.def("create_window", [](py::args, py::kwargs) -> py::object {
        throw std::runtime_error("orca.host.ui.create_window is not supported on Android; use a Pages capability");
    });
    ui.def("message", [](py::args, py::kwargs) -> py::object {
        throw std::runtime_error("orca.host.ui.message is not supported on Android");
    });
}

void PluginHostUi::close_windows_for_plugin(const std::string&) {}

} // namespace Slic3r
