// Name -> engine function table behind OrcaNative.call(). Argument names are the Kotlin side's
// (camelCase); see EngineApi.kt.

#include "EngineCalls.hpp"

#include <cmath>
#include <functional>
#include <stdexcept>
#include <unordered_map>

namespace orca {

namespace {

std::array<double, 3> vec3(const json &j)
{
    if (j.is_null())
        return {std::nan(""), std::nan(""), std::nan("")};
    return {j.at(0).get<double>(), j.at(1).get<double>(), j.at(2).get<double>()};
}

double number_or_nan(const json &a, const char *key) { return a.contains(key) && a[key].is_number() ? a[key].get<double>() : std::nan(""); }

std::vector<std::string> strings(const json &j)
{
    std::vector<std::string> out;
    for (const json &s : j)
        out.push_back(s.get<std::string>());
    return out;
}

// [[object, instance], ...]
OrcaEngine::Items item_list(const json &j)
{
    OrcaEngine::Items out;
    for (const json &it : j)
        out.emplace_back(it.at(0).get<int>(), it.at(1).get<int>());
    return out;
}

// "objects": [..], else the single "object".
std::vector<int> object_list(const json &a)
{
    return a.contains("objects") ? a["objects"].get<std::vector<int>>() : std::vector<int>{a.at("object").get<int>()};
}

using Handler = std::function<json(OrcaEngine &, const json &)>;

const std::unordered_map<std::string, Handler> &handlers()
{
    static const std::unordered_map<std::string, Handler> map = {
        // Presets
        {"loadPresets", [](OrcaEngine &e, const json &) { return e.load_presets(); }},
        {"printerList", [](OrcaEngine &e, const json &) { return e.printer_list(); }},
        {"selectPrinter", [](OrcaEngine &e, const json &a) { return e.select_printer(a.at("printer")); }},
        {"setDefaultBedType", [](OrcaEngine &e, const json &a) { return e.set_default_bed_type(a.value("bedType", std::string())); }},
        {"setAllowMixedTemperatures", [](OrcaEngine &e, const json &a) { e.set_allow_mixed_temperatures(a.value("allow", false)); return json::object(); }},
        {"optionDefs", [](OrcaEngine &e, const json &a) { return json{{"defs", e.option_defs(a.at("type"))}}; }},
        {"presetValues", [](OrcaEngine &e, const json &a) { return e.preset_values(a.at("type"), a.at("name")); }},
        {"savePreset", [](OrcaEngine &e, const json &a) { return e.save_preset(a.at("type"), a.at("base"), a.at("name"), a.at("overrides")); }},
        {"deletePreset", [](OrcaEngine &e, const json &a) { return e.delete_preset(a.at("type"), a.at("name")); }},
        {"setSelection", [](OrcaEngine &e, const json &a) {
             e.set_selection(a.at("print"), a.at("filaments"), a.value("overrides", json::object()));
             return json{{"ok", true}};
         }},
        {"optionStates", [](OrcaEngine &e, const json &a) { return e.option_states(a.value("object", json())); }},
        {"loadCloudPresets", [](OrcaEngine &e, const json &a) { return e.load_cloud_presets(a.at("presets")); }},
        {"cloudUploads", [](OrcaEngine &e, const json &a) { return json{{"presets", e.cloud_uploads(a.at("userId"))}}; }},
        {"markUploaded", [](OrcaEngine &e, const json &a) {
             return e.mark_uploaded(a.at("type"), a.at("name"), a.at("settingId"), a.value("syncInfo", std::string()), a.value("updatedTime", 0LL));
         }},
        {"vendorVersion", [](OrcaEngine &e, const json &a) { return e.vendor_version(a.at("vendor")); }},
        {"syncFilaments", [](OrcaEngine &e, const json &a) { return e.sync_filaments(a.at("trays"), a.at("filaments")); }},
        // Scene
        {"scene", [](OrcaEngine &e, const json &) { return e.scene(); }},
        {"loadModels", [](OrcaEngine &e, const json &a) { return e.load_models(strings(a.at("paths")), a.value("append", false), a.value("plate", 0)); }},
        {"setTransform", [](OrcaEngine &e, const json &a) { return e.set_transform(a.at("object"), a.at("instance"), a.at("transform"), a.value("volume", -1)); }},
        {"deleteObject", [](OrcaEngine &e, const json &a) { return e.delete_object(a.at("object")); }},
        {"deleteInstance", [](OrcaEngine &e, const json &a) { return e.delete_instance(a.at("object"), a.at("instance")); }},
        {"duplicate", [](OrcaEngine &e, const json &a) { return e.duplicate(item_list(a.at("items")), a.value("copies", 1)); }},
        {"deleteItems", [](OrcaEngine &e, const json &a) { return e.delete_items(item_list(a.at("items"))); }},
        {"moveItems", [](OrcaEngine &e, const json &a) { return e.move_items(item_list(a.at("items")), a.at("dx"), a.at("dy")); }},
        {"arrange", [](OrcaEngine &e, const json &a) { return e.arrange(a.value("plate", -1)); }},
        {"layOnFace", [](OrcaEngine &e, const json &a) { return e.lay_on_face(a.at("object"), a.at("instance"), vec3(a.at("normal"))); }},
        {"autoOrient", [](OrcaEngine &e, const json &a) { return e.auto_orient(a.value("object", -1)); }},
        {"split", [](OrcaEngine &e, const json &a) { return e.split(a.at("object"), a.value("toParts", false)); }},
        {"assemble", [](OrcaEngine &e, const json &a) { return e.assemble(item_list(a.at("items"))); }},
        {"meshBoolean", [](OrcaEngine &e, const json &a) {
             return e.mesh_boolean(a.at("object"), a.at("src"), a.at("tool"), a.at("op").get<std::string>(), a.value("keepTool", false)); }},
        {"cut", [](OrcaEngine &e, const json &a) {
             return e.cut(a.at("object"), a.value("instance", 0), a.at("z"), a.value("keepUpper", true), a.value("keepLower", true),
                          a.value("flipUpper", false), a.value("floatUpper", false),
                          a.value("rotX", 0.0), a.value("rotY", 0.0), a.value("rotZ", 0.0));
         }},
        {"simplify", [](OrcaEngine &e, const json &a) { return e.simplify(a.at("object"), a.at("ratio")); }},
        {"repair", [](OrcaEngine &e, const json &a) { return e.repair(a.at("object")); }},
        {"replaceMesh", [](OrcaEngine &e, const json &a) { return e.replace_mesh(a.at("object"), a.at("path"), a.value("module", std::string())); }},
        {"setObjectModule", [](OrcaEngine &e, const json &a) { return e.set_object_module(a.at("object"), a.at("module")); }},
        {"addVolume", [](OrcaEngine &e, const json &a) {
             return e.add_volume(a.at("object"), a.at("type"), a.value("shape", "box"), vec3(a.at("size")),
                                 a.contains("position") ? vec3(a["position"]) : vec3(json()));
         }},
        {"deleteVolume", [](OrcaEngine &e, const json &a) { return e.delete_volume(a.at("object"), a.at("volume")); }},
        {"setVolumes", [](OrcaEngine &e, const json &a) {
             std::vector<OrcaEngine::VolumeSpec> specs;
             for (const json &v : a.at("volumes")) {
                 OrcaEngine::VolumeSpec s;
                 s.stl  = v.value("stl", std::string());
                 s.keep = v.value("keep", -1);
                 s.type = v.value("type", 0);
                 s.name = v.value("name", std::string());
                 s.anchor = v.value("anchor", -1);
                 s.extruder = v.value("extruder", -1);
                 // A kept volume without a matrix keeps its own placement (modifiers next to the shapes).
                 const json &m = v.value("matrix", json::array());
                 s.own_matrix = m.empty() && s.keep >= 0;
                 if (!s.own_matrix && m.size() != 16)
                     throw std::runtime_error("matrix needs 16 numbers");
                 for (size_t i = 0; i < m.size() && i < 16; ++i)
                     s.matrix[i] = m.at(i).get<double>();
                 if (s.keep < 0 && s.stl.empty())
                     throw std::runtime_error("volume needs stl or keep");
                 specs.push_back(std::move(s));
             }
             return e.set_volumes(a.at("object"), specs, a.value("keepCenter", false));
         }},
        {"setBuildObject", [](OrcaEngine &e, const json &a) { return e.set_build_object(a.at("object")); }},
        {"setObjectSetting", [](OrcaEngine &e, const json &a) {
             return e.set_object_setting(object_list(a), a.value("volume", -1), a.at("key"), a.contains("value") ? a["value"] : json());
         }},
        {"setLayerRanges", [](OrcaEngine &e, const json &a) { return e.set_layer_ranges(a.at("object"), a.at("ranges")); }},
        {"addPlate", [](OrcaEngine &e, const json &) { return e.add_plate(); }},
        {"deletePlate", [](OrcaEngine &e, const json &a) {
             return e.delete_plates(a.contains("plates") ? a["plates"].get<std::vector<int>>() : std::vector<int>{a.at("plate").get<int>()});
         }},
        {"setPlateBedType", [](OrcaEngine &e, const json &a) { return e.set_plate_bed_type(a.at("plate"), a.value("bedType", "")); }},
        {"undo", [](OrcaEngine &e, const json &) { return e.undo(); }},
        {"redo", [](OrcaEngine &e, const json &) { return e.redo(); }},
        {"setUndoLimit", [](OrcaEngine &e, const json &a) { return e.set_undo_limit(a.at("limit").get<size_t>()); }},
        {"addPrimitive", [](OrcaEngine &e, const json &a) { return e.add_primitive(a.at("shape"), vec3(a.at("size")), a.value("plate", 0)); }},
        {"addText", [](OrcaEngine &e, const json &a) {
             return e.add_text(a.at("text"), a.at("font"), a.value("height", 10.0), a.value("depth", 2.0), a.value("plate", 0));
         }},
        {"addSvg", [](OrcaEngine &e, const json &a) { return e.add_svg(a.at("path"), a.value("width", 50.0), a.value("depth", 2.0), a.value("plate", 0)); }},
        // Tools
        {"pick", [](OrcaEngine &e, const json &a) { return e.pick(vec3(a.at("origin")), vec3(a.at("dir")), a.value("object", -1), a.value("snap", 0.)); }},
        {"paint", [](OrcaEngine &e, const json &a) {
             // "hits": [{object, instance, volume, facet, local}...] in stroke order, or one hit's fields at the top level.
             std::vector<OrcaEngine::PaintHit> hits;
             auto hit = [](const json &h) {
                 return OrcaEngine::PaintHit{h.at("object"), h.at("instance"), h.at("volume"), h.at("facet"), vec3(h.at("local")), h.value("join", false)};
             };
             if (a.contains("hits"))
                 for (const json &h : a.at("hits")) hits.push_back(hit(h));
             else
                 hits.push_back(hit(a));
             return e.paint(hits, vec3(a.at("camera")), a.value("radius", 2.0), a.value("kind", "fuzzy"), a.value("state", 1),
                            a.value("newStroke", false),
                            a.contains("section") && a["section"].is_array() ? a["section"].get<std::vector<double>>() : std::vector<double>{},
                            a.value("edgeAngle", 0.0), a.value("shape", std::string("sphere")), a.value("height", 2.0), a.value("overhang", 0.0),
                            a.contains("guard") && a["guard"].is_number() ? a["guard"].get<double>() : std::numeric_limits<double>::quiet_NaN(),
                            a.value("overColours", false), a.value("locked", uint32_t(0)));
         }},
        {"paintClear", [](OrcaEngine &e, const json &a) { return e.paint_clear(a.at("object"), a.value("kind", "")); }},
        {"paintFillCancel", [](OrcaEngine &e, const json &) { return e.paint_fill_cancel(); }},
        {"paintStateAt", [](OrcaEngine &e, const json &a) {
             return e.paint_state_at(a.at("object"), a.at("volume"), a.at("facet"), vec3(a.at("local")), a.at("kind"));
         }},
        {"paintByPart", [](OrcaEngine &e, const json &a) { return e.paint_by_part(a.at("object")); }},
        {"checkMesh", [](OrcaEngine &e, const json &a) { return e.check_mesh(a.at("object"), a.value("instance", 0), a.value("thickness", 0.4)); }},
        {"creaseLines", [](OrcaEngine &e, const json &a) { return e.crease_lines(a.at("object"), a.value("instance", 0), a.value("angle", 30.0)); }},
        {"paintRemap", [](OrcaEngine &e, const json &a) { return e.paint_remap(a.at("object"), a.at("dest").get<std::vector<int>>(), a.value("base", false)); }},
        {"paintCoverage", [](OrcaEngine &e, const json &a) { return e.paint_coverage(a.at("object")); }},
        {"paintGapFill", [](OrcaEngine &e, const json &a) { return e.paint_gap_fill(a.at("object"), a.at("kind"), a.at("area"), a.value("locked", uint32_t(0))); }},
        {"layerProfile", [](OrcaEngine &e, const json &a) { return e.layer_profile(a.at("object")); }},
        {"layerAdaptive", [](OrcaEngine &e, const json &a) { return e.layer_adaptive(a.at("object"), a.value("quality", 0.5)); }},
        {"layerSmooth", [](OrcaEngine &e, const json &a) { return e.layer_smooth(a.at("object"), a.value("radius", 5), a.value("keepMin", false)); }},
        {"layerAdjust", [](OrcaEngine &e, const json &a) { return e.layer_adjust(a.at("object"), a.at("z"), a.at("delta"), a.value("band", 2.0)); }},
        {"layerReset", [](OrcaEngine &e, const json &a) { return e.layer_reset(a.at("object")); }},
        {"setLayerGcodes", [](OrcaEngine &e, const json &a) { return e.set_layer_gcodes(a.at("plate"), a.at("items")); }},
        {"setWipeTower", [](OrcaEngine &e, const json &a) { return e.set_wipe_tower(a.at("plate"), number_or_nan(a, "x"), number_or_nan(a, "y")); }},
        {"flushMatrix", [](OrcaEngine &e, const json &) { return e.flush_matrix(); }},
        {"calibStart", [](OrcaEngine &e, const json &a) { return e.calib_start(a.at("type"), a.value("params", json::object())); }},
        {"calibStop", [](OrcaEngine &e, const json &) { return e.calib_stop(); }},
        // Files
        {"saveProject", [](OrcaEngine &e, const json &a) { return e.save_project(a.at("path")); }},
        {"loadProject", [](OrcaEngine &e, const json &a) { return e.load_project(a.at("path")); }},
        {"exportGcode3mf", [](OrcaEngine &e, const json &a) { return e.export_gcode_3mf(a.at("plate"), a.at("gcode"), a.at("path"), a.value("sliced", std::string())); }},
        {"exportStl", [](OrcaEngine &e, const json &a) { return e.export_stl(a.at("path"), a.value("plate", -1)); }},
        {"snapshot", [](OrcaEngine &e, const json &a) { return e.snapshot(a.at("stl").get<std::string>(), a.value("size", 512)); }},
        {"importPresets", [](OrcaEngine &e, const json &a) { return e.import_presets(strings(a.at("paths"))); }},
        {"presetFile", [](OrcaEngine &e, const json &a) { return e.preset_file(a.at("type"), a.at("name")); }},
    };
    return map;
}

} // namespace

json dispatch(OrcaEngine &engine, const std::string &method, const json &args)
{
    const auto it = handlers().find(method);
    if (it == handlers().end())
        throw std::runtime_error("Unknown engine call: " + method);
    return it->second(engine, args);
}

} // namespace orca
