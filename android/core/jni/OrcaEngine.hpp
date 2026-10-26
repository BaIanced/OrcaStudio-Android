#pragma once

// Thin, UI-agnostic facade over libslic3r for the Android app.
//
// Owns one PresetBundle (system + user presets of the installed vendors) and the scene: a Model
// with all objects, spread over one or more plates laid out like the desktop app's plate grid.
// Every public method is serialized by a mutex, except cancel(), which may be called from any
// thread while slice() is running.

#include <array>
#include <cstdint>
#include <tuple>
#include <optional>
#include <deque>
#include <map>
#include <functional>
#include <limits>
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
class TriangleMesh;
class TriangleSelector;
class FacetsAnnotation;
} // namespace Slic3r
struct indexed_triangle_set;

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
    //   "default_print": name, "default_filament": name, "bed_types": [...], "default_bed_type": name }
    // The scene is kept.
    json select_printer(const std::string &printer);
    // The plate type plates without their own use, when the printer has it (empty: the printer's
    // default, as on the desktop). Returns { "default_bed_type" } for the selected printer.
    json set_default_bed_type(const std::string &bed_type);
    void set_allow_mixed_temperatures(bool allow) { m_allow_mixed_temp = allow; }

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
    json set_transform(int object, int instance, const json &transform, int volume = -1);
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
    json assemble(const Items &items);
    json mesh_boolean(int object, int src, int tool, const std::string &op, bool keep_tool);
    // Horizontal cut at world height z. float_upper: the upper part keeps its height (its copies get auto_drop off).
    json cut(int object, int instance, double z, bool keep_upper, bool keep_lower, bool flip_upper, bool float_upper = false, double rot_x = 0., double rot_y = 0., double rot_z = 0.);
    // Reduces the triangle count to `ratio` (0..1) of the original.
    json simplify(int object, double ratio);
    // Merges duplicate vertices and removes degenerate faces.
    json repair(int object);
    // Replaces the object's first model part with the single mesh in `path` (desktop "Replace with
    // STL"): settings, modifiers and instances stay, painting is remapped. Sets `module` when not empty.
    json replace_mesh(int object, const std::string &path, const std::string &module);
    // Sets ModelObject::module_name, which the 3MF keeps per object (the OpenSCAD design of an
    // object, see ScadController.kt). No undo step: only used right after the object was added.
    json set_object_module(int object, const std::string &module);

    // Adds a volume of `type` (volume_type 1..4) shaped "box"|"cylinder"|"sphere" with `size`
    // [x,y,z] mm, centred at object-local `position`.
    json add_volume(int object, int type, const std::string &shape, const std::array<double, 3> &size,
                    const std::array<double, 3> &position);
    json delete_volume(int object, int volume);
    // One entry of set_volumes: the mesh comes from `stl` (piece-local frame, not centred) or is an
    // existing volume `keep` (its mesh, settings and painting stay); `matrix` maps it into the object.
    // `keep` assumes a mesh set_volumes made this session (a 3MF reload may re-centre volume meshes).
    struct VolumeSpec { std::string stl; int keep = -1; int type = 0; std::array<double, 16> matrix{}; bool own_matrix = false; std::string name; int anchor = -1; int extruder = -1; };
    // Replaces all the object's volumes with `specs` in one undo step (Build workspace: one volume per
    // shape). Instances keep their pose; with `keep_center`, they shift so the parts stay centred where
    // they were, or, when some specs name an `anchor` (the old volume the same unchanged piece was), so
    // the anchors stay where they were. Empty meshes are skipped; at least one part must remain. A spec's
    // `extruder` sets the volume's filament (1-based; 0 = the object's; -1 = copied as before).
    json set_volumes(int object, const std::vector<VolumeSpec> &specs, bool keep_center);
    // The object the Build workspace edits, or -1: write_mesh gives its volumes the selection id
    // -1000 - volume (instead of object * stride + instance), so the renderer can drag one shape.
    // Its copies are drawn and picked in the design pose: the volumes in the object frame (the copy's
    // turn, scale and mirror = the print pose, left out), standing on the bed at the copy's X/Y;
    // scene_json gives each copy "design": { "offset", "min", "size" } for it.
    json set_build_object(int object);
    // Per-object (volume < 0) or per-volume setting; a null value removes the override. With
    // several objects, the setting goes to each of them (volume must be < 0).
    json set_object_setting(const std::vector<int> &objects, int volume, const std::string &key, const json &value);

    json add_plate();
    // Deletes the plates and the objects on them as one undo step; later plates move up.
    json delete_plates(std::vector<int> plates);
    json set_plate_bed_type(int plate, const std::string &bed_type);

    json undo();
    // Undo steps kept (More > Misc); the oldest are dropped beyond it.
    json set_undo_limit(size_t limit);
    json redo();

    // --- Tools needing the current selection (see set_selection) ------------------------------

    // Nearest hit of a world-space ray with any object: { "hit": false } or { "hit": true,
    // "object", "instance", "volume", "facet", "point" [world], "normal" [world], "local" [volume] }.
    // [only] >= 0: that object alone (the Build workspace, where the others are hidden).
    /** [snap] > 0 (world mm): the hit moves to the nearest corner / sharp edge of its facet within that distance ("snap": "vertex" | "edge"). */
    json pick(const std::array<double, 3> &origin, const std::array<double, 3> &dir, int only = -1, double snap = 0.);

    // Paints with a sphere brush of `radius` mm around a pick() result. kind: "fuzzy" (state 1 =
    // fuzzy skin), "support" / "seam" (1 enforce, 2 block), "color" (state = 1-based filament);
    // state 0 erases. `new_stroke` records an undo step. Rewrites the paint overlay mesh (scene
    // mesh format; volume_type 5 fuzzy, 6/7 support enforce/block, 8/9 seam enforce/block,
    // 10 + filament index for colour) and returns scene_json(). [section] = the section view's world plane
    // {nx, ny, nz, d} (kept where n.p <= d) or empty: the brush leaves what it cuts away unpainted.
    // [hits] are the brush positions of one stroke in order (several at once when the app's samples queued up): the
    // painting and the paint mesh are rebuilt once for all of them, not per sample (user r314: the brush fell behind).
    struct PaintHit {
        int object, instance, volume, facet;
        std::array<double, 3> local;
        // Continues the line from the stroke's previous hit (a capsule between them, as the desktop's DoublePointCursor).
        bool join = false;
    };
    // [edge_angle] > 0 (degrees) = edge detection, as the desktop's smart fill angle: a stroke paints only the
    // surface it started on, up to edges sharper than that angle between neighbouring faces.
    // [shape], as the desktop's painting tools: "sphere" / "circle" brush; "triangle" paints the hit triangle; "fill"
    // paints the hit's same-state region (bucket fill, bounded by [edge_angle] when > 0) at a line's first hit;
    // "height" paints every part of the object in [hit z, hit z + height] (height range).
    // [overhang] > 0 (degrees): a brush paints only faces that overhang by more than that (support painting's "on overhangs only").
    json paint(const std::vector<PaintHit> &hits, const std::array<double, 3> &camera, double radius, const std::string &kind,
               int state, bool new_stroke, const std::vector<double> &section = {}, double edge_angle = 0.,
               const std::string &shape = "sphere", double height = 2., double overhang = 0.,
               double guard = std::numeric_limits<double>::quiet_NaN(), bool over_colours = false, uint32_t locked = 0);
    // Clears one kind of painting (or all for an empty kind) of an object.
    json paint_clear(int object, const std::string &kind);
    // Ends a fill preview by restoring the annotation from before the stroke (the finger slid off the model).
    json paint_fill_cancel();
    // Eyedropper: {"state": the paint state under the hit (0 = unpainted), "base": the part's extruder}.
    json paint_state_at(int object, int volume, int facet, const std::array<double, 3> &local, const std::string &kind);
    // Colour painting: each separate shell of the object gets its own filament, largest first.
    json paint_by_part(int object);
    // Regions view: {"lines": [x0,y0,z0, x1,y1,z1, ...]} world segments of one copy's crease edges (faces meeting at more
    // than [angle] degrees) and open edges.
    json crease_lines(int object, int instance, double angle);
    // Read-only mesh report of one copy, per model part: faces, open edges, shells, volume, zero-area faces and (when
    // [thickness] > 0) faces whose wall is thinner than that many mm; plus up to 400 of the thin spots as world x,y,z.
    json check_mesh(int object, int instance, double thickness);
    json paint_gap_fill(int object, const std::string &kind, double area, uint32_t locked = 0);
    json paint_remap(int object, const std::vector<int> &dest, bool base = false);
    json paint_coverage(int object);

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
    // { "disabled": [keys], "hidden": [keys] }. [object]: per-object / part / range settings (string values) applied
    // over the process first, as the desktop's ObjectSettings does; null for the process itself.
    json option_states(const json &object = nullptr);

    // --- Adding things ------------------------------------------------------------------------

    // Adds a primitive ("cube", "cylinder", "sphere", "cone") of `size` [x,y,z] mm to `plate`.
    json add_primitive(const std::string &shape, const std::array<double, 3> &size, int plate, double round = 0.);
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
    // sliced: the G-code path slice() returned, when gcode is a (post-processed) copy of it; its
    // slice statistics and used filaments go into slice_info.config.
    json export_gcode_3mf(int plate, const std::string &gcode, const std::string &path, const std::string &sliced);
    // Writes the objects of `plate` (or all for plate < 0) as one binary STL.
    json export_stl(const std::string &path, int plate);
    // Renders a mesh file (e.g. the OpenSCAD preview STL) to a square PNG with the CPU thumbnailer
    // (no scene state needed). `size` px is clamped to 128..1024. Returns { "png" (base64), "width",
    // "height" }. Used by the AI tool layer's design_snapshot.
    json snapshot(const std::string &path, int size);
    // Imports user presets (.json / .zip / .orca_printer / .orca_filament ...).
    json import_presets(const std::vector<std::string> &paths);
    // Loads the user's cloud presets like the desktop's cloud sync and saves them as user presets.
    // presets: { name: { option or metadata key: serialized value } }, as open-bamboo-networking's
    // get_user_presets returns them. Returns the printers and the setup like import_presets().
    json load_cloud_presets(const json &presets);
    // Orca Cloud upload: new / changed user presets with their values, and the result of an upload.
    json cloud_uploads(const std::string &user_id);
    json mark_uploaded(const std::string &type, const std::string &name, const std::string &setting_id,
                       const std::string &sync_info, long long updated_time);
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
    //   extrusions.bin  15 floats per segment: x0,y0,z0,x1,y1,z1, role + 32 * (depth label + 1), width, height, speed,
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

    // For the plugin runtime: the preset bundle (read by plugins without the engine lock, as the
    // desktop's plugins read the GUI's bundle; every change to it is made under a
    // plugins::BundleWriteGuard, and a bundle a plugin may hold is never freed), and the full config the last slice of `plate` used
    // (null before the first slice), which the G-code post-process plugins read.
    Slic3r::PresetBundle                             *preset_bundle() { return m_bundle.get(); }
    std::shared_ptr<const Slic3r::DynamicPrintConfig> slice_config(int plate);

private:
    OrcaEngine();
    ~OrcaEngine();

    // The selected printer has plate types (Bambu Lab, or support_multi_bed_types).
    bool has_bed_types() const;
    // Plate type of a plate without its own: the user's choice, else the printer's; empty without plate types.
    std::string default_bed_type() const;

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
    void                  trim_undo();
    // Runs an edit that replaces the scene; if it throws, the scene, history and calibration
    // state are put back as they were (Muse Task 12 finding 7).
    json                  rollback_on_error(const std::function<json()> &edit);
    json                  load_models_locked(const std::vector<std::string> &paths, bool append, int plate);
    json                  calib_start_locked(const std::string &type, const json &params);
    json                  scene_json();
    json                  commit(); // writes the mesh and returns scene_json()
    void                  write_mesh();
    std::array<double, 4> bed_rect() const; // min x, min y, max x, max y of plate 0
    std::array<double, 2> plate_origin(int plate) const;
    // The prime tower the plate prints, in plate coordinates: the plate's own or the preset's position kept on the bed using the
    // slicer's footprint estimate (depth, brim, cone), as the desktop's placement clamp does. Empty when the plate prints none.
    struct TowerBox { double x, y, width, depth, height; bool clash = false; };
    std::optional<TowerBox> tower_box(const Slic3r::DynamicPrintConfig &config, int plate) const;
    std::vector<std::optional<TowerBox>> m_tower_boxes; // per plate, as of the last write_mesh()
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
    // Bundles replaced by load_presets() while plugins ran: a plugin may still hold one.
    std::vector<std::unique_ptr<Slic3r::PresetBundle>> m_retired_bundles;
    std::unique_ptr<Slic3r::Model>        m_model;
    std::vector<PlateInfo>                m_plates{1};
    std::array<double, 4>                 m_layout_rect{0., 0., 200., 200.}; // bed the objects are laid out for
    std::deque<Snapshot>                  m_undo, m_redo;
    size_t                                m_undo_limit = 200;
    std::string                           m_printer;
    std::string                           m_preferred_bed_type; // set_default_bed_type
    bool                                  m_allow_mixed_temp = false; // slice filaments with far apart nozzle temperatures together
    std::string                           m_mesh_path;
    std::string                           m_paint_path;
    // Ray-cast acceleration per volume mesh, dropped whenever the scene changes (commit()).
    std::map<const void *, std::shared_ptr<Slic3r::AABBMesh>> m_aabb;
    // Facet neighbours per volume mesh (edge snapping in pick()), dropped with m_aabb.
    std::map<const void *, std::shared_ptr<std::vector<std::array<int32_t, 3>>>> m_neighbors;
    int                                   m_mesh_version = 0;
    int                                   m_build_object = -1; // see set_build_object()
    // The Build workspace's object is drawn and picked in its design pose (see set_build_object()): a plain
    // translation, given here per copy; nullopt = any other object (its copy's own matrix).
    std::optional<std::array<double, 3>>  design_offset(size_t object, size_t instance) const;
    int                                   m_paint_version = 0;
    // The selector of the volume being painted stays live between paint calls while its annotation is unchanged:
    // deserializing it on every call grew slower with every painted triangle (user r316). Holds the mesh it points to.
    struct LivePaint {
        const Slic3r::FacetsAnnotation             *annotation = nullptr;
        std::shared_ptr<const Slic3r::TriangleMesh> mesh;
        uint64_t                                    timestamp = 0;
        std::shared_ptr<Slic3r::TriangleSelector>   selector;
        // The annotation's timestamp before the last paint call and the state it painted: a state that was empty
        // then and wasn't painted is still empty (painting only takes triangles from the other states).
        uint64_t                                    previous = 0;
        int                                         state = -1;
    };
    LivePaint                             m_live_paint;
    // The stroke's last painted hit, start of the next joined capsule.
    std::optional<PaintHit>               m_paint_prev;
    // Bands: the stroke's painted height range on one object copy (world z); a drag extends it.
    struct BandStroke {
        int   object, instance;
        float lo, hi;
    };
    std::optional<BandStroke>             m_band;
    // Edge detection: the faces of the stroke's starting surface on one volume mesh (flood over edges up to the
    // angle), and that mesh's face neighbours, kept while the mesh is the same.
    struct PaintRegion {
        std::shared_ptr<const Slic3r::TriangleMesh> mesh;
        std::vector<int>                            neighbors; // 3 per face, -1 = open edge
        int                                         object = -1, volume = -1;
        std::vector<bool>                           faces;
    };
    PaintRegion                           m_paint_region;
    // Hold-to-preview fill: the stroke's target annotation as it was before the stroke; each fill hit repaints from it, so
    // the fill follows the finger, and paint_fill_cancel puts it back (OrcaTools.cpp).
    struct FillPreview;
    std::shared_ptr<FillPreview>          m_fill_preview;
    // The undo snapshot the current stroke pushed (nullptr: none), so a fill cancel pops only its own step.
    // Held, not a raw pointer: a freed snapshot's address can come back for a new one.
    std::shared_ptr<Slic3r::Model>        m_stroke_undo;
    // Another edit since the preview was made (its undo top changed) leaves the preview stale: its volume may be gone.
    void                                  drop_stale_fill_preview();
    // Guard plane: the stroke keeps below (true) or above the plane, the side where it started.
    bool                                  m_guard_below = true;
    // Painted triangles per (mesh, annotation timestamp, state): write_paint_mesh re-extracts only what changed.
    using PaintKey = std::tuple<const void *, uint64_t, int>;
    std::map<PaintKey, std::shared_ptr<indexed_triangle_set>> m_paint_facets;
    // Their file bytes per copy (key, overlay code, copy code) with the transform they were made with.
    struct PaintBytes {
        std::array<double, 16>              trafo;
        std::shared_ptr<std::vector<float>> data;
    };
    std::map<std::tuple<PaintKey, float, float>, PaintBytes> m_paint_bytes;
    // Last write_paint_mesh: time extracting triangles from selectors, time making file bytes, triangles written.
    double                                m_paint_extract_ms = 0., m_paint_bytes_ms = 0.;
    size_t                                m_paint_triangles = 0;
    // Active calibration test: print parameters and the setting changes it needs.
    std::shared_ptr<Slic3r::Calib_Params>       m_calib;
    std::shared_ptr<Slic3r::DynamicPrintConfig> m_calib_config;
    std::map<int, std::shared_ptr<const Slic3r::DynamicPrintConfig>> m_slice_configs;
    std::string                                 m_calib_name;
    // Undo depth right after the calibration's own snapshot: undoing below it leaves the test.
    size_t                                      m_calib_undo_depth = 0;
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
