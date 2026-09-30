#pragma once

// Stand-ins for the few desktop GUI services that ConfigManipulation::toggle_print_fff_options()
// uses, so its body can be compiled unchanged from upstream (see
// scripts/extract_option_toggles.py). The class records the resulting enable/visible state per
// option instead of greying out wx widgets.

#include <map>
#include <string>

#include "libslic3r/Config.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/PrintConfig.hpp"

namespace Slic3r { namespace GUI {

// Label translation is irrelevant here; set_option_label() is a no-op.
#ifndef _L
#define _L(s) std::string(s)
#endif

struct ToggleApp
{
    PresetBundle *preset_bundle = nullptr;
};
ToggleApp &wxGetApp();

// Bambu device capability lookups; none of the ported printers report them.
struct DevPrinterConfigUtil
{
    static bool support_wrapping_detection(const std::string &) { return false; }
};

class ConfigManipulation
{
public:
    bool is_BBL_Printer{false};

    // Last requested state per option; absent means enabled/visible.
    std::map<std::string, bool> field_enabled;
    std::map<std::string, bool> line_visible;

    void toggle_field(const std::string &opt_key, const bool toggle, int = -1) { field_enabled[opt_key] = toggle; }
    void toggle_line(const std::string &opt_key, const bool toggle, int = -1) { line_visible[opt_key] = toggle; }
    void set_option_label(const std::string &, const std::string &, int = -1) {}
    // The desktop applies corrective values (with a dialog); here they only affect the evaluation.
    void apply(DynamicPrintConfig *config, DynamicPrintConfig *new_config) { config->apply(*new_config); }

    void toggle_print_fff_options(DynamicPrintConfig *config, int variant_index = 0, const bool is_global_config = true);
};

}} // namespace Slic3r::GUI
