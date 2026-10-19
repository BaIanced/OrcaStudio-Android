// OrcaEngine tools that work on single objects with the current selection: picking, painting,
// variable layer height, per-plate custom G-code / wipe tower, flushing volumes and the process
// option dependency rules.

#include "OrcaEngine.hpp"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <memory>
#include <stdexcept>

#include <boost/log/trivial.hpp>

#include "libslic3r/AABBMesh.hpp"
#include "libslic3r/CustomGCode.hpp"
#include "libslic3r/FlushVolCalc.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Slicing.hpp"
#include "libslic3r/TriangleSelector.hpp"

#include "OptionToggles.hpp"
#include "Thumbnails.hpp"

namespace Slic3r { namespace GUI {
ToggleApp &wxGetApp()
{
    static ToggleApp app;
    return app;
}
}} // namespace Slic3r::GUI

namespace orca {

using namespace Slic3r;

namespace {

Vec3d to_vec(const std::array<double, 3> &a) { return Vec3d(a[0], a[1], a[2]); }
json  vec_json(const Vec3d &v) { return {v.x(), v.y(), v.z()}; }

template<class T> void write_raw(std::ofstream &f, const T &v) { f.write(reinterpret_cast<const char *>(&v), sizeof(T)); }

SlicingParameters slicing_params(const DynamicPrintConfig &config, const ModelObject &obj)
{
    const float height = float(obj.instance_bounding_box(0).size().z());
    return PrintObject::slicing_parameters(config, obj, height, Vec3d(1., 1., 1.));
}

} // namespace

// --- Picking and painting ----------------------------------------------------------------------

json OrcaEngine::pick(const std::array<double, 3> &origin, const std::array<double, 3> &dir)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Vec3d s = to_vec(origin), d = to_vec(dir).normalized();
    json best = {{"hit", false}};
    double best_dist = std::numeric_limits<double>::max();
    for (size_t oi = 0; oi < m_model->objects.size(); ++oi) {
        const ModelObject *obj = m_model->objects[oi];
        for (size_t ii = 0; ii < obj->instances.size(); ++ii)
            for (size_t vi = 0; vi < obj->volumes.size(); ++vi) {
                const ModelVolume *vol = obj->volumes[vi];
                const Transform3d trafo = obj->instances[ii]->get_matrix() * vol->get_matrix();
                const Transform3d inv   = trafo.inverse();
                // Ray in volume coordinates; the hit distance is measured back in world space.
                const Vec3d ls = inv * s;
                const Vec3d ld = (inv.linear() * d).normalized();
                std::shared_ptr<AABBMesh> &mesh = m_aabb[&vol->mesh()];
                if (!mesh)
                    mesh = std::make_shared<AABBMesh>(vol->mesh());
                const auto hit = mesh->query_ray_hit(ls, ld);
                if (!hit.is_hit())
                    continue;
                const Vec3d world = trafo * hit.position();
                const double dist = (world - s).norm();
                if (dist >= best_dist)
                    continue;
                best_dist = dist;
                const Vec3d n = (trafo.linear().inverse().transpose() * hit.normal()).normalized();
                best = {{"hit", true},
                        {"object", oi},
                        {"instance", ii},
                        {"volume", vi},
                        {"facet", hit.face()},
                        {"point", vec_json(world)},
                        {"normal", vec_json(n)},
                        {"local", vec_json(hit.position())}};
            }
    }
    return best;
}

namespace {

// The facet annotation of a volume that a paint kind edits.
FacetsAnnotation &facets_of(ModelVolume &vol, const std::string &kind)
{
    if (kind == "fuzzy")
        return vol.fuzzy_skin_facets;
    if (kind == "support")
        return vol.supported_facets;
    if (kind == "seam")
        return vol.seam_facets;
    if (kind == "color")
        return vol.mmu_segmentation_facets;
    throw std::runtime_error("Unknown paint kind: " + kind);
}

} // namespace

void OrcaEngine::write_paint_mesh()
{
    // Written next to the target and renamed over it, so the app never reads a half-written file.
    const std::string tmp = m_paint_path + ".tmp";
    std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
    if (!f)
        throw std::runtime_error("Cannot write " + m_paint_path);
    using clock = std::chrono::steady_clock;
    auto ms = [](clock::time_point a) { return std::chrono::duration<double, std::milli>(clock::now() - a).count(); };
    m_paint_extract_ms = m_paint_bytes_ms = 0.;
    m_paint_triangles = 0;
    // File bytes of one copy's painted triangles: reused while the triangles and the copy's transform are the same
    // (written one float at a time, every copy of every painted object, on every paint call before).
    decltype(m_paint_bytes) bytes_used;
    auto emit = [&](const PaintKey &key, const indexed_triangle_set &its, const Transform3d &trafo, float obj_code, float type_code) {
        const auto bytes_key = std::make_tuple(key, obj_code, type_code);
        std::array<double, 16> matrix;
        std::copy(trafo.matrix().data(), trafo.matrix().data() + 16, matrix.begin());
        auto it = m_paint_bytes.find(bytes_key);
        std::shared_ptr<std::vector<float>> data;
        if (it != m_paint_bytes.end() && it->second.trafo == matrix) {
            data = it->second.data;
        } else {
            const auto t = clock::now();
            data = std::make_shared<std::vector<float>>();
            data->reserve(its.indices.size() * 24);
            // A mirroring transform flips the winding; swap two corners so the triangles face out.
            const bool mirrored = trafo.matrix().block<3, 3>(0, 0).determinant() < 0.;
            for (const Vec3i32 &tri : its.indices) {
                const Vec3f a = (trafo * its.vertices[tri[0]].cast<double>()).cast<float>();
                const Vec3f b = (trafo * its.vertices[tri[mirrored ? 2 : 1]].cast<double>()).cast<float>();
                const Vec3f c = (trafo * its.vertices[tri[mirrored ? 1 : 2]].cast<double>()).cast<float>();
                Vec3f n = (b - a).cross(c - a);
                n = n.norm() > 0.f ? Vec3f(n.normalized()) : Vec3f(0.f, 0.f, 1.f);
                for (const Vec3f *v : {&a, &b, &c})
                    data->insert(data->end(), {(*v)[0], (*v)[1], (*v)[2], n[0], n[1], n[2], obj_code, type_code});
            }
            m_paint_bytes_ms += ms(t);
        }
        bytes_used[bytes_key] = {matrix, data};
        m_paint_triangles += its.indices.size();
        f.write(reinterpret_cast<const char *>(data->data()), std::streamsize(data->size() * sizeof(float)));
    };
    const int max_filament = std::max<int>(1, int(m_sel_filaments.size()));
    // Painted triangles of one annotation state: from the cache, else from the live paint selector, else from one
    // selector per annotation (FacetsAnnotation::get_facets deserialized the whole annotation again for every state).
    decltype(m_paint_facets) used;
    const TriangleSelector *loaded = nullptr;
    std::unique_ptr<TriangleSelector> own;
    auto facets_for = [&](const PaintKey &key, const ModelVolume &vol, const FacetsAnnotation &annotation, EnforcerBlockerType state) -> const indexed_triangle_set & {
        auto it = m_paint_facets.find(key);
        std::shared_ptr<indexed_triangle_set> its;
        const LivePaint &live = m_live_paint;
        const bool is_live = live.selector && live.annotation == &annotation && live.timestamp == annotation.timestamp() &&
                             live.mesh.get() == &vol.mesh();
        if (it != m_paint_facets.end()) {
            its = it->second;
        } else if (auto before = m_paint_facets.find(PaintKey{&vol.mesh(), live.previous, int(state)});
                   is_live && int(state) != live.state && before != m_paint_facets.end() && before->second->indices.empty()) {
            its = before->second;
        } else {
            const auto t = clock::now();
            const TriangleSelector *selector = loaded;
            if (is_live) {
                selector = live.selector.get();
            } else if (selector == nullptr) {
                own = std::make_unique<TriangleSelector>(vol.mesh());
                own->deserialize(annotation.get_data(), false);
                selector = loaded = own.get();
            }
            its = std::make_shared<indexed_triangle_set>(selector->get_facets(state));
            m_paint_extract_ms += ms(t);
        }
        used[key] = its;
        return *its;
    };
    for (size_t oi = 0; oi < m_model->objects.size(); ++oi) {
        const ModelObject *obj = m_model->objects[oi];
        for (const ModelVolume *vol : obj->volumes) {
            // (annotation, state, overlay type code)
            std::vector<std::tuple<const FacetsAnnotation *, EnforcerBlockerType, float>> layers;
            if (!vol->fuzzy_skin_facets.empty())
                layers.emplace_back(&vol->fuzzy_skin_facets, EnforcerBlockerType::FUZZY_SKIN, 5.f);
            if (!vol->supported_facets.empty()) {
                layers.emplace_back(&vol->supported_facets, EnforcerBlockerType::ENFORCER, 6.f);
                layers.emplace_back(&vol->supported_facets, EnforcerBlockerType::BLOCKER, 7.f);
            }
            if (!vol->seam_facets.empty()) {
                layers.emplace_back(&vol->seam_facets, EnforcerBlockerType::ENFORCER, 8.f);
                layers.emplace_back(&vol->seam_facets, EnforcerBlockerType::BLOCKER, 9.f);
            }
            if (!vol->mmu_segmentation_facets.empty())
                for (int e = 1; e <= max_filament; ++e)
                    layers.emplace_back(&vol->mmu_segmentation_facets, EnforcerBlockerType(e), 10.f + float(e - 1));
            const FacetsAnnotation *previous = nullptr;
            for (const auto &[annotation, state, code] : layers) {
                if (annotation != previous) {
                    // A new annotation: the selector loaded for the previous one doesn't apply.
                    loaded = nullptr;
                    previous = annotation;
                }
                const PaintKey key{&vol->mesh(), uint64_t(annotation->timestamp()), int(state)};
                const indexed_triangle_set &painted = facets_for(key, *vol, *annotation, state);
                if (painted.indices.empty())
                    continue;
                for (size_t ii = 0; ii < obj->instances.size(); ++ii)
                    emit(key, painted, obj->instances[ii]->get_matrix() * vol->get_matrix(), float(oi * INSTANCE_ID_STRIDE + ii), code);
            }
        }
    }
    m_paint_facets = std::move(used);
    m_paint_bytes = std::move(bytes_used);
    f.close();
    if (std::rename(tmp.c_str(), m_paint_path.c_str()) != 0)
        throw std::runtime_error("Cannot write " + m_paint_path);
    ++m_paint_version;
}

json OrcaEngine::paint(const std::vector<PaintHit> &hits, const std::array<double, 3> &camera, double radius, const std::string &kind,
                       int state, bool new_stroke, const std::vector<double> &section)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (const PaintHit &h : hits) {
        ModelObject &obj = object_at(h.object);
        if (h.instance < 0 || h.instance >= int(obj.instances.size()) || h.volume < 0 || h.volume >= int(obj.volumes.size()))
            throw std::runtime_error("Invalid paint target");
    }
    const auto t0 = std::chrono::steady_clock::now();
    int loads = 0;
    if (new_stroke && !hits.empty())
        push_undo();

    // Runs of hits on the same volume copy share one selector: deserialized once, saved once.
    for (size_t i = 0; i < hits.size();) {
        const PaintHit &first = hits[i];
        ModelObject &obj = object_at(first.object);
        ModelVolume *vol = obj.volumes[first.volume];
        FacetsAnnotation &facets = facets_of(*vol, kind);

        const Transform3d trafo = obj.instances[first.instance]->get_matrix() * vol->get_matrix();
        Transform3d trafo_no_translate = trafo;
        trafo_no_translate.translation() = Vec3d::Zero();
        const Vec3f camera_local = (trafo.inverse() * to_vec(camera)).cast<float>();
        // The section plane in volume coordinates, as the desktop's get_clipping_plane_in_volume_coordinates:
        // n.(T q) <= d  <=>  (L^T n).q <= d - n.t.
        TriangleSelector::ClippingPlane clip;
        if (section.size() == 4) {
            const Vec3d n(section[0], section[1], section[2]);
            const Vec3d n_local = trafo.linear().transpose() * n;
            clip = TriangleSelector::ClippingPlane({float(n_local.x()), float(n_local.y()), float(n_local.z()),
                                                    float(section[3] - n.dot(trafo.translation()))});
        }

        LivePaint &live = m_live_paint;
        if (!live.selector || live.annotation != &facets || live.timestamp != facets.timestamp() || live.mesh.get() != &vol->mesh()) {
            live = {&facets, vol->mesh_ptr(), 0, std::make_shared<TriangleSelector>(vol->mesh())};
            live.selector->deserialize(facets.get_data(), false);
            ++loads;
        }
        TriangleSelector &selector = *live.selector;
        for (; i < hits.size() && hits[i].object == first.object && hits[i].instance == first.instance && hits[i].volume == first.volume; ++i)
            selector.select_patch(hits[i].facet,
                                  TriangleSelector::SinglePointCursor::cursor_factory(to_vec(hits[i].local).cast<float>(), camera_local,
                                                                                    float(radius), TriangleSelector::CursorType::SPHERE, trafo, clip),
                                  EnforcerBlockerType(std::clamp(state, 0, int(EnforcerBlockerType::ExtruderMax))), trafo_no_translate, true);
        live.previous = facets.timestamp();
        live.state = std::clamp(state, 0, int(EnforcerBlockerType::ExtruderMax));
        facets.set(selector);
        live.timestamp = facets.timestamp();
    }
    const auto t1 = std::chrono::steady_clock::now();
    write_paint_mesh();
    const auto t2 = std::chrono::steady_clock::now();
    json out = scene_json();
    const auto t3 = std::chrono::steady_clock::now();
    auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    if (ms(t0, t3) > 30.)
        BOOST_LOG_TRIVIAL(warning) << "paint: " << hits.size() << " hits, " << loads << " selector loads, select " << ms(t0, t1)
                                << " ms, paint mesh " << ms(t1, t2) << " ms (extract " << m_paint_extract_ms << ", bytes "
                                << m_paint_bytes_ms << ", " << m_paint_triangles << " triangles), scene " << ms(t2, t3) << " ms";
    return out;
}

json OrcaEngine::paint_clear(int object, const std::string &kind)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    for (ModelVolume *vol : obj.volumes) {
        if (kind.empty()) {
            for (const char *k : {"fuzzy", "support", "seam", "color"})
                facets_of(*vol, k).reset();
        } else {
            facets_of(*vol, kind).reset();
        }
    }
    write_paint_mesh();
    return scene_json();
}

// --- Variable layer height -----------------------------------------------------------------------

json OrcaEngine::layer_result(ModelObject &obj)
{
    const SlicingParameters params = slicing_params(selection_config(), obj);
    std::vector<coordf_t> profile = obj.layer_height_profile.get();
    if (profile.empty())
        PrintObject::update_layer_height_profile(obj, params, profile);
    json out = commit();
    out["profile"] = profile;
    out["min"]     = params.min_layer_height;
    out["max"]     = params.max_layer_height;
    out["height"]  = params.object_print_z_height();
    return out;
}

json OrcaEngine::layer_profile(int object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return layer_result(object_at(object));
}

json OrcaEngine::layer_adaptive(int object, double quality)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    const SlicingParameters params = slicing_params(selection_config(), obj);
    obj.layer_height_profile.set(layer_height_profile_adaptive(params, obj, float(std::clamp(quality, 0., 1.))));
    return layer_result(obj);
}

json OrcaEngine::layer_smooth(int object, int radius, bool keep_min)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    const SlicingParameters params = slicing_params(selection_config(), obj);
    std::vector<coordf_t> profile = obj.layer_height_profile.get();
    if (profile.empty())
        PrintObject::update_layer_height_profile(obj, params, profile);
    obj.layer_height_profile.set(smooth_height_profile(profile, params, HeightProfileSmoothingParams(unsigned(std::max(1, radius)), keep_min)));
    return layer_result(obj);
}

json OrcaEngine::layer_adjust(int object, double z, double delta, double band)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    const SlicingParameters params = slicing_params(selection_config(), obj);
    std::vector<coordf_t> profile = obj.layer_height_profile.get();
    if (profile.empty())
        PrintObject::update_layer_height_profile(obj, params, profile);
    adjust_layer_height_profile(obj, params, profile, z, std::abs(delta), band,
                                delta >= 0 ? LAYER_HEIGHT_EDIT_ACTION_INCREASE : LAYER_HEIGHT_EDIT_ACTION_DECREASE);
    obj.layer_height_profile.set(profile);
    return layer_result(obj);
}

json OrcaEngine::layer_reset(int object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    obj.layer_height_profile.clear();
    return layer_result(obj);
}

// --- Plate extras ----------------------------------------------------------------------------------

json OrcaEngine::set_layer_gcodes(int plate, const json &items)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (plate < 0 || plate >= int(m_plates.size()))
        throw std::runtime_error("Invalid plate");
    push_undo();
    CustomGCode::Info info;
    info.mode = m_sel_filaments.size() > 1 ? CustomGCode::MultiAsSingle : CustomGCode::SingleExtruder;
    for (const json &it : items) {
        CustomGCode::Item item;
        item.print_z  = it.at("z").get<double>();
        const std::string type = it.value("type", "pause");
        // OrcaSlicer no longer emits ColorChange (see ProcessLayer::emit_custom_gcode_per_print_z): a
        // colour change is a change to another filament slot, which runs change_filament_gcode.
        item.type     = type == "color" ? CustomGCode::ToolChange : type == "custom" ? CustomGCode::Custom : CustomGCode::PausePrint;
        item.extruder = it.value("extruder", 1);
        item.color    = it.value("color", std::string());
        item.extra    = it.value("extra", std::string());
        info.gcodes.push_back(item);
    }
    std::sort(info.gcodes.begin(), info.gcodes.end());
    if (info.gcodes.empty())
        m_model->plates_custom_gcodes.erase(plate);
    else
        m_model->plates_custom_gcodes[plate] = info;
    return commit();
}

json OrcaEngine::set_wipe_tower(int plate, double x, double y)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (plate < 0 || plate >= int(m_plates.size()))
        throw std::runtime_error("Invalid plate");
    push_undo();
    PlateInfo &p = m_plates[plate];
    p.has_wipe_tower_pos = std::isfinite(x) && std::isfinite(y);
    p.wipe_tower_x = p.has_wipe_tower_pos ? x : 0.;
    p.wipe_tower_y = p.has_wipe_tower_pos ? y : 0.;
    return commit();
}

std::vector<double> OrcaEngine::auto_flush_matrix(const DynamicPrintConfig &config)
{
    const auto  *colours = config.option<ConfigOptionStrings>("filament_colour");
    const size_t n       = colours ? colours->values.size() : 0;
    int min_volume = 0;
    if (const auto *vol = config.option<ConfigOptionFloatsNullable>("nozzle_volume"); vol && !vol->values.empty())
        min_volume = int(vol->get_at(0));
    int dataset = 0;
    if (const auto *ds = config.option<ConfigOptionIntsNullable>("nozzle_flush_dataset"); ds && !ds->values.empty())
        dataset = ds->values.front();

    std::vector<double> matrix(n * n, 0.);
    FlushVolCalculator  calc(min_volume, g_max_flush_volume, dataset);
    auto byte = [](float v) { return (unsigned char)std::clamp(int(std::lround(v * 255.f)), 0, 255); };
    for (size_t from = 0; from < n; ++from)
        for (size_t to = 0; to < n; ++to) {
            if (from == to)
                continue;
            const auto a = parse_color(colours->values[from]), b = parse_color(colours->values[to]);
            matrix[from * n + to] = calc.calc_flush_vol(255, byte(a[0]), byte(a[1]), byte(a[2]), 255, byte(b[0]), byte(b[1]), byte(b[2]));
        }
    return matrix;
}

json OrcaEngine::flush_matrix()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const std::vector<double> matrix = auto_flush_matrix(selection_config());
    return {{"matrix", matrix}, {"size", size_t(std::lround(std::sqrt(double(matrix.size()))))}};
}

json OrcaEngine::option_states(const json &object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    DynamicPrintConfig config = selection_config();
    // GUI_ObjectSettings.cpp update_config_values: the object's own settings decide what is greyed out in its editor.
    if (object.is_object())
        for (const auto &[key, value] : object.items())
            if (value.is_string() && config.has(key))
                try { config.set_deserialize_strict(key, value.get<std::string>()); } catch (const std::exception &) {}
    GUI::wxGetApp().preset_bundle = m_bundle.get();
    GUI::ConfigManipulation toggles;
    toggles.is_BBL_Printer = m_bundle->is_bbl_vendor();
    toggles.toggle_print_fff_options(&config, 0, !object.is_object());
    json disabled = json::array(), hidden = json::array();
    for (const auto &[key, enabled] : toggles.field_enabled)
        if (!enabled)
            disabled.push_back(key);
    for (const auto &[key, visible] : toggles.line_visible)
        if (!visible)
            hidden.push_back(key);

    // Printer / filament options the desktop's TabPrinter / TabFilament hide for Bambu Lab printers
    // (and the Bambu-only ones for others). Bambu printers also never see options of other firmware
    // (Klipper, Marlin, pellet and toolchanger printers) or of print hosts: they connect from the
    // Device screen.
    static const char *non_bbl[] = {
        "use_firmware_retraction", "use_relative_e_distances", "support_multi_bed_types", "pellet_modded_printer",
        "bed_mesh_max", "bed_mesh_min", "bed_mesh_probe_distance", "adaptive_bed_mesh_margin", "thumbnails", "thumbnails_format",
        "wipe_tower_type", "enable_filament_ramming", "cooling_tube_retraction", "cooling_tube_length", "parking_pos_retraction",
        "extra_loading_move", "high_current_on_filament_swap", "purge_in_prime_tower", "tool_change_on_wipe_tower",
        "filament_minimal_purge_on_wipe_tower", "filament_loading_speed_start", "filament_loading_speed",
        "filament_unloading_speed_start", "filament_unloading_speed", "filament_toolchange_delay", "filament_cooling_moves",
        "filament_cooling_initial_speed", "filament_cooling_final_speed",
        "gcode_flavor", "accel_to_decel_enable", "accel_to_decel_factor", "exclude_object", "disable_m73",
        "default_junction_deviation", "machine_max_junction_deviation", "pellet_flow_coefficient", "preheat_time", "preheat_steps",
        "host_type", "print_host", "print_host_webui", "printhost_apikey", "printhost_cafile", "printhost_port",
        "printhost_authorization_type", "printhost_user", "printhost_password", "printhost_ssl_ignore_revoke"};
    static const char *bbl_only[] = {"scan_first_layer", "bbl_calib_mark_logo", "bbl_use_printhost"};
    if (toggles.is_BBL_Printer)
        for (const char *key : non_bbl)
            hidden.push_back(key);
    else
        for (const char *key : bbl_only)
            hidden.push_back(key);
    return {{"disabled", disabled}, {"hidden", hidden}};
}

} // namespace orca
