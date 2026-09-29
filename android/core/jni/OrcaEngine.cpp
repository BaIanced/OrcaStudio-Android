#include "OrcaEngine.hpp"

#include <cmath>
#include <cstdio>
#include <fstream>
#include <stdexcept>

#include <android/log.h>

#include <boost/filesystem.hpp>
#include <boost/log/core.hpp>
#include <boost/log/expressions.hpp>
#include <boost/log/sinks/basic_sink_backend.hpp>
#include <boost/log/sinks/sync_frontend.hpp>
#include <boost/log/trivial.hpp>
#include <boost/make_shared.hpp>

#include "libslic3r/AppConfig.hpp"
#include "libslic3r/BuildVolume.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/ModelArrange.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"

namespace orca {

using namespace Slic3r;
namespace fs = boost::filesystem;

namespace {

// Routes Boost.Log (all BOOST_LOG_TRIVIAL output of libslic3r) to logcat.
class LogcatBackend
    : public boost::log::sinks::basic_formatted_sink_backend<char, boost::log::sinks::synchronized_feeding>
{
public:
    void consume(const boost::log::record_view &rec, const string_type &msg)
    {
        int prio = ANDROID_LOG_INFO;
        if (auto sev = rec[boost::log::trivial::severity]) {
            switch (*sev) {
            case boost::log::trivial::trace:
            case boost::log::trivial::debug: prio = ANDROID_LOG_DEBUG; break;
            case boost::log::trivial::info: prio = ANDROID_LOG_INFO; break;
            case boost::log::trivial::warning: prio = ANDROID_LOG_WARN; break;
            default: prio = ANDROID_LOG_ERROR; break;
            }
        }
        __android_log_write(prio, "OrcaCore", msg.c_str());
    }
};

void install_logcat_sink()
{
    using Sink = boost::log::sinks::synchronous_sink<LogcatBackend>;
    auto sink  = boost::make_shared<Sink>();
    sink->set_formatter(boost::log::expressions::stream << boost::log::expressions::smessage);
    boost::log::core::get()->add_sink(sink);
}

const Preset *find_preset(const PresetCollection &collection, const std::string &name)
{
    for (size_t i = 0; i < collection.size(); ++i)
        if (collection.preset(i).name == name)
            return &collection.preset(i);
    return nullptr;
}

double nozzle_of(const Preset &printer)
{
    const auto *opt = printer.config.option<ConfigOptionFloats>("nozzle_diameter");
    return (opt && !opt->values.empty()) ? opt->values.front() : 0.;
}

// Lists the presets of a collection that are usable with the selected printer.
json compatible_names(const PresetCollection &collection)
{
    json out = json::array();
    for (size_t i = 0; i < collection.size(); ++i) {
        const Preset &p = collection.preset(i);
        if (p.is_default || !p.is_compatible)
            continue;
        out.push_back({{"name", p.name}, {"system", p.is_system}, {"vendor", p.vendor ? p.vendor->name : "User"}});
    }
    return out;
}

// Config options are strings to libslic3r; bools use the 0/1 spelling.
std::string option_value(const json &v)
{
    if (v.is_string())
        return v.get<std::string>();
    if (v.is_boolean())
        return v.get<bool>() ? "1" : "0";
    return v.dump();
}

template<class T> void write_raw(std::ofstream &f, const T &v) { f.write(reinterpret_cast<const char *>(&v), sizeof(T)); }

} // namespace

OrcaEngine &OrcaEngine::instance()
{
    static OrcaEngine engine;
    return engine;
}

OrcaEngine::OrcaEngine()  = default;
OrcaEngine::~OrcaEngine() = default;

void OrcaEngine::init(const std::string &resources_dir, const std::string &data_dir, const std::string &cache_dir)
{
    std::lock_guard<std::mutex> lock(m_mutex);

    static bool logging_ready = false;
    if (!logging_ready) {
        install_logcat_sink();
        set_logging_level(2); // warnings and errors
        logging_ready = true;
    }

    set_resources_dir(resources_dir);
    set_var_dir((fs::path(resources_dir) / "images").string());
    set_local_dir((fs::path(resources_dir) / "i18n").string());
    set_sys_shapes_dir((fs::path(resources_dir) / "shapes").string());
    set_custom_gcodes_dir((fs::path(resources_dir) / "custom_gcodes").string());
    set_data_dir(data_dir);
    set_temporary_dir(cache_dir);

    fs::create_directories(fs::path(data_dir) / PRESET_SYSTEM_DIR);
    fs::create_directories(fs::path(data_dir) / PRESET_USER_DIR);
    fs::create_directories(cache_dir);
}

json OrcaEngine::load_presets()
{
    std::lock_guard<std::mutex> lock(m_mutex);

    AppConfig   app_config;
    std::string errors;
    auto        bundle = std::make_unique<PresetBundle>();
    bundle->load_presets(app_config, ForwardCompatibilitySubstitutionRule::EnableSilent, PresetBundle::PresetPreferences(),
                         &errors, true);
    if (!errors.empty())
        BOOST_LOG_TRIVIAL(warning) << "Preset loading reported: " << errors;
    // The desktop app hides presets of printer models not ticked in its setup wizard, and
    // select_preset_by_name() silently falls back to the first visible preset. Here the set of
    // installed vendors already is the user's choice, so everything that was loaded is usable.
    for (PresetCollection *collection : {static_cast<PresetCollection *>(&bundle->printers), &bundle->prints, &bundle->filaments})
        for (size_t i = 0; i < collection->size(); ++i)
            collection->preset(i, true).is_visible = true;
    m_bundle = std::move(bundle);
    m_printer.clear();

    return {{"printers", printer_list_locked()}, {"errors", errors}};
}

json OrcaEngine::printer_list()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_bundle)
        throw std::runtime_error("Presets are not loaded");
    return {{"printers", printer_list_locked()}};
}

json OrcaEngine::printer_list_locked()
{
    json printers = json::array();
    for (size_t i = 0; i < m_bundle->printers.size(); ++i) {
        const Preset &p = m_bundle->printers.preset(i);
        if (p.is_default)
            continue;
        printers.push_back({{"name", p.name},
                            {"vendor", p.vendor ? p.vendor->name : "User"},
                            {"model", p.config.opt_string("printer_model")},
                            {"nozzle", nozzle_of(p)},
                            {"system", p.is_system}});
    }
    return printers;
}

json OrcaEngine::select_printer(const std::string &printer)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return select_printer_locked(printer);
}

json OrcaEngine::select_printer_locked(const std::string &printer)
{
    if (!m_bundle)
        throw std::runtime_error("Presets are not loaded");
    if (find_preset(m_bundle->printers, printer) == nullptr)
        throw std::runtime_error("Unknown printer: " + printer);

    m_bundle->printers.select_preset_by_name(printer, true);
    m_bundle->update_compatible(PresetSelectCompatibleType::Always);
    m_printer = printer;

    const DynamicPrintConfig &cfg  = m_bundle->printers.get_selected_preset().config;
    json                      bed  = json::array();
    if (const auto *area = cfg.option<ConfigOptionPoints>("printable_area"))
        for (const Vec2d &p : area->values)
            bed.push_back({p.x(), p.y()});

    // Prefer the printer's declared defaults when they are compatible, else what update_compatible picked.
    std::string default_print = m_bundle->prints.get_selected_preset().name;
    if (const auto *opt = cfg.option<ConfigOptionString>("default_print_profile"))
        if (const Preset *p = find_preset(m_bundle->prints, opt->value); p && p->is_compatible)
            default_print = p->name;
    std::string default_filament = m_bundle->filaments.get_selected_preset().name;
    if (const auto *opt = cfg.option<ConfigOptionStrings>("default_filament_profile"); opt && !opt->values.empty())
        if (const Preset *p = find_preset(m_bundle->filaments, opt->values.front()); p && p->is_compatible)
            default_filament = p->name;

    return {{"prints", compatible_names(m_bundle->prints)},
            {"filaments", compatible_names(m_bundle->filaments)},
            {"bed", bed},
            {"max_height", cfg.opt_float("printable_height")},
            {"default_print", default_print},
            {"default_filament", default_filament}};
}

json OrcaEngine::load_model(const std::vector<std::string> &paths, int copies, const std::string &mesh_out)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_printer.empty())
        throw std::runtime_error("Select a printer first");

    const DynamicPrintConfig &cfg = m_bundle->printers.get_selected_preset().config;
    BoundingBoxf bed;
    for (const Vec2d &p : cfg.option<ConfigOptionPoints>("printable_area")->values)
        bed.merge(p);
    const Vec2d bed_center = bed.center();

    auto model = std::make_unique<Model>();
    for (const std::string &path : paths) {
        DynamicPrintConfig        file_config;
        ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
        Model loaded = Model::read_from_file(path, &file_config, &substitutions, LoadStrategy::LoadModel);
        for (const ModelObject *obj : loaded.objects) {
            ModelObject *added = model->add_object(*obj);
            // Like the desktop Plater: files without placement (STL, OBJ, ...) keep their CAD
            // coordinates, which may be anywhere. Center them and put them in the middle of the bed.
            if (added->instances.empty()) {
                added->center_around_origin();
                added->add_instance()->set_offset(to_3d(bed_center, 0.));
            }
        }
    }
    if (model->objects.empty())
        throw std::runtime_error("The file does not contain any printable object");

    if (copies > 1)
        duplicate_objects(*model, size_t(copies));
    for (ModelObject *obj : model->objects)
        obj->ensure_on_bed();

    // A single object stays centered; several are arranged. Instances that do not fit end up on
    // virtual neighbour beds, which would put them far off the plate - keep those centered instead
    // and report that the plate is overfull.
    bool fits = true;
    size_t instance_count = 0;
    for (const ModelObject *obj : model->objects)
        instance_count += obj->instances.size();
    if (instance_count > 1) {
        ArrangeParams params;
        params.min_obj_distance = scaled(6.);
        ModelInstancePtrs instances;
        ArrangePolygons   polys = get_arrange_polys(*model, instances);
        arrangement::arrange(polys, BoundingBox(scaled(bed.min), scaled(bed.max)), params);
        for (size_t i = 0; i < polys.size(); ++i) {
            if (polys[i].bed_idx == 0)
                instances[i]->apply_arrange_result(polys[i].translation.cast<double>(), polys[i].rotation);
            else
                fits = false;
        }
    }
    for (ModelObject *obj : model->objects)
        obj->ensure_on_bed();

    // Whole-plate check against the printable area (also catches single objects larger than the bed).
    for (const ModelObject *obj : model->objects)
        for (size_t i = 0; i < obj->instances.size(); ++i) {
            const BoundingBoxf3 bb = obj->instance_bounding_box(i);
            if (bb.min.x() < bed.min.x() - EPSILON || bb.min.y() < bed.min.y() - EPSILON ||
                bb.max.x() > bed.max.x() + EPSILON || bb.max.y() > bed.max.y() + EPSILON ||
                bb.max.z() > cfg.opt_float("printable_height") + EPSILON)
                fits = false;
        }

    // Preview mesh: flat-shaded triangle soup of every instance in bed coordinates.
    std::ofstream f(mesh_out, std::ios::binary | std::ios::trunc);
    if (!f)
        throw std::runtime_error("Cannot write " + mesh_out);
    size_t triangles = 0;
    BoundingBoxf3 bbox;
    for (const ModelObject *obj : model->objects) {
        const TriangleMesh object_mesh = obj->mesh();
        for (const ModelInstance *inst : obj->instances) {
            TriangleMesh mesh = object_mesh;
            mesh.transform(inst->get_matrix());
            const indexed_triangle_set &its = mesh.its;
            for (const Vec3i32 &tri : its.indices) {
                const Vec3f &a = its.vertices[tri[0]], &b = its.vertices[tri[1]], &c = its.vertices[tri[2]];
                Vec3f n = (b - a).cross(c - a);
                const float len = n.norm();
                n = len > 0.f ? Vec3f(n / len) : Vec3f(0.f, 0.f, 1.f);
                for (const Vec3f *v : {&a, &b, &c}) {
                    for (int k = 0; k < 3; ++k) write_raw(f, (*v)[k]);
                    for (int k = 0; k < 3; ++k) write_raw(f, n[k]);
                    bbox.merge(v->cast<double>());
                }
            }
            triangles += its.indices.size();
        }
    }
    f.close();

    m_model = std::move(model);
    const Vec3d size = bbox.size();
    return {{"objects", m_model->objects.size()},
            {"triangles", triangles},
            {"fits", fits},
            {"min", {bbox.min.x(), bbox.min.y(), bbox.min.z()}},
            {"size", {size.x(), size.y(), size.z()}}};
}

PresetCollection &OrcaEngine::collection(const std::string &type)
{
    if (!m_bundle)
        throw std::runtime_error("Presets are not loaded");
    if (type == "print")
        return m_bundle->prints;
    if (type == "filament")
        return m_bundle->filaments;
    if (type == "printer")
        return m_bundle->printers;
    throw std::runtime_error("Unknown preset type: " + type);
}

namespace {

const std::vector<std::string> &option_keys(const std::string &type)
{
    if (type == "print")
        return Preset::print_options();
    if (type == "filament")
        return Preset::filament_options();
    return Preset::printer_options();
}

const char *type_name(ConfigOptionType t)
{
    switch (t) {
    case coFloat: return "float";
    case coFloats: return "floats";
    case coInt: return "int";
    case coInts: return "ints";
    case coString: return "string";
    case coStrings: return "strings";
    case coPercent: return "percent";
    case coPercents: return "percents";
    case coFloatOrPercent: return "float_or_percent";
    case coFloatsOrPercents: return "floats_or_percents";
    case coPoint: return "point";
    case coPoints: return "points";
    case coPoint3: return "point3";
    case coBool: return "bool";
    case coBools: return "bools";
    case coEnum: return "enum";
    case coEnums: return "enums";
    default: return "other";
    }
}

// JSON cannot hold +-inf; unbounded limits are simply omitted.
void put_limit(json &j, const char *key, float v)
{
    if (std::abs(v) < 1e30f)
        j[key] = v;
}

} // namespace

json OrcaEngine::option_defs(const std::string &type)
{
    json out = json::array();
    for (const std::string &key : option_keys(type)) {
        const ConfigOptionDef *def = print_config_def.get(key);
        if (def == nullptr || def->type == coNone)
            continue;
        json j = {{"key", key},
                  {"label", def->label},
                  {"full_label", def->full_label},
                  {"category", def->category},
                  {"tooltip", def->tooltip},
                  {"type", type_name(def->type)},
                  {"sidetext", def->sidetext},
                  {"mode", int(def->mode)},
                  {"readonly", def->readonly},
                  {"multiline", def->multiline},
                  {"is_code", def->is_code},
                  {"gui_type", int(def->gui_type)}};
        put_limit(j, "min", def->min);
        put_limit(j, "max", def->max);
        if (!def->enum_values.empty()) {
            j["enum_values"] = def->enum_values;
            j["enum_labels"] = def->enum_labels.empty() ? def->enum_values : def->enum_labels;
        }
        out.push_back(std::move(j));
    }
    return out;
}

json OrcaEngine::preset_values(const std::string &type, const std::string &name)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Preset *preset = find_preset(collection(type), name);
    if (preset == nullptr)
        throw std::runtime_error("Unknown preset: " + name);
    json out = json::object();
    for (const std::string &key : option_keys(type))
        if (preset->config.has(key))
            out[key] = preset->config.opt_serialize(key);
    return out;
}

json OrcaEngine::save_preset(const std::string &type, const std::string &base, const std::string &new_name, const json &overrides)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PresetCollection &presets = collection(type);
    const Preset     *existing = find_preset(presets, new_name);
    if (existing != nullptr && (existing->is_system || existing->is_default))
        throw std::runtime_error("A system preset with this name already exists");
    if (!presets.select_preset_by_name(base, true))
        throw std::runtime_error("Unknown preset: " + base);

    DynamicPrintConfig &config = presets.get_edited_preset().config;
    for (const auto &[key, value] : overrides.items())
        config.set_deserialize_strict(key, option_value(value));
    presets.save_current_preset(new_name);
    if (Preset *saved = presets.find_preset(new_name, false))
        saved->is_visible = true;

    if (type == "filament")
        m_bundle->filament_presets = {new_name};
    return select_printer_locked(type == "printer" ? new_name : m_printer);
}

json OrcaEngine::delete_preset(const std::string &type, const std::string &name)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PresetCollection &presets = collection(type);
    const Preset     *preset  = find_preset(presets, name);
    if (preset == nullptr || preset->is_system || preset->is_default)
        throw std::runtime_error("Only user presets can be deleted");
    presets.select_preset_by_name(name, true);
    if (!presets.delete_current_preset())
        throw std::runtime_error("The preset is in use by other presets and cannot be deleted");

    std::string printer = m_printer;
    if (type == "printer" && printer == name)
        printer = m_bundle->printers.get_selected_preset().name;
    return select_printer_locked(printer);
}

DynamicPrintConfig OrcaEngine::build_config(const std::string &print_preset, const std::string &filament_preset,
                                            const json &overrides)
{
    if (!m_bundle->prints.select_preset_by_name(print_preset, true))
        throw std::runtime_error("Unknown process preset: " + print_preset);
    if (!m_bundle->filaments.select_preset_by_name(filament_preset, true))
        throw std::runtime_error("Unknown filament preset: " + filament_preset);
    m_bundle->filament_presets = {filament_preset};

    DynamicPrintConfig config = m_bundle->full_config();
    for (const auto &[key, value] : overrides.items()) {
        try {
            config.set_deserialize_strict(key, option_value(value));
        } catch (const std::exception &ex) {
            throw std::runtime_error("Invalid value for " + key + ": " + ex.what());
        }
    }
    return config;
}

json OrcaEngine::slice(const std::string &print_preset, const std::string &filament_preset, const json &overrides,
                       const std::string &gcode_out, const std::string &preview_out, const ProgressFn &progress)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_bundle || m_printer.empty())
        throw std::runtime_error("Select a printer first");
    if (!m_model || m_model->objects.empty())
        throw std::runtime_error("Load a model first");

    DynamicPrintConfig config = build_config(print_preset, filament_preset, overrides);

    const auto &area = config.option<ConfigOptionPoints>("printable_area")->values;
    BuildVolume build_volume(area, config.opt_float("printable_height"), {}, {});
    if (m_model->update_print_volume_state(build_volume) == 0)
        throw std::runtime_error("No object lies completely inside the print volume");

    Print print;
    print.set_status_callback([&progress](const PrintBase::SlicingStatus &s) {
        if (s.percent >= 0)
            progress(s.percent, s.text);
    });
    print.apply(*m_model, config);
    print.is_BBL_printer() = config.opt_string("printer_model").rfind("Bambu Lab", 0) == 0;

    std::vector<StringObjectException> validate_warnings;
    StringObjectException              err = print.validate(&validate_warnings);
    if (!err.string.empty())
        throw std::runtime_error(err.string);
    if (print.empty())
        throw std::runtime_error("Nothing to slice");

    Model::setExtruderParams(config, 1);
    Model::setPrintSpeedTable(config, print.config());

    GCodeProcessorResult result;
    std::string          gcode_path;
    auto set_running = [this](Print *p) {
        std::lock_guard<std::mutex> cancel_lock(m_cancel_mutex);
        m_running_print = p;
    };
    set_running(&print);
    try {
        print.process();
        gcode_path = print.export_gcode(gcode_out, &result, nullptr);
    } catch (const CanceledException &) {
        set_running(nullptr);
        throw std::runtime_error("Slicing cancelled");
    } catch (...) {
        set_running(nullptr);
        throw;
    }
    set_running(nullptr);
    progress(100, "Done");

    // Toolpath preview: extrusion segments in G-code order, with the index of each layer's first one.
    json layers = json::array();
    {
        std::ofstream f(preview_out, std::ios::binary | std::ios::trunc);
        if (!f)
            throw std::runtime_error("Cannot write " + preview_out);
        size_t       segments   = 0;
        unsigned int last_layer = UINT_MAX;
        for (size_t i = 1; i < result.moves.size(); ++i) {
            const auto &m = result.moves[i];
            if (m.type != EMoveType::Extrude)
                continue;
            if (m.layer_id != last_layer) {
                layers.push_back({m.position.z(), segments});
                last_layer = m.layer_id;
            }
            const Vec3f &a = result.moves[i - 1].position;
            for (int k = 0; k < 3; ++k) write_raw(f, a[k]);
            for (int k = 0; k < 3; ++k) write_raw(f, m.position[k]);
            write_raw(f, float(m.extrusion_role));
            ++segments;
        }
    }

    json warnings = json::array();
    for (const auto &w : validate_warnings)
        warnings.push_back(w.string);

    const PrintStatistics &stats = print.print_statistics();
    const float print_time = result.print_statistics.modes[size_t(PrintEstimatedStatistics::ETimeMode::Normal)].time;
    return {{"gcode", gcode_path},
            {"print_time_s", print_time},
            {"filament_mm", stats.total_used_filament},
            {"filament_g", stats.total_weight},
            {"cost", stats.total_cost},
            {"layers", layers},
            {"warnings", warnings}};
}

void OrcaEngine::cancel()
{
    std::lock_guard<std::mutex> cancel_lock(m_cancel_mutex);
    if (m_running_print)
        m_running_print->cancel();
}

} // namespace orca
