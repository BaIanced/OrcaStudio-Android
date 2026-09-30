// OrcaEngine projects and files: 3MF projects in the desktop format (plates, per-plate bed type
// and custom G-code, settings, thumbnails), STL export and user preset import/export.

#include "OrcaEngine.hpp"

#include <cmath>
#include <stdexcept>

#include "libslic3r/AppConfig.hpp"
#include "libslic3r/Format/STL.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/PrintConfig.hpp"

#include "Thumbnails.hpp"

namespace orca {

using namespace Slic3r;

namespace {

const Preset *find_by_name(const PresetCollection &collection, const std::string &name)
{
    for (size_t i = 0; i < collection.size(); ++i)
        if (collection.preset(i).name == name)
            return &collection.preset(i);
    return nullptr;
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

// Options of `keys` whose value in `project` differs from `base` (slot `index` for vectors).
json diff_options(const DynamicPrintConfig &project, const DynamicPrintConfig &base, const std::vector<std::string> &keys, size_t index)
{
    json out = json::object();
    for (const std::string &key : keys) {
        if (!project.has(key) || !base.has(key))
            continue;
        const std::string a = value_at(project, key, index), b = value_at(base, key, index);
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

json OrcaEngine::preset_file(const std::string &type, const std::string &name)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Preset *preset = find_by_name(collection(type), name);
    if (preset == nullptr || preset->file.empty())
        throw std::runtime_error("This preset has no file to export");
    return {{"path", preset->file}, {"system", preset->is_system}};
}

} // namespace orca
