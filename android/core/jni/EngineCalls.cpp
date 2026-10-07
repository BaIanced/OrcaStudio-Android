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

using Handler = std::function<json(OrcaEngine &, const json &)>;

const std::unordered_map<std::string, Handler> &handlers()
{
    static const std::unordered_map<std::string, Handler> map = {
        // Presets
        {"loadPresets", [](OrcaEngine &e, const json &) { return e.load_presets(); }},
        {"printerList", [](OrcaEngine &e, const json &) { return e.printer_list(); }},
        {"selectPrinter", [](OrcaEngine &e, const json &a) { return e.select_printer(a.at("printer")); }},
        {"optionDefs", [](OrcaEngine &e, const json &a) { return json{{"defs", e.option_defs(a.at("type"))}}; }},
        {"presetValues", [](OrcaEngine &e, const json &a) { return e.preset_values(a.at("type"), a.at("name")); }},
        {"savePreset", [](OrcaEngine &e, const json &a) { return e.save_preset(a.at("type"), a.at("base"), a.at("name"), a.at("overrides")); }},
        {"deletePreset", [](OrcaEngine &e, const json &a) { return e.delete_preset(a.at("type"), a.at("name")); }},
        {"setSelection", [](OrcaEngine &e, const json &a) {
             e.set_selection(a.at("print"), a.at("filaments"), a.value("overrides", json::object()));
             return json{{"ok", true}};
         }},
        {"optionStates", [](OrcaEngine &e, const json &) { return e.option_states(); }},
        {"loadCloudPresets", [](OrcaEngine &e, const json &a) { return e.load_cloud_presets(a.at("presets")); }},
        {"vendorVersion", [](OrcaEngine &e, const json &a) { return e.vendor_version(a.at("vendor")); }},
        {"syncFilaments", [](OrcaEngine &e, const json &a) { return e.sync_filaments(a.at("trays"), a.at("filaments")); }},
        // Scene
        {"scene", [](OrcaEngine &e, const json &) { return e.scene(); }},
        {"loadModels", [](OrcaEngine &e, const json &a) { return e.load_models(strings(a.at("paths")), a.value("append", false), a.value("plate", 0)); }},
        {"setTransform", [](OrcaEngine &e, const json &a) { return e.set_transform(a.at("object"), a.at("instance"), a.at("transform")); }},
        {"deleteObject", [](OrcaEngine &e, const json &a) { return e.delete_object(a.at("object")); }},
        {"deleteInstance", [](OrcaEngine &e, const json &a) { return e.delete_instance(a.at("object"), a.at("instance")); }},
        {"duplicate", [](OrcaEngine &e, const json &a) { return e.duplicate(a.at("object"), a.value("copies", 1)); }},
        {"arrange", [](OrcaEngine &e, const json &a) { return e.arrange(a.value("plate", -1)); }},
        {"layOnFace", [](OrcaEngine &e, const json &a) { return e.lay_on_face(a.at("object"), a.at("instance"), vec3(a.at("normal"))); }},
        {"autoOrient", [](OrcaEngine &e, const json &a) { return e.auto_orient(a.value("object", -1)); }},
        {"split", [](OrcaEngine &e, const json &a) { return e.split(a.at("object"), a.value("toParts", false)); }},
        {"cut", [](OrcaEngine &e, const json &a) {
             return e.cut(a.at("object"), a.value("instance", 0), a.at("z"), a.value("keepUpper", true), a.value("keepLower", true),
                          a.value("flipUpper", false));
         }},
        {"simplify", [](OrcaEngine &e, const json &a) { return e.simplify(a.at("object"), a.at("ratio")); }},
        {"repair", [](OrcaEngine &e, const json &a) { return e.repair(a.at("object")); }},
        {"addVolume", [](OrcaEngine &e, const json &a) {
             return e.add_volume(a.at("object"), a.at("type"), a.value("shape", "box"), vec3(a.at("size")),
                                 a.contains("position") ? vec3(a["position"]) : vec3(json()));
         }},
        {"deleteVolume", [](OrcaEngine &e, const json &a) { return e.delete_volume(a.at("object"), a.at("volume")); }},
        {"setObjectSetting", [](OrcaEngine &e, const json &a) {
             return e.set_object_setting(a.at("object"), a.value("volume", -1), a.at("key"), a.contains("value") ? a["value"] : json());
         }},
        {"setLayerRanges", [](OrcaEngine &e, const json &a) { return e.set_layer_ranges(a.at("object"), a.at("ranges")); }},
        {"addPlate", [](OrcaEngine &e, const json &) { return e.add_plate(); }},
        {"deletePlate", [](OrcaEngine &e, const json &a) { return e.delete_plate(a.at("plate")); }},
        {"setPlateBedType", [](OrcaEngine &e, const json &a) { return e.set_plate_bed_type(a.at("plate"), a.value("bedType", "")); }},
        {"undo", [](OrcaEngine &e, const json &) { return e.undo(); }},
        {"redo", [](OrcaEngine &e, const json &) { return e.redo(); }},
        {"addPrimitive", [](OrcaEngine &e, const json &a) { return e.add_primitive(a.at("shape"), vec3(a.at("size")), a.value("plate", 0)); }},
        {"addText", [](OrcaEngine &e, const json &a) {
             return e.add_text(a.at("text"), a.at("font"), a.value("height", 10.0), a.value("depth", 2.0), a.value("plate", 0));
         }},
        {"addSvg", [](OrcaEngine &e, const json &a) { return e.add_svg(a.at("path"), a.value("width", 50.0), a.value("depth", 2.0), a.value("plate", 0)); }},
        // Tools
        {"pick", [](OrcaEngine &e, const json &a) { return e.pick(vec3(a.at("origin")), vec3(a.at("dir"))); }},
        {"paint", [](OrcaEngine &e, const json &a) {
             return e.paint(a.at("object"), a.at("instance"), a.at("volume"), a.at("facet"), vec3(a.at("local")), vec3(a.at("camera")),
                            a.value("radius", 2.0), a.value("kind", "fuzzy"), a.value("state", 1), a.value("newStroke", false));
         }},
        {"paintClear", [](OrcaEngine &e, const json &a) { return e.paint_clear(a.at("object"), a.value("kind", "")); }},
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
        {"exportGcode3mf", [](OrcaEngine &e, const json &a) { return e.export_gcode_3mf(a.at("plate"), a.at("gcode"), a.at("path")); }},
        {"exportStl", [](OrcaEngine &e, const json &a) { return e.export_stl(a.at("path"), a.value("plate", -1)); }},
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
