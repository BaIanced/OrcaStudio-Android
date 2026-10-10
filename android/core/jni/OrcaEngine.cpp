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
#include "libslic3r/calib.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"

#include "Preview.hpp"
#include "Thumbnails.hpp"

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
        json entry = {{"name", p.name}, {"system", p.is_system}, {"vendor", p.vendor ? p.vendor->name : "User"}};
        // A filament's brand (Bambu Lab, Generic, eSUN ...) and material, for filtering and grouping.
        if (const auto *brand = p.config.option<ConfigOptionStrings>("filament_vendor"); brand && !brand->values.empty())
            entry["brand"] = brand->values.front();
        if (const auto *type = p.config.option<ConfigOptionStrings>("filament_type"); type && !type->values.empty())
            entry["type"] = type->values.front();
        out.push_back(std::move(entry));
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

    m_mesh_path  = (fs::path(cache_dir) / "scene_mesh.bin").string();
    m_paint_path = (fs::path(cache_dir) / "paint_mesh.bin").string();
    if (!m_model)
        m_model = std::make_unique<Model>();

    fs::create_directories(fs::path(data_dir) / PRESET_SYSTEM_DIR);
    fs::create_directories(fs::path(data_dir) / PRESET_USER_DIR);
    fs::create_directories(cache_dir);
}

json OrcaEngine::load_presets()
{
    std::lock_guard<std::mutex> lock(m_mutex);

    // Loading read-only skips user presets unless their folder exists, and then leaves the user
    // preset paths empty, so saving a user preset would fail.
    boost::filesystem::create_directories(boost::filesystem::path(data_dir()) / PRESET_USER_DIR / DEFAULT_USER_FOLDER_NAME);

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
        // A user preset belongs to the vendor and the physical printer of the system preset it inherits.
        const Preset        *parent = p.is_system ? nullptr : m_bundle->printers.get_preset_parent(p);
        const VendorProfile *vendor = m_bundle->printers.get_preset_with_vendor_profile(p).vendor;
        printers.push_back({{"name", p.name},
                            {"vendor", vendor ? vendor->name : "User"},
                            {"base", parent && parent != &p ? parent->name : std::string()},
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
    // Keep objects on their plates when the bed size changes.
    if (m_model && !m_model->objects.empty() && bed_rect() != m_layout_rect)
        relayout(m_plates.size(), m_layout_rect);
    m_layout_rect = bed_rect();

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

    // Plate types (Cool Plate, Textured PEI, ...): Bambu Lab printers always have them, others only
    // when they declare them (as in the desktop's Plater).
    json        bed_types = json::array();
    std::string default_bed_type;
    if (m_bundle->is_bbl_vendor() || cfg.opt_bool("support_multi_bed_types")) {
        for (const auto &[name, value] : ConfigOptionEnum<BedType>::get_enum_values())
            if (value != btDefault)
                bed_types.push_back(name);
        default_bed_type = cfg.has("default_bed_type") ? cfg.opt_string("default_bed_type") : std::string();
        if (default_bed_type.empty())
            default_bed_type = print_config_def.get("curr_bed_type")->default_value->serialize();
    }

    return {{"prints", compatible_names(m_bundle->prints)},
            {"filaments", compatible_names(m_bundle->filaments)},
            {"bed", bed},
            {"max_height", cfg.opt_float("printable_height")},
            {"default_print", default_print},
            {"default_filament", default_filament},
            {"bed_types", bed_types},
            {"default_bed_type", default_bed_type}};
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

void OrcaEngine::set_selection(const std::string &print, const json &filaments, const json &overrides)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    if (find_preset(m_bundle->prints, print) == nullptr)
        throw std::runtime_error("Unknown process preset: " + print);
    if (!filaments.is_array() || filaments.empty())
        throw std::runtime_error("Select at least one filament");
    for (const json &f : filaments)
        if (find_preset(m_bundle->filaments, f.at("name").get<std::string>()) == nullptr)
            throw std::runtime_error("Unknown filament preset: " + f.at("name").get<std::string>());
    m_sel_print     = print;
    m_sel_filaments = filaments;
    m_sel_overrides = overrides.is_object() ? overrides : json::object();
}

DynamicPrintConfig OrcaEngine::selection_config()
{
    require_printer();
    if (m_sel_print.empty() || m_sel_filaments.empty())
        throw std::runtime_error("No process/filament selected");
    m_bundle->prints.select_preset_by_name(m_sel_print, true);
    std::vector<std::string> names;
    for (const json &f : m_sel_filaments)
        names.push_back(f.at("name").get<std::string>());
    m_bundle->filaments.select_preset_by_name(names.front(), true);
    m_bundle->filament_presets = names;

    DynamicPrintConfig config = m_bundle->full_config();
    auto set_option = [&config](const std::string &key, const json &value) {
        try {
            config.set_deserialize_strict(key, option_value(value));
        } catch (const std::exception &ex) {
            throw std::runtime_error("Invalid value for " + key + ": " + ex.what());
        }
    };
    for (const auto &[key, value] : m_sel_overrides.items())
        set_option(key, value);

    // Per-filament edits and colours go into their slot of the per-filament vector options.
    for (size_t i = 0; i < m_sel_filaments.size(); ++i) {
        const json &f = m_sel_filaments[i];
        json edits = f.value("overrides", json::object());
        if (f.contains("color") && f["color"].is_string() && !f["color"].get<std::string>().empty())
            edits["filament_colour"] = f["color"];
        for (const auto &[key, value] : edits.items()) {
            ConfigOption *opt = config.option(key);
            if (opt == nullptr || !opt->is_vector()) {
                set_option(key, value);
                continue;
            }
            // Parse the edited value on its own, then copy its first entry into slot i.
            std::unique_ptr<ConfigOption> single(opt->clone());
            DynamicPrintConfig tmp;
            tmp.set_key_value(key, single.release());
            try {
                tmp.set_deserialize_strict(key, option_value(value));
            } catch (const std::exception &ex) {
                throw std::runtime_error("Invalid value for " + key + ": " + ex.what());
            }
            auto *vec = static_cast<ConfigOptionVectorBase *>(opt);
            if (vec->size() <= i)
                vec->resize(i + 1, static_cast<const ConfigOptionVectorBase *>(tmp.option(key)));
            vec->set_at(tmp.option(key), i, 0);
        }
    }
    // Like the desktop when filaments are added or removed: a flushing matrix that does not fit the
    // number of filaments (per nozzle) is recalculated from the colours.
    if (auto *matrix = config.option<ConfigOptionFloats>("flush_volumes_matrix")) {
        const size_t filaments = config.option<ConfigOptionStrings>("filament_colour")->values.size();
        const auto  *mult      = config.option<ConfigOptionFloats>("flush_multiplier");
        const size_t heads     = std::max<size_t>(1, mult ? mult->values.size() : 1);
        if (filaments > 1 && matrix->values.size() != filaments * filaments * heads) {
            const std::vector<double> one = auto_flush_matrix(config);
            matrix->values.clear();
            for (size_t h = 0; h < heads; ++h)
                matrix->values.insert(matrix->values.end(), one.begin(), one.end());
        }
    }
    // An active calibration test overrides whatever it needs (see OrcaExtras.cpp).
    if (m_calib_config)
        config.apply(*m_calib_config);
    return config;
}

std::unique_ptr<Model> OrcaEngine::plate_model(int plate) const
{
    const std::array<double, 2> origin = plate_origin(plate);
    auto model = std::make_unique<Model>();
    for (const ModelObject *obj : m_model->objects) {
        std::vector<size_t> on_plate;
        for (size_t i = 0; i < obj->instances.size(); ++i)
            if (plate_of(*obj, i) == plate)
                on_plate.push_back(i);
        if (on_plate.empty())
            continue;
        ModelObject *copy = model->add_object(*obj);
        copy->clear_instances();
        for (size_t i : on_plate) {
            ModelInstance *inst = copy->add_instance(*obj->instances[i]);
            inst->set_offset(inst->get_offset() - Vec3d(origin[0], origin[1], 0.));
        }
        copy->invalidate_bounding_box();
    }
    if (auto it = m_model->plates_custom_gcodes.find(plate); it != m_model->plates_custom_gcodes.end())
        model->plates_custom_gcodes[0] = it->second;
    model->curr_plate_index = 0;
    return model;
}

json OrcaEngine::slice(int plate, const std::string &gcode_out, const std::string &preview_dir, const ProgressFn &progress)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    if (plate < 0 || plate >= int(m_plates.size()))
        throw std::runtime_error("Invalid plate");

    std::unique_ptr<Model> model = plate_model(plate);
    if (model->objects.empty())
        throw std::runtime_error("This plate is empty");

    DynamicPrintConfig config = selection_config();
    // The plate's own type, else the printer's default (the UI shows the latter for "default").
    if (!m_plates[plate].bed_type.empty())
        config.set_deserialize_strict("curr_bed_type", m_plates[plate].bed_type);
    else if (config.has("default_bed_type") && !config.opt_string("default_bed_type").empty())
        config.set_deserialize_strict("curr_bed_type", config.opt_string("default_bed_type"));
    if (m_plates[plate].has_wipe_tower_pos) {
        config.option<ConfigOptionFloats>("wipe_tower_x", true)->values = {m_plates[plate].wipe_tower_x};
        config.option<ConfigOptionFloats>("wipe_tower_y", true)->values = {m_plates[plate].wipe_tower_y};
    }
    const size_t filament_count = m_sel_filaments.size();

    const auto &area = config.option<ConfigOptionPoints>("printable_area")->values;
    BuildVolume build_volume(area, config.opt_float("printable_height"), {}, {});
    if (model->update_print_volume_state(build_volume) == 0)
        throw std::runtime_error("No object lies completely inside the print volume");

    Print print;
    print.set_status_callback([&progress](const PrintBase::SlicingStatus &s) {
        if (s.percent >= 0)
            progress(s.percent, s.text);
    });
    print.apply(*model, config);
    print.is_BBL_printer() = m_bundle->is_bbl_vendor();
    if (m_calib)
        print.set_calib_params(*m_calib);

    std::vector<StringObjectException> validate_warnings;
    StringObjectException              err = print.validate(&validate_warnings);
    if (!err.string.empty())
        throw std::runtime_error(err.string);
    if (print.empty())
        throw std::runtime_error("Nothing to slice");

    Model::setExtruderParams(config, int(filament_count));
    Model::setPrintSpeedTable(config, print.config());

    // Thumbnails for printer displays and web UIs, in the sizes the printer profile asks for.
    std::vector<std::array<float, 3>> colors;
    if (const auto *opt = config.option<ConfigOptionStrings>("filament_colour"))
        for (const std::string &c : opt->values)
            colors.push_back(parse_color(c));
    const Model &thumb_model = *model;
    ThumbnailsGeneratorCallback thumbnails = [&thumb_model, &colors](const ThumbnailsParams &params) {
        ThumbnailsList list;
        for (const Vec2d &size : params.sizes)
            list.push_back(render_thumbnail(thumb_model, unsigned(size.x()), unsigned(size.y()), colors));
        return list;
    };

    GCodeProcessorResult result;
    std::string          gcode_path;
    auto set_running = [this](Print *p) {
        std::lock_guard<std::mutex> cancel_lock(m_cancel_mutex);
        m_running_print = p;
    };
    set_running(&print);
    try {
        print.process();
        gcode_path = print.export_gcode(gcode_out, &result, thumbnails);
    } catch (const CanceledException &) {
        set_running(nullptr);
        throw std::runtime_error("Slicing cancelled");
    } catch (...) {
        set_running(nullptr);
        throw;
    }
    set_running(nullptr);
    progress(100, "Done");

    json out = write_preview(result, preview_dir);

    json warnings = json::array();
    for (const auto &w : validate_warnings)
        warnings.push_back(w.string);

    const PrintStatistics &stats = print.print_statistics();
    const auto &normal = result.print_statistics.modes[size_t(PrintEstimatedStatistics::ETimeMode::Normal)];
    json roles = json::array();
    for (auto &r : out["roles"]) {
        const auto role = ExtrusionRole(r["role"].get<int>());
        if (auto it = result.print_statistics.used_filaments_per_role.find(role); it != result.print_statistics.used_filaments_per_role.end()) {
            r["filament_m"] = it->second.first;
            r["filament_g"] = it->second.second;
        }
        roles.push_back(r);
    }
    json per_filament = json::array();
    for (size_t i = 0; i < filament_count; ++i) {
        double m = 0., g = 0.;
        if (auto it = result.print_statistics.total_volumes_per_extruder.find(i); it != result.print_statistics.total_volumes_per_extruder.end()) {
            const double d = config.option<ConfigOptionFloats>("filament_diameter")->get_at(i);
            const double density = config.option<ConfigOptionFloats>("filament_density")->get_at(i);
            m = it->second / (M_PI * d * d / 4.) / 1000.;
            g = it->second * density / 1000.;
        }
        per_filament.push_back({{"m", m}, {"g", g}});
    }
    out["roles"]        = roles;
    out["filaments"]    = per_filament;
    out["gcode"]        = gcode_path;
    out["print_time_s"] = normal.time;
    out["filament_mm"]  = stats.total_used_filament;
    out["filament_g"]   = stats.total_weight;
    out["cost"]         = stats.total_cost;
    out["warnings"]     = warnings;

    // What PartPlateList::store_to_3mf_structure records for a sliced plate.
    auto info = std::make_shared<PlateData>();
    info->gcode_prediction        = std::to_string(int(normal.time));
    info->first_layer_time        = std::to_string(result.initial_layer_time);
    if (stats.total_weight != 0.) {
        char weight[32];
        std::snprintf(weight, sizeof(weight), "%.2f", stats.total_weight);
        info->gcode_weight = weight;
    }
    info->toolpath_outside        = result.toolpath_outside;
    info->is_label_object_enabled = result.label_object_enabled;
    info->is_support_used         = print.is_support_used();
    info->parse_filament_info(&result);
    m_slice_info[gcode_path] = info;

    const std::array<double, 2> origin = plate_origin(plate);
    out["origin"]       = {origin[0], origin[1]};
    return out;
}

void OrcaEngine::cancel()
{
    std::lock_guard<std::mutex> cancel_lock(m_cancel_mutex);
    if (m_running_print)
        m_running_print->cancel();
}

} // namespace orca
