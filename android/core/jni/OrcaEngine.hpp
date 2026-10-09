#pragma once

// Thin, UI-agnostic facade over libslic3r for the Android app.
//
// Owns one PresetBundle (system + user presets of the installed vendors) and the scene: a Model
// with all objects, spread over one or more plates laid out like the desktop app's plate grid.
// Every public method is serialized by a mutex, except cancel(), which may be called from any
// thread while slice() is running.

#include <array>
#include <deque>
#include <map>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

namespace Slic3r {
class Model;
class ModelObject;
class Print;
class PresetBundle;
class PresetCollection;
class DynamicPrintConfig;
class AABBMesh;
struct Calib_Params;
struct PlateData;
} // namespace Slic3r

namespace orca {

using json       = nlohmann::json;
using ProgressFn = std::function<void(int percent, const std::string &text)>;

// Selection id of an instance in the scene and paint meshes: object * stride + instance (exact in
// a float up to 4096 objects). The app highlights and drags instances by it.
inline constexpr size_t INSTANCE_ID_STRIDE = 4096;

class OrcaEngine
{
public:
    static OrcaEngine &instance();

    // resources_dir: extracted app resources (info/, flush/, ...).
    // data_dir: writable app data; data_dir/system holds the installed vendor profiles.
    // cache_dir: scratch space; the scene preview mesh is written to cache_dir/scene_mesh.bin.
    void init(const std::string &resources_dir, const std::string &data_dir, const std::string &cache_dir);

    // --- Presets ------------------------------------------------------------------------------

    // (Re)loads all presets from data_dir. Call after installing or removing vendor profiles.
    // Returns { "printers": [ {name, vendor, model, nozzle, system}... ] }.
    json load_presets();

    // Printers of the loaded bundle, same format as load_presets().
    json printer_list();

    // Selects the printer and returns the compatible process/filament presets and bed geometry:
    // { "prints": [...], "filaments": [...], "bed": [[x,y]...], "max_height": h,
    //   "default_print": name, "default_filament": name, "bed_types": [...] }
    // The scene is kept.
    json select_printer(const std::string &printer);

    // type is "print", "filament" or "printer".
    json option_defs(const std::string &type);
    json preset_values(const std::string &type, const std::string &name);
    json save_preset(const std::string &type, const std::string &base, const std::string &new_name, const json &overrides);
    json delete_preset(const std::string &type, const std::string &name);

    // --- Scene --------------------------------------------------------------------------------
    // Every scene call returns scene_json() and rewrites the preview mesh: float32 triangles, per
    // vertex x,y,z,nx,ny,nz,object_index,volume_type (8 floats); volume_type is 0 part,
    // 1 negative volume, 2 modifier, 3 support blocker, 4 support enforcer.
    //
    // scene_json(): { "mesh": path, "mesh_version": n, "can_undo", "can_redo",
    //   "plate_size": [w, d], "plates": [{ "origin": [x, y], "bed_type": s, "instances": n }],
    //   "objects": [{ "name", "triangles", "settings": {key: value},
    //                 "volumes": [{ "name", "type", "settings": {...}, "offset", "size" }],
    //                 "instances": [{ "plate", "offset", "rotation" (deg), "scale", "mirror", "size", "min" }] }] }

    json scene();

    // Loads model files onto `plate` (append) or replaces the scene (append=false).
    json load_models(const std::vector<std::string> &paths, bool append, int plate);

    // Partial update of an instance transform; json keys: offset [x,y,z] (mm), rotation [x,y,z]
    // (degrees), scale [x,y,z] (factor), mirror [x,y,z] (+1/-1). Objects are dropped onto the bed.
    json set_transform(int object, int instance, const json &transform);
    json delete_object(int object);
    json delete_instance(int object, int instance);
    // Batch operations on several instances (object, instance), each one undo step.
    using Items = std::vector<std::pair<int, int>>;
    // Adds `copies` copies of each instance, arranged around what is on its plate.
    json duplicate(const Items &items, int copies);
    // Deletes the instances; an object without instances left is deleted.
    json delete_items(const Items &items);
    // Moves the instances by (dx, dy) mm on the bed.
    json move_items(const Items &items, double dx, double dy);
    // Arranges one plate (or every plate for plate < 0).
    json arrange(int plate);
    // Rotates the instance so that the face with world normal `normal` lies on the bed.
    json lay_on_face(int object, int instance, const std::array<double, 3> &normal);
    // Automatic orientation for minimal supports (object < 0: all objects).
    json auto_orient(int object);
    json split(int object, bool to_parts);
    // Horizontal cut at world height z.
    json cut(int object, int instance, double z, bool keep_upper, bool keep_lower, bool flip_upper);
    // Reduces the triangle count to `ratio` (0..1) of the original.
    json simplify(int object, double ratio);
    // Merges duplicate vertices and removes degenerate faces.
    json repair(int object);

    // Adds a volume of `type` (volume_type 1..4) shaped "box"|"cylinder"|"sphere" with `size`
    // [x,y,z] mm, centred at object-local `position`.
    json add_volume(int object, int type, const std::string &shape, const std::array<double, 3> &size,
                    const std::array<double, 3> &position);
    json delete_volume(int object, int volume);
    // Per-object (volume < 0) or per-volume setting; a null value removes the override. With
    // several objects, the setting goes to each of them (volume must be < 0).
    json set_object_setting(const std::vector<int> &objects, int volume, const std::string &key, const json &value);

    json add_plate();
    json delete_plate(int plate);
    json set_plate_bed_type(int plate, const std::string &bed_type);

    json undo();
    json redo();

    // --- Tools needing the current selection (see set_selection) ------------------------------

    // Nearest hit of a world-space ray with any object: { "hit": false } or { "hit": true,
    // "object", "instance", "volume", "facet", "point" [world], "normal" [world], "local" [volume] }.
    json pick(const std::array<double, 3> &origin, const std::array<double, 3> &dir);

    // Paints with a sphere brush of `radius` mm around a pick() result. kind: "fuzzy" (state 1 =
    // fuzzy skin), "support" / "seam" (1 enforce, 2 block), "color" (state = 1-based filament);
    // state 0 erases. `new_stroke` records an undo step. Rewrites the paint overlay mesh (scene
    // mesh format; volume_type 5 fuzzy, 6/7 support enforce/block, 8/9 seam enforce/block,
    // 10 + filament index for colour) and returns scene_json().
    json paint(int object, int instance, int volume, int facet, const std::array<double, 3> &local,
               const std::array<double, 3> &camera, double radius, const std::string &kind, int state, bool new_stroke);
    // Clears one kind of painting (or all for an empty kind) of an object.
    json paint_clear(int object, const std::string &kind);

    // Variable layer height. All return { "profile": [z, h, ...], "min", "max", "height" } plus the
    // scene. `quality` 0..1 (adaptive), `radius` layers (smooth), `delta` mm over `band` mm.
    json layer_profile(int object);
    json layer_adaptive(int object, double quality);
    json layer_smooth(int object, int radius, bool keep_min);
    json layer_adjust(int object, double z, double delta, double band);
    json layer_reset(int object);

    // Per-plate G-code at a height: items [{ "z", "type": "pause"|"color"|"custom", "extra", "color", "extruder" }].
    json set_layer_gcodes(int plate, const json &items);
    // Wipe/prime tower position on a plate (plate coordinates); NaN restores the preset position.
    json set_wipe_tower(int plate, double x, double y);

    // Default flushing volumes (row-major N x N) for the selected filaments and their colours.
    json flush_matrix();

    // Current process option states per the desktop's dependency rules:
    // { "disabled": [keys], "hidden": [keys] }.
    json option_states();

    // --- Adding things ------------------------------------------------------------------------

    // Adds a primitive ("cube", "cylinder", "sphere", "cone") of `size` [x,y,z] mm to `plate`.
    json add_primitive(const std::string &shape, const std::array<double, 3> &size, int plate);
    // Adds extruded text (font file, height and depth in mm) to `plate`.
    json add_text(const std::string &text, const std::string &font_path, double height, double depth, int plate);
    // Adds an extruded SVG drawing scaled to `width` mm, `depth` mm thick, to `plate`.
    json add_svg(const std::string &path, double width, double depth, int plate);
    // Replaces an object's height range modifiers: [{ "from", "to", "settings": {key: value} }].
    json set_layer_ranges(int object, const json &ranges);

    // --- Calibration --------------------------------------------------------------------------

    // Starts a desktop calibration test in a new scene. type: "temp", "flow" (param pass 1/2,
    // "linear"), "pa_line", "pa_tower", "retraction", "max_volumetric", "vfa", "input_shaping_freq",
    // "input_shaping_damp", "cornering"; params: start, end, step (+ type specific). The test's
    // setting changes stay active for slicing until calib_stop().
    json calib_start(const std::string &type, const json &params);
    json calib_stop();

    // --- Projects & files ----------------------------------------------------------------------

    // Saves the whole scene (all plates, settings of the current selection) as a 3MF project.
    json save_project(const std::string &path);
    // Loads a 3MF project (replacing the scene). Adds "project": { "printer", "print",
    // "filaments": [...], "print_overrides": {...}, "filament_overrides": [{...}] } describing its
    // settings relative to presets of the same name.
    json load_project(const std::string &path);
    // Packs a sliced plate's G-code into a ".gcode.3mf" (as Bambu printers expect) with thumbnail.
    json export_gcode_3mf(int plate, const std::string &gcode, const std::string &path);
    // Writes the objects of `plate` (or all for plate < 0) as one binary STL.
    json export_stl(const std::string &path, int plate);
    // Imports user presets (.json / .zip / .orca_printer / .orca_filament ...).
    json import_presets(const std::vector<std::string> &paths);
    // Loads the user's cloud presets like the desktop's cloud sync and saves them as user presets.
    // presets: { name: { option or metadata key: serialized value } }, as open-bamboo-networking's
    // get_user_presets returns them. Returns the printers and the setup like import_presets().
    json load_cloud_presets(const json &presets);
    // Version of an installed vendor's profiles, e.g. "02.00.00.55": { "version" } ("" if absent).
    json vendor_version(const std::string &vendor);
    // Picks a filament preset and colour per loaded tray, like the desktop's filament sync.
    // trays: [{ "filament_id", "filament_type", "color" ("#RRGGBB"), "colors" [...], "ams_id",
    // "slot_id", "name" }] in tray order; filaments: the current slots, as for set_selection().
    // Returns { "filaments": [{ "name", "color" }], "unknown": [{ "tray", "message" }] }.
    json sync_filaments(const json &trays, const json &filaments);
    // File of a user preset: { "path": ... }.
    json preset_file(const std::string &type, const std::string &name);

    // --- Slicing ------------------------------------------------------------------------------

    // The presets and overrides used for slicing and the tools above. filaments:
    // [{ "name", "color" (optional, "#RRGGBB"), "overrides": {...} }]; overrides apply to the
    // process and printer. Validates the preset names.
    void set_selection(const std::string &print, const json &filaments, const json &overrides);

    // Slices one plate with the current selection and exports G-code (with thumbnails) to
    // gcode_out. Preview data goes to preview_dir:
    //   extrusions.bin  15 floats per segment: x0,y0,z0,x1,y1,z1, role, width, height, speed,
    //                   fan, temperature, volumetric rate, filament, G-code line
    //   travels.bin     6 floats per travel: x0,y0,z0,x1,y1,z1
    //   markers.bin     4 floats per marker: x,y,z, kind (0 retract, 1 unretract, 2 seam, 3 wipe)
    // all in plate coordinates and G-code order. Returns
    // { "gcode", "print_time_s", "filament_mm", "filament_g", "cost", "warnings", "origin",
    //   "layers": [{ "z", "extrusion", "travel", "marker", "time" }],
    //   "roles": [{ "role", "time", "filament_m", "filament_g" }], "travel_time",
    //   "ranges": { "speed": [min,max], "fan": ..., "temperature": ..., "volumetric": ..., "width": ..., "height": ... },
    //   "filaments": [{ "m", "g" }] }.
    json slice(int plate, const std::string &gcode_out, const std::string &preview_dir, const ProgressFn &progress);

    // Previews an external G-code file (same outputs as slice(), without G-code export).
    json view_gcode(const std::string &gcode, const std::string &preview_dir, const ProgressFn &progress);

    // Requests cancellation of a running slice(); a no-op otherwise.
    void cancel();

private:
    OrcaEngine();
    ~OrcaEngine();

    struct PlateInfo
    {
        std::string bed_type; // empty: printer/filament default
        bool        has_wipe_tower_pos = false;
        double      wipe_tower_x = 0., wipe_tower_y = 0.;
    };
    struct Snapshot
    {
        std::shared_ptr<Slic3r::Model> model;
        std::vector<PlateInfo>         plates;
    };

    json select_printer_locked(const std::string &printer);
    json printer_list_locked();
    Slic3r::PresetCollection &collection(const std::string &type);
    // Full config of the current selection (presets + overrides + per-filament overrides).
    Slic3r::DynamicPrintConfig selection_config();
    /** Flushing volumes between all filaments from their colours (filaments x filaments, row = from). */
    static std::vector<double> auto_flush_matrix(const Slic3r::DynamicPrintConfig &config);
    // The objects of `plate`, moved to plate coordinates, with the plate's custom G-code.
    std::unique_ptr<Slic3r::Model> plate_model(int plate) const;
    void                  write_paint_mesh();
    struct LayerEdit;
    json                  layer_result(Slic3r::ModelObject &obj);

    // Scene helpers (OrcaScene.cpp). All expect m_mutex to be held.
    void                  require_printer() const;
    Slic3r::ModelObject  &object_at(int object);
    Items                 checked_items(Items items) const; // validated, sorted, without duplicates
    void                  push_undo();
    json                  scene_json();
    json                  commit(); // writes the mesh and returns scene_json()
    void                  write_mesh();
    std::array<double, 4> bed_rect() const; // min x, min y, max x, max y of plate 0
    std::array<double, 2> plate_origin(int plate) const;
    int                   plate_of(const Slic3r::ModelObject &obj, size_t instance) const;
    // Arranges the instances on `plate`; with `movable`, only those move around the others.
    void                  arrange_plate(int plate, const std::vector<std::pair<Slic3r::ModelObject *, size_t>> *movable = nullptr);
    void                  drop_to_bed(Slic3r::ModelObject &obj);
    // Moves instances to their plate's new place after the plate count or bed size changed;
    // `remap` maps old to new plate indices (default: unchanged).
    void                  relayout(size_t old_count, const std::array<double, 4> &old_rect,
                                   const std::function<int(int)> &remap = {});

    std::mutex                            m_mutex;
    std::unique_ptr<Slic3r::PresetBundle> m_bundle;
    std::unique_ptr<Slic3r::Model>        m_model;
    std::vector<PlateInfo>                m_plates{1};
    std::array<double, 4>                 m_layout_rect{0., 0., 200., 200.}; // bed the objects are laid out for
    std::deque<Snapshot>                  m_undo, m_redo;
    std::string                           m_printer;
    std::string                           m_mesh_path;
    std::string                           m_paint_path;
    // Ray-cast acceleration per volume mesh, dropped whenever the scene changes (commit()).
    std::map<const void *, std::shared_ptr<Slic3r::AABBMesh>> m_aabb;
    int                                   m_mesh_version = 0;
    int                                   m_paint_version = 0;
    // Active calibration test: print parameters and the setting changes it needs.
    std::shared_ptr<Slic3r::Calib_Params>       m_calib;
    std::shared_ptr<Slic3r::DynamicPrintConfig> m_calib_config;
    std::string                                 m_calib_name;
    // Adds loaded objects to a plate (centred, arranged) - shared by load_models and add_*.
    json                  place_new_objects(const std::vector<Slic3r::ModelObject *> &objects, int plate);
    // Current selection (set_selection).
    std::string                           m_sel_print;
    json                                  m_sel_filaments = json::array();
    json                                  m_sel_overrides = json::object();
    // Guards m_running_print so cancel() never touches a Print that slice() is destroying.
    std::mutex                            m_cancel_mutex;
    Slic3r::Print                        *m_running_print{nullptr};
    // Slice statistics per G-code file (time, weight, filaments) for the .gcode.3mf's
    // slice_info.config, which printers and Bambu Handy read. Filled by slice().
    std::map<std::string, std::shared_ptr<Slic3r::PlateData>> m_slice_info;
};

} // namespace orca
