// OrcaEngine: adding primitives, text and SVG, height range modifiers, the desktop calibration
// tests and the external G-code viewer.
//
// The calibration setups mirror Plater::calib_*() of the desktop GUI (src/slic3r/GUI/Plater.cpp at
// the upstream commit in UPSTREAM.md). Instead of editing the selected presets they keep their
// setting changes in m_calib_config, which selection_config() applies on top of the selection.

#include "OrcaEngine.hpp"

#include <cmath>
#include <stdexcept>

#include <boost/filesystem.hpp>

#include "libslic3r/ClipperUtils.hpp"
#include "libslic3r/CutUtils.hpp"
#include "libslic3r/Emboss.hpp"
#include "libslic3r/Flow.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/NSVGUtils.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/Utils.hpp"
#include "libslic3r/calib.hpp"

#include "Preview.hpp"

namespace orca {

using namespace Slic3r;
namespace fs = boost::filesystem;

namespace {

// Sets `key` to `value` in `out`, for every entry of a per-filament/per-extruder vector option
// (like the desktop's set_config_values()). `full` supplies the option type and vector size.
void set_value(DynamicPrintConfig &out, const DynamicPrintConfig &full, const std::string &key, const std::string &value)
{
    const ConfigOption *opt = full.option(key);
    if (opt == nullptr)
        return;
    size_t n = 1;
    if (opt->is_vector())
        n = std::max<size_t>(1, static_cast<const ConfigOptionVectorBase *>(opt)->size());
    std::string joined;
    for (size_t i = 0; i < n; ++i)
        joined += (i ? "," : "") + value;
    out.set_key_value(key, opt->clone());
    out.set_deserialize_strict(key, joined);
}

std::string num(double v)
{
    std::ostringstream ss;
    ss.imbue(std::locale::classic());
    ss << v;
    return ss.str();
}

void set_object(ModelObject &obj, const std::string &key, const std::string &value)
{
    ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
    obj.config.set_deserialize(key, value, ctx);
}

double first_float(const DynamicPrintConfig &cfg, const std::string &key, double fallback)
{
    if (const auto *opt = dynamic_cast<const ConfigOptionFloats *>(cfg.option(key)); opt && !opt->values.empty())
        return opt->values.front();
    if (const auto *opt = dynamic_cast<const ConfigOptionFloatsNullable *>(cfg.option(key)); opt && !opt->values.empty())
        return opt->values.front();
    return fallback;
}

// Horizontal cut of the object's first instance at world height z; returns the kept part.
ModelObject *cut_object(Model &model, ModelObject *obj, double z, bool keep_lower)
{
    const BoundingBoxf3 bb = obj->instance_bounding_box(0);
    if (z <= bb.min.z() || z >= bb.max.z())
        return obj;
    const ModelObjectCutAttributes attributes = keep_lower ? ModelObjectCutAttributes(ModelObjectCutAttribute::KeepLower)
                                                           : ModelObjectCutAttribute::KeepUpper | ModelObjectCutAttribute::PlaceOnCutUpper;
    // The cut plane is relative to the instance offset (see GLGizmoCut::get_cut_matrix).
    const Vec3d offset = obj->instances.front()->get_offset();
    Cut cutter(obj, 0, Geometry::translation_transform(Vec3d(bb.center().x(), bb.center().y(), z) - offset), attributes);
    const ModelObjectPtrs &parts = cutter.perform_with_plane();
    if (parts.empty())
        return obj;
    ModelObject *kept = model.add_object(*parts.front());
    model.delete_object(obj);
    for (ModelVolume *vol : kept->volumes)
        if (vol->get_convex_hull().empty())
            vol->calculate_convex_hull();
    kept->invalidate_bounding_box();
    kept->ensure_on_bed();
    return kept;
}

// Scales the object about its own base centre.
void scale_object(ModelObject *obj, const Vec3d &factor)
{
    for (ModelInstance *inst : obj->instances) {
        Geometry::Transformation t = inst->get_transformation();
        t.set_scaling_factor(t.get_scaling_factor().cwiseProduct(factor));
        inst->set_transformation(t);
    }
    obj->invalidate_bounding_box();
    obj->ensure_on_bed();
}

} // namespace

// --- Adding things -------------------------------------------------------------------------------

json OrcaEngine::place_new_objects(const std::vector<ModelObject *> &objects, int plate)
{
    plate = std::clamp(plate, 0, int(m_plates.size()) - 1);
    const std::array<double, 4> rect = bed_rect();
    const auto origin = plate_origin(plate);
    bool plate_was_empty = true;
    for (const ModelObject *obj : m_model->objects)
        if (std::find(objects.begin(), objects.end(), obj) == objects.end())
            for (size_t i = 0; i < obj->instances.size(); ++i)
                if (plate_of(*obj, i) == plate)
                    plate_was_empty = false;

    std::vector<std::pair<ModelObject *, size_t>> added;
    for (ModelObject *obj : objects) {
        obj->center_around_origin();
        if (obj->instances.empty())
            obj->add_instance();
        obj->instances.front()->set_offset(Vec3d(origin[0] + (rect[0] + rect[2]) / 2, origin[1] + (rect[1] + rect[3]) / 2, 0.));
        drop_to_bed(*obj);
        for (size_t i = 0; i < obj->instances.size(); ++i)
            added.emplace_back(obj, i);
    }
    if (!plate_was_empty || added.size() > 1)
        arrange_plate(plate, &added);
    return commit();
}

json OrcaEngine::add_primitive(const std::string &shape, const std::array<double, 3> &size, int plate)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    const Vec3d s(std::max(0.5, size[0]), std::max(0.5, size[1]), std::max(0.5, size[2]));
    TriangleMesh mesh;
    std::string  name;
    if (shape == "cylinder") {
        mesh = make_cylinder(s.x() / 2., s.z());
        name = "Cylinder";
    } else if (shape == "sphere") {
        mesh = TriangleMesh(its_make_sphere(s.x() / 2., 2. * M_PI / 90.));
        name = "Sphere";
    } else if (shape == "cone") {
        mesh = make_cone(s.x() / 2., s.z());
        name = "Cone";
    } else {
        mesh = make_cube(s.x(), s.y(), s.z());
        name = "Cube";
    }
    push_undo();
    ModelObject *obj = m_model->add_object(name.c_str(), "", std::move(mesh));
    return place_new_objects({obj}, plate);
}

json OrcaEngine::add_text(const std::string &text, const std::string &font_path, double height, double depth, int plate)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    if (text.empty())
        throw std::runtime_error("Enter a text");
    std::unique_ptr<Emboss::FontFile> font = Emboss::create_font_file(font_path.c_str());
    if (!font)
        throw std::runtime_error("Cannot load the font " + font_path);
    Emboss::FontFileWithCache cache(std::move(font));
    const FontProp prop{static_cast<float>(height)};
    ExPolygons shapes = Emboss::text2shapes(cache, text.c_str(), prop).expolygons;
    if (shapes.empty())
        throw std::runtime_error("The text has no printable glyphs in this font");

    // Font units to mm; extrude by `depth` (see EmbossJob try_create_mesh()).
    const double scale = Emboss::get_text_shape_scale(prop, *cache.font_file);
    auto project = std::make_unique<Emboss::ProjectZ>(depth / scale);
    Emboss::ProjectTransform projection(std::move(project), Transform3d(Eigen::Scaling(scale)));
    TriangleMesh mesh(Emboss::polygons2model(shapes, projection));
    if (mesh.empty())
        throw std::runtime_error("Creating the text failed");
    push_undo();
    ModelObject *obj = m_model->add_object(text.substr(0, 32).c_str(), "", std::move(mesh));
    return place_new_objects({obj}, plate);
}

json OrcaEngine::add_svg(const std::string &path, double width, double depth, int plate)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    NSVGimage *image = nsvgParseFromFile(path.c_str(), "mm", 96.0f);
    if (image == nullptr)
        throw std::runtime_error("Cannot read the SVG file");
    // 0.1 mm curve tolerance, squared and in scaled units (GLGizmoSVG get_tesselation_tolerance()).
    NSVGLineParams params{(0.1 * 0.1) / SCALING_FACTOR / SCALING_FACTOR};
    ExPolygonsWithIds with_ids = create_shape_with_ids(*image, params);
    nsvgDelete(image);
    ExPolygons shapes;
    for (ExPolygonsWithId &e : with_ids)
        append(shapes, std::move(e.expoly));
    shapes = union_ex(shapes);
    if (shapes.empty())
        throw std::runtime_error("The SVG contains no closed shapes");

    const BoundingBox bb = get_extents(shapes);
    const double source_width = unscale<double>(bb.size().x());
    const double scale = SCALING_FACTOR * (source_width > 0 ? width / source_width : 1.);
    auto project = std::make_unique<Emboss::ProjectZ>(depth / scale);
    Emboss::ProjectTransform projection(std::move(project), Transform3d(Eigen::Scaling(scale)));
    TriangleMesh mesh(Emboss::polygons2model(shapes, projection));
    if (mesh.empty())
        throw std::runtime_error("Creating the SVG object failed");
    push_undo();
    ModelObject *obj = m_model->add_object(fs::path(path).stem().string().c_str(), path.c_str(), std::move(mesh));
    return place_new_objects({obj}, plate);
}

json OrcaEngine::set_layer_ranges(int object, const json &ranges)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    // Every range needs its own layer height (Slicing.cpp reads it unchecked); like the desktop's
    // object list, new ranges start with the object's or the process's layer height.
    double default_layer_height = 0.2;
    if (obj.config.has("layer_height"))
        default_layer_height = obj.config.opt_float("layer_height");
    else
        try {
            default_layer_height = selection_config().opt_float("layer_height");
        } catch (const std::exception &) {
        }
    t_layer_config_ranges result;
    for (const json &r : ranges) {
        const double from = r.at("from").get<double>(), to = r.at("to").get<double>();
        if (!(to > from))
            throw std::runtime_error("A height range must end above its start");
        ModelConfig &cfg = result[{from, to}];
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
        // Keep the settings alive: iterating items() of the temporary from value() would dangle.
        const json settings = r.value("settings", json::object());
        for (const auto &[key, value] : settings.items())
            cfg.set_deserialize(key, value.is_string() ? value.get<std::string>() : value.dump(), ctx);
        if (!cfg.has("layer_height"))
            cfg.set_key_value("layer_height", new ConfigOptionFloat(default_layer_height));
    }
    push_undo();
    obj.layer_config_ranges = std::move(result);
    return commit();
}

// --- Calibration -----------------------------------------------------------------------------------

json OrcaEngine::calib_stop()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_calib.reset();
    m_calib_config.reset();
    m_calib_name.clear();
    json out = scene_json();
    out["calibration"] = nullptr;
    return out;
}

json OrcaEngine::calib_start(const std::string &type, const json &params)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    // The test's own settings are derived from the plain selection.
    m_calib.reset();
    m_calib_config.reset();
    const DynamicPrintConfig full = selection_config();

    const double start  = params.value("start", 0.0);
    const double end    = params.value("end", 1.0);
    const double step   = params.value("step", 0.1);
    const double nozzle = first_float(full, "nozzle_diameter", 0.4);
    const std::string calib_dir = resources_dir() + "/calib/";

    auto p = std::make_shared<Calib_Params>();
    p->start = start;
    p->end   = end;
    p->step  = step;
    p->print_numbers = true;
    auto cfg = std::make_shared<DynamicPrintConfig>();
    auto set = [&](const std::string &key, const std::string &value) { set_value(*cfg, full, key, value); };

    std::string model_file, name;
    if (type == "temp") {
        model_file = "temperature_tower/temperature_tower.drc";
        name = "Temperature tower";
        p->mode = CalibMode::Calib_Temp_Tower;
    } else if (type == "flow") {
        const bool linear = params.value("linear", false);
        const int  pass   = params.value("pass", 1);
        model_file = linear ? (pass == 1 ? "filament_flow/Orca-LinearFlow.3mf" : "filament_flow/Orca-LinearFlow_fine.3mf")
                            : (pass == 1 ? "filament_flow/flowrate-test-pass1.3mf" : "filament_flow/flowrate-test-pass2.3mf");
        name = linear ? "Flow rate (YOLO)" : "Flow rate pass " + std::to_string(pass);
        p->mode = CalibMode::Calib_None; // the desktop does not pass calibration parameters here
    } else if (type == "pa_line") {
        model_file = "pressure_advance/pressure_advance_test.drc";
        name = "Pressure advance (line)";
        p->mode = CalibMode::Calib_PA_Line;
    } else if (type == "pa_tower") {
        model_file = "pressure_advance/tower_with_seam.drc";
        name = "Pressure advance (tower)";
        p->mode = CalibMode::Calib_PA_Tower;
    } else if (type == "retraction") {
        model_file = "retraction/retraction_tower.drc";
        name = "Retraction test";
        p->mode = CalibMode::Calib_Retraction_tower;
    } else if (type == "max_volumetric") {
        model_file = "volumetric_speed/SpeedTestStructure.drc";
        name = "Max volumetric speed";
        p->mode = CalibMode::Calib_Vol_speed_Tower;
    } else if (type == "vfa") {
        model_file = "vfa/vfa.drc";
        name = "VFA test";
        p->mode = CalibMode::Calib_VFA_Tower;
    } else if (type == "input_shaping_freq" || type == "input_shaping_damp" || type == "cornering") {
        p->test_model = params.value("model", 0);
        model_file = p->test_model == 2 && type == "cornering" ? "cornering/SCV-V2.drc"
                   : p->test_model >= 1 ? "input_shaping/fast_tower_test.drc" : "input_shaping/ringing_tower.drc";
        name = type == "cornering" ? "Cornering test" : type == "input_shaping_freq" ? "Input shaping frequency" : "Input shaping damping";
        p->mode = type == "cornering" ? CalibMode::Calib_Cornering
                : type == "input_shaping_freq" ? CalibMode::Calib_Input_shaping_freq : CalibMode::Calib_Input_shaping_damp;
        p->freqStartX = params.value("freq_start_x", start);
        p->freqEndX   = params.value("freq_end_x", end);
        p->freqStartY = params.value("freq_start_y", start);
        p->freqEndY   = params.value("freq_end_y", end);
        p->shaper_type = params.value("shaper_type", std::string());
    } else {
        throw std::runtime_error("Unknown calibration: " + type);
    }

    // New scene with the test model.
    DynamicPrintConfig        file_config;
    ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
    Model loaded = Model::read_from_file(calib_dir + model_file, &file_config, &substitutions, LoadStrategy::LoadModel);
    if (loaded.objects.empty())
        throw std::runtime_error("Calibration model missing: " + model_file);
    push_undo();
    m_model->clear_objects();
    m_model->plates_custom_gcodes.clear();
    m_plates.assign(1, {});
    const std::array<double, 4> rect = bed_rect();
    const Vec3d center((rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2, 0.);
    std::vector<ModelObject *> objects;
    BoundingBoxf3 group;
    for (const ModelObject *src : loaded.objects) {
        ModelObject *obj = m_model->add_object(*src);
        if (obj->instances.empty()) {
            obj->center_around_origin();
            obj->add_instance();
        }
        objects.push_back(obj);
        group.merge(obj->instance_bounding_box(0));
    }
    for (ModelObject *obj : objects) {
        for (ModelInstance *inst : obj->instances)
            inst->set_offset(inst->get_offset() + Vec3d(center.x() - group.center().x(), center.y() - group.center().y(), 0.));
        obj->invalidate_bounding_box();
        obj->ensure_on_bed();
    }
    ModelObject *obj = objects.front();

    // Settings shared by the desktop's calibration functions.
    set("resonance_avoidance", "0");
    set("enable_wrapping_detection", "0");

    if (type == "temp") {
        const double block_height = 10.0;
        const int    temp_step    = 5;
        const long   upper_blocks = std::lround((500 - end) / temp_step + 1);
        if (upper_blocks > 0)
            obj = cut_object(*m_model, obj, upper_blocks * block_height - EPSILON, true);
        const long lower_blocks = std::lround((500 - start) / temp_step);
        if (lower_blocks > 0)
            obj = cut_object(*m_model, obj, lower_blocks * block_height + EPSILON, false);
        const double scale = nozzle / 0.4;
        if (std::abs(scale - 1.0) > EPSILON)
            scale_object(obj, Vec3d(scale, scale, scale));
        set("nozzle_temperature_initial_layer", num(std::lround(start)));
        set("nozzle_temperature", num(std::lround(start)));
        set("initial_layer_print_height", num(nozzle / 2));
        set_object(*obj, "layer_height", num(nozzle / 2));
        set_object(*obj, "brim_type", "outer_only");
        set_object(*obj, "brim_width", "5");
        set_object(*obj, "brim_object_gap", "0");
        set_object(*obj, "alternate_extra_wall", "0");
        set_object(*obj, "seam_slope_type", "none");
        set_object(*obj, "overhang_reverse", "0");
        set_object(*obj, "precise_z_height", "0");
    } else if (type == "flow") {
        const bool   linear = params.value("linear", false);
        const double layer_height = nozzle / 2.0;
        const double first_layer  = std::max(full.opt_float("initial_layer_print_height"), layer_height);
        const double xy = nozzle / 0.6;
        const double z  = (first_layer + 9 * layer_height) / 2;
        const double cur_flow = first_float(full, "filament_flow_ratio", 1.0);
        for (ModelObject *o : objects) {
            scale_object(o, Vec3d(xy > 1.2 ? xy : 1., xy > 1.2 ? xy : 1., z));
            for (const auto &[k, v] : std::vector<std::pair<std::string, std::string>>{
                     {"wall_loops", "1"}, {"only_one_wall_top", "1"}, {"thick_internal_bridges", "0"},
                     {"internal_bridge_density", "100%"}, {"sparse_infill_density", "35%"}, {"min_width_top_surface", "100%"},
                     {"bottom_shell_layers", "2"}, {"top_shell_layers", "5"}, {"top_shell_thickness", "0"},
                     {"bottom_shell_thickness", "0"}, {"detect_thin_wall", "1"}, {"filter_out_gap_fill", "0"},
                     {"sparse_infill_pattern", "rectilinear"}, {"top_surface_line_width", num(nozzle * 1.2)},
                     {"internal_solid_infill_line_width", num(nozzle * 1.2)}, {"top_surface_pattern", params.value("pattern", "monotonic")},
                     {"infill_direction", "45"}, {"solid_infill_direction", "135"}, {"ironing_type", "no ironing"},
                     {"seam_slope_type", "none"}, {"calib_flowrate_topinfill_special_order", "1"}}) {
                try {
                    set_object(*o, k, v);
                } catch (...) {
                    // Keys renamed upstream are skipped rather than failing the whole test.
                }
            }
            // The object names encode the flow modifier, e.g. "flowrate_m5" = -5 %.
            double modifier = 0.;
            if (o->name.size() > 9) {
                std::string n = o->name.substr(9);
                if (!n.empty() && n[0] == 'm')
                    n[0] = '-';
                try {
                    modifier = std::stod(n);
                } catch (...) {
                }
            }
            set_object(*o, "print_flow_ratio", num(linear ? (cur_flow + modifier) / cur_flow : 1.0 + modifier / 100.));
        }
        set("layer_height", num(layer_height));
        set("alternate_extra_wall", "0");
        set("initial_layer_print_height", num(first_layer));
        set("reduce_crossing_wall", "1");
        set("max_volumetric_extrusion_rate_slope", "0");
    } else if (type == "pa_line" || type == "pa_tower") {
        set("overhang_reverse", "0");
        set("precise_z_height", "0");
        set("wipe_inward", "0");
        if (type == "pa_tower") {
            set("slow_down_layer_time", "1");
            set("max_volumetric_extrusion_rate_slope", "0");
            for (const auto &[k, v] : std::vector<std::pair<std::string, std::string>>{
                     {"alternate_extra_wall", "0"}, {"seam_position", "back"}, {"wall_loops", "2"}, {"top_shell_layers", "0"},
                     {"bottom_shell_layers", "0"}, {"sparse_infill_density", "0%"}, {"brim_type", "brim_ears"},
                     {"brim_object_gap", "0"}, {"brim_ears_max_angle", "135"}, {"brim_width", "6"}, {"seam_slope_type", "none"}})
                set_object(*obj, k, v);
            obj = cut_object(*m_model, obj, std::ceil((end - start) / step) + 1, true);
        }
    } else if (type == "retraction") {
        const double lh = nozzle <= 0.1 ? 0.05 : nozzle <= 0.2 ? 0.1 : 0.2;
        set("wipe_inward", "0");
        set("use_firmware_retraction", "0");
        set("initial_layer_print_height", num(lh));
        if (first_float(full, "max_layer_height", lh) < lh)
            set("max_layer_height", num(lh));
        for (const auto &[k, v] : std::vector<std::pair<std::string, std::string>>{
                 {"wall_loops", "2"}, {"top_shell_layers", "0"}, {"bottom_shell_layers", "3"}, {"sparse_infill_density", "0%"},
                 {"layer_height", num(lh)}, {"alternate_extra_wall", "0"}, {"seam_position", "aligned"},
                 {"wall_sequence", "inner wall/outer wall"}, {"overhang_reverse", "0"}, {"precise_z_height", "0"},
                 {"seam_slope_type", "none"}})
            set_object(*obj, k, v);
        obj = cut_object(*m_model, obj, 1.0 + 0.4 + (end - start) / step - EPSILON, true);
    } else if (type == "max_volumetric") {
        const double bed_w = rect[2] - rect[0];
        const double scale_x = (bed_w - 10) / obj->instance_bounding_box(0).size().x();
        if (scale_x < 1.0)
            scale_object(obj, Vec3d(scale_x, 1, 1));
        const double line_width = nozzle * 1.75, lh = nozzle * 0.8;
        if (first_float(full, "max_layer_height", lh) < lh)
            set("max_layer_height", num(lh));
        set("filament_max_volumetric_speed", num(std::max(first_float(full, "filament_max_volumetric_speed", 0.), 200.)));
        set("slow_down_layer_time", "0");
        set("spiral_mode", "1");
        set("max_volumetric_extrusion_rate_slope", "0");
        for (const auto &[k, v] : std::vector<std::pair<std::string, std::string>>{
                 {"enable_overhang_speed", "0"}, {"wall_loops", "1"}, {"alternate_extra_wall", "0"}, {"top_shell_layers", "0"},
                 {"bottom_shell_layers", "0"}, {"sparse_infill_density", "0%"}, {"outer_wall_line_width", num(line_width)},
                 {"layer_height", num(lh)}, {"brim_type", "outer_and_inner"}, {"brim_width", "5"}, {"brim_object_gap", "0"},
                 {"precise_z_height", "0"}})
            set_object(*obj, k, v);
        obj = cut_object(*m_model, obj, (end - start + 1) / step, true);
        const double mm3_per_mm = Flow(float(line_width), float(lh), float(nozzle)).mm3_per_mm() * first_float(full, "filament_flow_ratio", 1.0);
        p->start = start / mm3_per_mm;
        p->end   = end / mm3_per_mm;
        p->step  = step / mm3_per_mm;
    } else if (type == "vfa") {
        const double lh = nozzle / 2.0;
        obj = cut_object(*m_model, obj, vfa_base_block_height * ((end - start) / step + 1) - EPSILON, true);
        const double xy = nozzle / vfa_base_nozzle_diameter, z = (vfa_layers_per_block * lh) / vfa_base_block_height;
        if (std::abs(xy - 1.0) > EPSILON || std::abs(z - 1.0) > EPSILON)
            scale_object(obj, Vec3d(xy, xy, z));
        for (const auto &[k, v] : std::vector<std::pair<std::string, std::string>>{
                 {"slow_down_layer_time", "0"}, {"enable_overhang_speed", "0"}, {"wall_loops", "1"}, {"alternate_extra_wall", "0"},
                 {"top_shell_layers", "0"}, {"bottom_shell_layers", "1"}, {"sparse_infill_density", "0%"}, {"detect_thin_wall", "0"},
                 {"spiral_mode", "1"}, {"precise_z_height", "0"}, {"initial_layer_print_height", num(lh)}})
            set(k, v);
        set_object(*obj, "layer_height", num(lh));
        set_object(*obj, "brim_type", "outer_only");
        set_object(*obj, "brim_width", "3");
        set_object(*obj, "brim_object_gap", "0");
        p->vfa_layer_height = lh;
    } else { // input shaping / cornering
        const bool junction = first_float(full, "machine_max_junction_deviation", 0.) > 0.;
        if (type == "cornering") {
            set(junction ? "machine_max_junction_deviation" : "machine_max_jerk_x", num(end));
            if (!junction)
                set("machine_max_jerk_y", num(end));
            set("input_shaping_emit", "1");
        } else {
            const bool klipper = full.opt_serialize("gcode_flavor") == "klipper";
            if (junction) {
                set("machine_max_junction_deviation", num(std::max(first_float(full, "machine_max_junction_deviation", 0.), 0.25)));
            } else {
                const double jerk = klipper ? 5.0 : 10.0;
                set("machine_max_jerk_x", num(std::max(first_float(full, "machine_max_jerk_x", 0.), jerk)));
                set("machine_max_jerk_y", num(std::max(first_float(full, "machine_max_jerk_y", 0.), jerk)));
            }
            set("input_shaping_emit", "0");
            set("layer_height", "0.2");
        }
        set(junction ? "default_junction_deviation" : "default_jerk", "0");
        set("filament_max_volumetric_speed", num(std::max(first_float(full, "filament_max_volumetric_speed", 0.), 200.)));
        const double max_speed = std::min(first_float(full, "machine_max_speed_x", 200.), first_float(full, "machine_max_speed_y", 200.));
        const double max_accel = first_float(full, "machine_max_acceleration_extruding", 5000.);
        for (const auto &[k, v] : std::vector<std::pair<std::string, std::string>>{
                 {"slow_down_layer_time", "0"}, {"slow_down_min_speed", "0"}, {"slow_down_for_layer_cooling", "0"},
                 {"enable_overhang_speed", "0"}, {"wall_loops", "1"}, {"top_shell_layers", "0"}, {"bottom_shell_layers", "1"},
                 {"sparse_infill_density", "0%"}, {"detect_thin_wall", "0"}, {"spiral_mode", "1"}, {"spiral_mode_smooth", "0"},
                 {"bottom_surface_pattern", "rectilinear"}, {"outer_wall_speed", num(max_speed)},
                 {"default_acceleration", num(max_accel)}, {"outer_wall_acceleration", num(max_accel)}, {"precise_z_height", "0"}})
            set(k, v);
        set_object(*obj, "brim_type", "outer_only");
        set_object(*obj, "brim_width", "3");
        set_object(*obj, "brim_object_gap", "0");
    }

    m_calib        = p->mode == CalibMode::Calib_None ? nullptr : p;
    m_calib_config = cfg;
    m_calib_name   = name;
    json out = commit();
    out["calibration"] = {{"type", type}, {"name", name}, {"start", start}, {"end", end}, {"step", step}};
    return out;
}

// --- External G-code ---------------------------------------------------------------------------------

json OrcaEngine::view_gcode(const std::string &gcode, const std::string &preview_dir, const ProgressFn &progress)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    progress(10, "Reading G-code");
    GCodeProcessor processor;
    processor.process_file(gcode);
    // extract_result() hands out the processor's own result; bind it instead of copying.
    GCodeProcessorResult &&result = processor.extract_result();
    progress(90, "Preparing preview");
    json out = write_preview(result, preview_dir);
    const auto &normal = result.print_statistics.modes[size_t(PrintEstimatedStatistics::ETimeMode::Normal)];
    double filament_m = 0., filament_g = 0.;
    for (const auto &[role, used] : result.print_statistics.used_filaments_per_role) {
        filament_m += used.first;
        filament_g += used.second;
    }
    out["gcode"]        = gcode;
    out["print_time_s"] = normal.time;
    out["filament_mm"]  = filament_m * 1000.;
    out["filament_g"]   = filament_g;
    out["cost"]         = 0.;
    out["warnings"]     = json::array();
    out["origin"]       = {0., 0.};
    out["filaments"]    = json::array();
    progress(100, "Done");
    return out;
}

} // namespace orca
