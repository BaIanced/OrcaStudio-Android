#pragma once

// Thin, UI-agnostic facade over libslic3r for the Android app.
//
// Owns one PresetBundle (system + user presets of the installed vendors) and one Model (the
// plate currently loaded). Every public method is serialized by a mutex, except cancel(), which
// may be called from any thread while slice() is running.

#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

namespace Slic3r {
class Model;
class Print;
class PresetBundle;
class PresetCollection;
class DynamicPrintConfig;
} // namespace Slic3r

namespace orca {

using json           = nlohmann::json;
using ProgressFn     = std::function<void(int percent, const std::string &text)>;

class OrcaEngine
{
public:
    static OrcaEngine &instance();

    // resources_dir: extracted app resources (info/, flush/, ...).
    // data_dir: writable app data; data_dir/system holds the installed vendor profiles.
    // cache_dir: scratch space for temporary files.
    void init(const std::string &resources_dir, const std::string &data_dir, const std::string &cache_dir);

    // (Re)loads all presets from data_dir. Call after installing or removing vendor profiles.
    // Returns { "printers": [ {name, vendor, model, nozzle}... ] }.
    json load_presets();

    // Printers of the loaded bundle, same format as load_presets() (e.g. after saving a user printer).
    json printer_list();

    // Selects the printer and returns the process and filament presets compatible with it,
    // plus the printer's bed geometry:
    // { "prints": [...], "filaments": [...], "bed": [[x,y]...], "max_height": h,
    //   "default_print": name, "default_filament": name }
    json select_printer(const std::string &printer);

    // Loads one or more model files (STL/3MF/OBJ/STEP/AMF) as the current plate, arranges them on
    // the selected printer's bed and writes a preview mesh to mesh_out.
    // Mesh file format: float32 triangles, per vertex x,y,z,nx,ny,nz (18 floats per triangle).
    // Returns { "objects": n, "triangles": n, "size": [x,y,z] }.
    json load_model(const std::vector<std::string> &paths, int copies, const std::string &mesh_out);

    // Slices the current plate with the given presets plus key/value overrides and exports
    // G-code to gcode_out. The toolpath preview is written to preview_out as float32 segments
    // (x0,y0,z0,x1,y1,z1,role; 7 floats per segment) in G-code order.
    // Returns { "gcode": path, "print_time_s": t, "filament_mm": l, "filament_g": w,
    //           "cost": c, "layers": [[z, first_segment]...], "warnings": [...] }.
    // Throws std::exception with a user-readable message on failure.
    json slice(const std::string &print_preset, const std::string &filament_preset, const json &overrides,
               const std::string &gcode_out, const std::string &preview_out, const ProgressFn &progress);

    // --- Settings editor ------------------------------------------------------------------
    // type is "print", "filament" or "printer".

    // Definitions of all options of a preset type:
    // [{key, label, full_label, category, tooltip, type, sidetext, min, max, mode,
    //   enum_values, enum_labels, readonly, multiline, is_code, gui_type}]
    json option_defs(const std::string &type);

    // Serialized values of all options of a preset: {key: value}.
    json preset_values(const std::string &type, const std::string &name);

    // Saves `base` + `overrides` as the user preset `new_name` (overwriting a user preset of that
    // name) and returns the refreshed select_printer() result.
    json save_preset(const std::string &type, const std::string &base, const std::string &new_name, const json &overrides);

    // Deletes a user preset and returns the refreshed select_printer() result.
    json delete_preset(const std::string &type, const std::string &name);

    // Requests cancellation of a running slice(); a no-op otherwise.
    void cancel();

private:
    OrcaEngine();
    ~OrcaEngine();

    json select_printer_locked(const std::string &printer);
    json printer_list_locked();
    Slic3r::PresetCollection &collection(const std::string &type);

    Slic3r::DynamicPrintConfig build_config(const std::string &print_preset, const std::string &filament_preset,
                                            const json &overrides);

    std::mutex                            m_mutex;
    std::unique_ptr<Slic3r::PresetBundle> m_bundle;
    std::unique_ptr<Slic3r::Model>        m_model;
    std::string                           m_printer;
    // Guards m_running_print so cancel() never touches a Print that slice() is destroying.
    std::mutex                            m_cancel_mutex;
    Slic3r::Print                        *m_running_print{nullptr};
};

} // namespace orca
