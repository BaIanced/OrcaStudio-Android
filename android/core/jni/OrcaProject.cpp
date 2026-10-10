// OrcaEngine projects and files: 3MF projects in the desktop format (plates, per-plate bed type
// and custom G-code, settings, thumbnails), STL export and user preset import/export.

#include "OrcaEngine.hpp"

#include <cmath>
#include <fstream>
#include <functional>
#include <map>
#include <stdexcept>

#include "libslic3r/AppConfig.hpp"
#include "libslic3r/Format/STL.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"

#include "Thumbnails.hpp"

namespace orca {

using namespace Slic3r;

namespace {

// Runs a function when the scope ends, also when an exception leaves it.
struct OnExit {
    std::function<void()> fn;
    ~OnExit() { fn(); }
};

const Preset *find_by_name(const PresetCollection &collection, const std::string &name)
{
    for (size_t i = 0; i < collection.size(); ++i)
        if (collection.preset(i).name == name)
            return &collection.preset(i);
    return nullptr;
}

// The id a Bambu printer knows a filament by. OrcaSlicer re-keyed the Bambu bundle's filament_id
// (upstream ec207e67a); resources/printers/bambu_filament_ids.json maps back, as
// BBLPrinterAgent::from_orca_filament_id does on the desktop (libslic3r_gui, not built here).
std::string bambu_filament_id(const std::string &id)
{
    static const std::map<std::string, std::string> to_bambu = [] {
        std::map<std::string, std::string> m;
        try {
            std::ifstream file(resources_dir() + "/printers/bambu_filament_ids.json");
            const json doc = json::parse(file);
            for (const auto &[orca_id, row] : doc.at("filaments").items())
                m.emplace(orca_id, row.at("bambu_id").get<std::string>());
        } catch (const std::exception &) {
            // Missing or unreadable: ids pass through, as on the desktop.
        }
        return m;
    }();
    auto it = to_bambu.find(id);
    return it != to_bambu.end() ? it->second : id;
}

// Bambu printers report a tray's filament as Bambu's id (GFA00 = Bambu PLA Basic), but OrcaSlicer
// re-keyed the Bambu bundle's filament_id to its own ids (upstream ec207e67a), so sync_ams_list()
// no longer finds the preset and falls back to Generic. The bundle's setting_id still carries
// Bambu's id (GFA00 -> GFSA00_04), so translate through it when the reported id matches nothing.
std::string current_filament_id(const PresetCollection &filaments, const std::string &reported)
{
    if (reported.size() < 3 || reported.compare(0, 2, "GF") != 0)
        return reported;
    for (size_t i = 0; i < filaments.size(); ++i)
        if (filaments.preset(i).filament_id == reported)
            return reported;
    const std::string prefix = "GFS" + reported.substr(2);
    for (size_t i = 0; i < filaments.size(); ++i) {
        const Preset &p = filaments.preset(i);
        if (p.is_system && p.is_compatible && !p.filament_id.empty() &&
            (p.setting_id == prefix || p.setting_id.compare(0, prefix.size() + 1, prefix + "_") == 0))
            return p.filament_id;
    }
    return reported;
}

// Value of option `key` for filament slot `index` (vector options) or the scalar value.
std::string value_at(const DynamicPrintConfig &config, const std::string &key, size_t index)
{
    const ConfigOption *opt = config.option(key);
    if (opt == nullptr)
        return {};
    if (!opt->is_vector())
        return opt->serialize();
    const std::vector<std::string> values = static_cast<const ConfigOptionVectorBase *>(opt)->vserialize();
    if (values.empty())
        return {};
    return values[std::min(index, values.size() - 1)];
}

// Options of `keys` whose value in `project` (slot `index` for vectors) differs from `base`. The base
// is one preset: its vector entries are printer variants, not slots, so its first entry counts
// (comparing slot 2 with the second variant marked e.g. filament_dev_ams_drying_ams_limitations
// "1" vs "0" as changed on every reload).
json diff_options(const DynamicPrintConfig &project, const DynamicPrintConfig &base, const std::vector<std::string> &keys, size_t index)
{
    json out = json::object();
    for (const std::string &key : keys) {
        if (!project.has(key) || !base.has(key))
            continue;
        const std::string a = value_at(project, key, index), b = value_at(base, key, 0);
        if (a != b)
            out[key] = a;
    }
    return out;
}

std::array<double, 4> rect_of(const DynamicPrintConfig &config, const std::array<double, 4> &fallback)
{
    const auto *area = config.option<ConfigOptionPoints>("printable_area");
    if (area == nullptr || area->values.empty())
        return fallback;
    BoundingBoxf bb;
    for (const Vec2d &p : area->values)
        bb.merge(p);
    return {bb.min.x(), bb.min.y(), bb.max.x(), bb.max.y()};
}

} // namespace

json OrcaEngine::save_project(const std::string &path)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    DynamicPrintConfig config = selection_config();

    std::vector<std::array<float, 3>> colors;
    if (const auto *opt = config.option<ConfigOptionStrings>("filament_colour"))
        for (const std::string &c : opt->values)
            colors.push_back(parse_color(c));

    PlateDataPtrs plates;
    std::vector<ThumbnailData> thumbs(m_plates.size());
    std::vector<ThumbnailData *> thumb_ptrs;
    for (size_t p = 0; p < m_plates.size(); ++p) {
        auto *pd = new PlateData();
        pd->plate_index = int(p);
        for (size_t oi = 0; oi < m_model->objects.size(); ++oi)
            for (size_t ii = 0; ii < m_model->objects[oi]->instances.size(); ++ii)
                if (plate_of(*m_model->objects[oi], ii) == int(p))
                    pd->objects_and_instances.emplace_back(int(oi), int(ii));
        if (!m_plates[p].bed_type.empty())
            pd->config.set_deserialize_strict("curr_bed_type", m_plates[p].bed_type);
        plates.push_back(pd);
        thumbs[p] = render_thumbnail(*plate_model(int(p)), 512, 512, colors);
        thumb_ptrs.push_back(&thumbs[p]);
    }

    StoreParams params;
    params.path           = path;
    params.model          = m_model.get();
    params.plate_data_list = plates;
    params.config         = &config;
    params.thumbnail_data = thumb_ptrs;
    params.strategy       = SaveStrategy::Silence | SaveStrategy::SplitModel | SaveStrategy::ShareMesh | SaveStrategy::Zip64;
    const bool ok = store_bbs_3mf(params);
    release_PlateData_list(plates);
    if (!ok)
        throw std::runtime_error("Saving the project failed");
    return {{"path", path}};
}

json OrcaEngine::load_project(const std::string &path)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();

    DynamicPrintConfig        config;
    ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
    PlateDataPtrs             plate_data;
    std::vector<Preset *>     project_presets;
    bool                      is_bbl = false;
    Semver                    version;
    Model loaded = Model::read_from_file(path, &config, &substitutions, LoadStrategy::LoadModel | LoadStrategy::LoadConfig,
                                         &plate_data, &project_presets, &is_bbl, &version);
    for (Preset *p : project_presets)
        delete p;
    if (loaded.objects.empty()) {
        release_PlateData_list(plate_data);
        throw std::runtime_error("The project does not contain any object");
    }

    push_undo();
    m_model = std::make_unique<Model>(std::move(loaded));
    m_plates.assign(std::max<size_t>(1, plate_data.size()), {});
    for (size_t p = 0; p < plate_data.size() && p < m_plates.size(); ++p)
        if (plate_data[p]->config.has("curr_bed_type"))
            m_plates[p].bed_type = plate_data[p]->config.opt_serialize("curr_bed_type");
    release_PlateData_list(plate_data);

    // Positions are laid out for the project's printer bed; move them onto ours.
    const std::array<double, 4> file_rect = rect_of(config, bed_rect());
    relayout(m_plates.size(), file_rect);
    for (ModelObject *obj : m_model->objects) {
        if (obj->instances.empty()) {
            obj->center_around_origin();
            obj->add_instance();
        }
        drop_to_bed(*obj);
    }

    // Describe the project settings relative to presets of the same names.
    json project = json::object();
    project["printer"] = config.has("printer_settings_id") ? config.opt_string("printer_settings_id") : "";
    project["print"]   = config.has("print_settings_id") ? config.opt_string("print_settings_id") : "";
    json filaments = json::array(), filament_overrides = json::array(), colors = json::array();
    const auto *fil_ids = config.option<ConfigOptionStrings>("filament_settings_id");
    const size_t n_fil  = fil_ids ? fil_ids->values.size() : 0;
    for (size_t i = 0; i < n_fil; ++i) {
        const std::string name = fil_ids->values[i];
        filaments.push_back(name);
        const Preset *base = find_by_name(m_bundle->filaments, name);
        filament_overrides.push_back(base ? diff_options(config, base->config, Preset::filament_options(), i) : json::object());
        colors.push_back(value_at(config, "filament_colour", i));
    }
    const Preset *print_base = find_by_name(m_bundle->prints, project["print"].get<std::string>());
    project["print_overrides"]    = print_base ? diff_options(config, print_base->config, Preset::print_options(), 0) : json::object();
    project["filaments"]          = filaments;
    project["filament_overrides"] = filament_overrides;
    project["filament_colors"]    = colors;

    json out = commit();
    out["project"] = project;
    return out;
}

json OrcaEngine::export_gcode_3mf(int plate, const std::string &gcode, const std::string &path)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    if (plate < 0 || plate >= int(m_plates.size()))
        throw std::runtime_error("Invalid plate");
    DynamicPrintConfig config = selection_config();
    std::unique_ptr<Model> model = plate_model(plate);

    std::vector<std::array<float, 3>> colors;
    if (const auto *opt = config.option<ConfigOptionStrings>("filament_colour"))
        for (const std::string &c : opt->values)
            colors.push_back(parse_color(c));
    ThumbnailData thumb = render_thumbnail(*model, 512, 512, colors);

    PlateDataPtrs plates;
    auto *pd = new PlateData();
    pd->plate_index    = 0;
    pd->gcode_file     = gcode;
    pd->is_sliced_valid = true;
    for (size_t oi = 0; oi < model->objects.size(); ++oi)
        for (size_t ii = 0; ii < model->objects[oi]->instances.size(); ++ii)
            pd->objects_and_instances.emplace_back(int(oi), int(ii));
    if (!m_plates[plate].bed_type.empty())
        pd->config.set_deserialize_strict("curr_bed_type", m_plates[plate].bed_type);
    if (auto it = m_slice_info.find(gcode); it != m_slice_info.end()) {
        const PlateData &s = *it->second;
        pd->gcode_prediction        = s.gcode_prediction;
        pd->gcode_weight            = s.gcode_weight;
        pd->first_layer_time        = s.first_layer_time;
        pd->toolpath_outside        = s.toolpath_outside;
        pd->is_label_object_enabled = s.is_label_object_enabled;
        pd->is_support_used         = s.is_support_used;
        pd->slice_filaments_info    = s.slice_filaments_info;
    }
    // As Plater::export_3mf: the printer model's id and each used filament's type, colour and id.
    const std::string &printer_model = config.opt_string("printer_model");
    for (const auto &[name, vendor] : m_bundle->vendors)
        for (const auto &m : vendor.models)
            if (m.name == printer_model)
                pd->printer_model_id = m.model_id;
    const auto *ids     = config.option<ConfigOptionStrings>("filament_ids");
    const auto *colours = config.option<ConfigOptionStrings>("filament_colour");
    for (FilamentInfo &f : pd->slice_filaments_info) {
        std::string displayed;
        f.type        = config.get_filament_type(displayed, f.id);
        f.filament_id = ids && !ids->values.empty() ? ids->get_at(f.id) : "";
        if (m_bundle->is_bbl_vendor())
            f.filament_id = bambu_filament_id(f.filament_id);
        f.color = colours && !colours->values.empty() ? colours->get_at(f.id) : "#FFFFFF";
    }
    plates.push_back(pd);

    StoreParams params;
    params.path            = path;
    params.model           = model.get();
    params.plate_data_list = plates;
    params.config          = &config;
    params.thumbnail_data  = {&thumb};
    params.export_plate_idx = 0;
    params.strategy = SaveStrategy::Silence | SaveStrategy::WithGcode | SaveStrategy::SkipModel | SaveStrategy::WithSliceInfo |
                      SaveStrategy::SplitModel | SaveStrategy::ShareMesh | SaveStrategy::Zip64;
    const bool ok = store_bbs_3mf(params);
    release_PlateData_list(plates);
    if (!ok)
        throw std::runtime_error("Writing the .gcode.3mf failed");
    return {{"path", path}};
}

json OrcaEngine::export_stl(const std::string &path, int plate)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    TriangleMesh mesh;
    if (plate >= 0) {
        if (plate >= int(m_plates.size()))
            throw std::runtime_error("Invalid plate");
        mesh = plate_model(plate)->mesh();
    } else {
        mesh = m_model->mesh();
    }
    if (mesh.empty())
        throw std::runtime_error("Nothing to export");
    if (!store_stl(path.c_str(), &mesh, true))
        throw std::runtime_error("Writing the STL failed");
    return {{"path", path}, {"triangles", mesh.facets_count()}};
}

json OrcaEngine::import_presets(const std::vector<std::string> &paths)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    std::vector<std::string> files = paths;
    AppConfig app_config;
    // 3 = "yes to all": imported presets replace user presets of the same name.
    m_bundle->import_presets(files, [](const std::string &) { return 3; }, ForwardCompatibilitySubstitutionRule::EnableSilent, app_config);
    for (PresetCollection *c : {static_cast<PresetCollection *>(&m_bundle->printers), &m_bundle->prints, &m_bundle->filaments})
        for (size_t i = 0; i < c->size(); ++i)
            c->preset(i, true).is_visible = true;
    json out = select_printer_locked(m_printer);
    out["printers"] = printer_list_locked();
    return out;
}

json OrcaEngine::load_cloud_presets(const json &presets)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    std::map<std::string, std::map<std::string, std::string>> my_presets;
    for (const auto &[name, values] : presets.items()) {
        if (!values.is_object())
            continue;
        for (const auto &[key, value] : values.items())
            my_presets[name][key] = value.is_string() ? value.get<std::string>() : value.dump();
    }
    // As the desktop's cloud sync: load (newer cloud versions replace local copies), then write the
    // loaded presets (sync_info "save") to the user preset folder. Presets that were never synced
    // (no setting_id) are not touched by the removal step inside load_user_presets().
    // load_user_presets() also updates the desktop's filament selection and project options
    // (update_multi_material_filament_presets); the engine keeps its own, so restore them.
    OnExit restore{[this, presets = m_bundle->filament_presets, project = m_bundle->project_config] {
        m_bundle->filament_presets = presets;
        m_bundle->project_config   = project;
    }};
    AppConfig app_config;
    m_bundle->load_user_presets(app_config, my_presets, ForwardCompatibilitySubstitutionRule::EnableSilent);
    std::map<std::string, std::string> need_to_delete;
    m_bundle->save_user_presets(app_config, need_to_delete);
    for (PresetCollection *c : {static_cast<PresetCollection *>(&m_bundle->printers), &m_bundle->prints, &m_bundle->filaments})
        for (size_t i = 0; i < c->size(); ++i)
            c->preset(i, true).is_visible = true;
    // The cloud may have removed the selected printer; the app selects one again from the list.
    return {{"count", my_presets.size()}, {"printers", printer_list_locked()}};
}

json OrcaEngine::cloud_uploads(const std::string &user_id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_bundle)
        throw std::runtime_error("Presets are not loaded");
    // As the desktop's sync thread: user presets that are new or changed (get_user_presets), with
    // the values it uploads (get_differed_values_to_update). Deletions are not uploaded, and
    // presets synced from another account (the Bambu cloud: its user_id) are left alone.
    json out = json::array();
    for (const char *type : {"print", "filament", "printer"}) {
        std::vector<Preset> presets;
        collection(type).get_user_presets(m_bundle.get(), presets);
        for (Preset &preset : presets) {
            if (preset.sync_info == "delete" || preset.sync_info == "will_not_sync")
                continue;
            if (!preset.setting_id.empty() && !preset.user_id.empty() && preset.user_id != user_id)
                continue;
            std::map<std::string, std::string> values;
            if (m_bundle->get_differed_values_to_update(preset, values) != 0)
                continue;
            out.push_back({{"type", type}, {"name", preset.name}, {"setting_id", preset.setting_id},
                           {"sync_info", preset.sync_info}, {"values", values}});
        }
    }
    return out;
}

json OrcaEngine::mark_uploaded(const std::string &type, const std::string &name, const std::string &setting_id,
                               const std::string &sync_info, long long updated_time)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    collection(type).set_sync_info_and_save(name, setting_id, sync_info, updated_time);
    return {{"ok", true}};
}

json OrcaEngine::vendor_version(const std::string &vendor)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_bundle)
        throw std::runtime_error("Presets are not loaded");
    const bool installed = m_bundle->vendors.find(vendor) != m_bundle->vendors.end();
    return {{"version", installed ? m_bundle->get_vendor_profile_version(vendor).to_string() : std::string()}};
}

json OrcaEngine::sync_filaments(const json &trays, const json &filaments)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    // sync_ams_list() works on the desktop's selection state: the filament presets, the per-filament
    // project options and flush matrix, the edited process preset. The engine keeps its own
    // selection (set_selection), so all of that is restored once the result has been read.
    OnExit restore{[this, presets = m_bundle->filament_presets, project = m_bundle->project_config,
                     print = m_bundle->prints.get_edited_preset().config] {
        m_bundle->filament_presets                 = presets;
        m_bundle->project_config                   = project;
        m_bundle->prints.get_edited_preset().config = print;
        m_bundle->filament_ams_list.clear();
    }};
    // The current slots as the desktop's selection, with the per-filament project options sized to it.
    std::vector<std::string> names, colors;
    for (const json &f : filaments) {
        names.push_back(f.at("name").get<std::string>());
        colors.push_back(f.contains("color") && f["color"].is_string() ? f["color"].get<std::string>() : std::string("#FFFFFF"));
    }
    if (names.empty())
        throw std::runtime_error("Select at least one filament");
    m_bundle->filament_presets = names;
    DynamicPrintConfig &project = m_bundle->project_config;
    project.option<ConfigOptionStrings>("filament_colour", true)->values = colors;
    project.option<ConfigOptionStrings>("filament_colour_type", true)->values.resize(names.size(), "1");
    project.option<ConfigOptionStrings>("filament_multi_colour", true)->values.resize(names.size());
    project.option<ConfigOptionInts>("filament_map", true)->values.resize(names.size(), 1);
    project.option<ConfigOptionInts>("filament_volume_map", true)->values.resize(names.size(), int(nvtStandard));

    // The trays as Sidebar::build_filament_ams_list() describes them, in the same order (AMS slots, then the external spool).
    m_bundle->filament_ams_list.clear();
    int index = 0;
    for (const json &t : trays) {
        DynamicPrintConfig tray;
        tray.set_key_value("filament_id", new ConfigOptionStrings{current_filament_id(m_bundle->filaments, t.value("filament_id", std::string()))});
        tray.set_key_value("ams_id", new ConfigOptionStrings{t.value("ams_id", std::string())});
        tray.set_key_value("slot_id", new ConfigOptionStrings{t.value("slot_id", std::string())});
        tray.set_key_value("filament_type", new ConfigOptionStrings{t.value("filament_type", std::string())});
        tray.set_key_value("tray_name", new ConfigOptionStrings{t.value("name", std::string())});
        tray.set_key_value("filament_colour", new ConfigOptionStrings{t.value("color", std::string())});
        auto *multi = new ConfigOptionStrings();
        if (t.contains("colors"))
            for (const json &c : t["colors"])
                multi->values.push_back(c.get<std::string>());
        tray.set_key_value("filament_multi_colour", multi);
        tray.set_key_value("filament_colour_type", new ConfigOptionStrings{t.value("color_type", std::string("1"))});
        tray.set_key_value("filament_exist", new ConfigOptionBools{true});
        tray.set_key_value("filament_changed", new ConfigOptionBool{true});
        m_bundle->filament_ams_list.emplace(index++, std::move(tray));
    }

    // Direct sync (no slot mapping, no appending): one slot per loaded tray.
    std::vector<std::pair<DynamicPrintConfig *, std::string>> unknowns;
    std::map<int, AMSMapInfo> maps;
    MergeFilamentInfo merge_info;
    const unsigned int synced = m_bundle->sync_ams_list(unknowns, false, maps, false, merge_info);

    json out = json::array();
    if (synced > 0) {
        const auto &synced_colors = project.option<ConfigOptionStrings>("filament_colour")->values;
        for (size_t i = 0; i < m_bundle->filament_presets.size(); ++i)
            out.push_back({{"name", m_bundle->filament_presets[i]}, {"color", i < synced_colors.size() ? synced_colors[i] : std::string()}});
    }
    json unknown = json::array();
    for (const auto &[tray, message] : unknowns)
        unknown.push_back({{"tray", tray->opt_string("tray_name", 0u)}, {"message", message}});
    return {{"filaments", out}, {"unknown", unknown}};
}

json OrcaEngine::preset_file(const std::string &type, const std::string &name)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Preset *preset = find_by_name(collection(type), name);
    if (preset == nullptr || preset->file.empty())
        throw std::runtime_error("This preset has no file to export");
    return {{"path", preset->file}, {"system", preset->is_system}};
}

} // namespace orca
