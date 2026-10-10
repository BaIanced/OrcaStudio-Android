// Scene editing for OrcaEngine: plates, object transforms and tools, undo/redo.
//
// Plates are laid out like the desktop app's PartPlateList: a grid of `cols` columns
// (ceil(sqrt(count))), each plate the size of the printer bed, separated by a fifth of its size;
// plate 0 sits at the bed origin, further rows extend towards -Y. An instance belongs to the plate
// under the centre of its bounding box. Keeping this layout makes positions compatible with
// desktop 3MF projects.

#include "OrcaEngine.hpp"
#include "Thumbnails.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <set>
#include <stdexcept>

#include <boost/log/trivial.hpp>

#include "libslic3r/CustomGCode.hpp"
#include "libslic3r/CutUtils.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/ModelArrange.hpp"
#include "libslic3r/Orient.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/QuadricEdgeCollapse.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orca {

using namespace Slic3r;

namespace {

constexpr double PLATE_GAP        = 1. / 5.; // PartPlate.cpp LOGICAL_PART_PLATE_GAP
constexpr size_t MAX_PLATES       = 36;      // PartPlate.hpp MAX_PLATE_COUNT
constexpr size_t MAX_UNDO         = 30;
constexpr double ARRANGE_DISTANCE = 6.;      // mm between arranged objects

int plate_columns(size_t count)
{
    // PartPlate.hpp compute_colum_count()
    const float value = std::sqrt(float(count));
    const float rounded = std::round(value);
    return value > rounded ? int(rounded) + 1 : int(rounded);
}

std::array<double, 2> origin_of(int plate, size_t count, const std::array<double, 4> &rect)
{
    const int cols = std::max(1, plate_columns(count));
    const double w = rect[2] - rect[0], d = rect[3] - rect[1];
    return {(plate % cols) * w * (1. + PLATE_GAP), -(plate / cols) * d * (1. + PLATE_GAP)};
}

int plate_at(const Vec3d &point, size_t count, const std::array<double, 4> &rect)
{
    for (size_t p = 0; p < count; ++p) {
        const auto o = origin_of(int(p), count, rect);
        if (point.x() >= o[0] + rect[0] && point.x() <= o[0] + rect[2] && point.y() >= o[1] + rect[1] &&
            point.y() <= o[1] + rect[3])
            return int(p);
    }
    return -1;
}

int volume_type_code(ModelVolumeType t)
{
    switch (t) {
    case ModelVolumeType::MODEL_PART: return 0;
    case ModelVolumeType::NEGATIVE_VOLUME: return 1;
    case ModelVolumeType::SUPPORT_BLOCKER: return 3;
    case ModelVolumeType::SUPPORT_ENFORCER: return 4;
    default: return 2; // parameter modifiers and seam modifiers
    }
}

ModelVolumeType volume_type_from_code(int code)
{
    switch (code) {
    case 1: return ModelVolumeType::NEGATIVE_VOLUME;
    case 2: return ModelVolumeType::PARAMETER_MODIFIER;
    case 3: return ModelVolumeType::SUPPORT_BLOCKER;
    case 4: return ModelVolumeType::SUPPORT_ENFORCER;
    default: throw std::runtime_error("Unsupported volume type");
    }
}

json config_json(const ModelConfig &config)
{
    json out = json::object();
    for (const std::string &key : config.keys())
        out[key] = config.get().opt_serialize(key);
    return out;
}

json vec_json(const Vec3d &v) { return {v.x(), v.y(), v.z()}; }

Vec3d vec_from(const json &j) { return Vec3d(j.at(0).get<double>(), j.at(1).get<double>(), j.at(2).get<double>()); }

template<class T> void write_raw(std::ofstream &f, const T &v) { f.write(reinterpret_cast<const char *>(&v), sizeof(T)); }

} // namespace

// --- Helpers (m_mutex held) ------------------------------------------------------------------

void OrcaEngine::require_printer() const
{
    if (!m_bundle || m_printer.empty())
        throw std::runtime_error("Select a printer first");
}

ModelObject &OrcaEngine::object_at(int object)
{
    if (object < 0 || object >= int(m_model->objects.size()))
        throw std::runtime_error("Invalid object");
    return *m_model->objects[object];
}

OrcaEngine::Items OrcaEngine::checked_items(Items items) const
{
    if (items.empty())
        throw std::runtime_error("Nothing selected");
    for (const auto &[o, i] : items)
        if (o < 0 || o >= int(m_model->objects.size()) || i < 0 || i >= int(m_model->objects[o]->instances.size()))
            throw std::runtime_error("Invalid instance");
    std::sort(items.begin(), items.end());
    items.erase(std::unique(items.begin(), items.end()), items.end());
    return items;
}

void OrcaEngine::push_undo()
{
    m_undo.push_back({std::make_shared<Model>(*m_model), m_plates});
    if (m_undo.size() > MAX_UNDO) {
        m_undo.pop_front();
        if (m_calib_undo_depth > 0)
            --m_calib_undo_depth;
    }
    m_redo.clear();
}

std::array<double, 4> OrcaEngine::bed_rect() const
{
    BoundingBoxf bed;
    if (m_bundle && !m_printer.empty())
        for (const Vec2d &p : m_bundle->printers.get_selected_preset().config.option<ConfigOptionPoints>("printable_area")->values)
            bed.merge(p);
    if (!bed.defined)
        return {0., 0., 200., 200.};
    return {bed.min.x(), bed.min.y(), bed.max.x(), bed.max.y()};
}

std::array<double, 2> OrcaEngine::plate_origin(int plate) const { return origin_of(plate, m_plates.size(), bed_rect()); }

int OrcaEngine::plate_of(const ModelObject &obj, size_t instance) const
{
    return plate_at(obj.instance_bounding_box(instance).center(), m_plates.size(), bed_rect());
}

void OrcaEngine::relayout(size_t old_count, const std::array<double, 4> &old_rect, const std::function<int(int)> &remap)
{
    const std::array<double, 4> rect = bed_rect();
    for (ModelObject *obj : m_model->objects)
        for (size_t i = 0; i < obj->instances.size(); ++i) {
            const int old_plate = plate_at(obj->instance_bounding_box(i).center(), old_count, old_rect);
            if (old_plate < 0)
                continue;
            const int new_plate = remap ? remap(old_plate) : old_plate;
            if (new_plate < 0)
                continue;
            const auto from = origin_of(old_plate, old_count, old_rect);
            const auto to   = origin_of(new_plate, m_plates.size(), rect);
            // Keep the position relative to the bed centre, so objects stay on a resized bed.
            const Vec3d shift(to[0] + (rect[0] + rect[2]) / 2 - from[0] - (old_rect[0] + old_rect[2]) / 2,
                              to[1] + (rect[1] + rect[3]) / 2 - from[1] - (old_rect[1] + old_rect[3]) / 2, 0.);
            ModelInstance *inst = obj->instances[i];
            inst->set_offset(inst->get_offset() + shift);
        }
    for (ModelObject *obj : m_model->objects)
        obj->invalidate_bounding_box();
    m_layout_rect = rect;
}

void OrcaEngine::drop_to_bed(ModelObject &obj)
{
    // Volumes made by cutting/splitting come without convex hull, but ensure_on_bed() and the
    // arrange polygons are computed from it.
    for (ModelVolume *vol : obj.volumes) {
        if (vol->get_convex_hull().empty())
            vol->calculate_convex_hull();
        vol->invalidate_convex_hull_2d();
    }
    obj.invalidate_bounding_box();
    obj.ensure_on_bed();
}

void OrcaEngine::arrange_plate(int plate, const std::vector<std::pair<ModelObject *, size_t>> *movable)
{
    const std::array<double, 4> rect = bed_rect();
    const auto origin = plate_origin(plate);
    const BoundingBox bed(scaled(Vec2d(rect[0] + origin[0], rect[1] + origin[1])),
                          scaled(Vec2d(rect[2] + origin[0], rect[3] + origin[1])));

    // Spacing, clearance for sequential printing and brims come from the print settings, like in
    // the desktop's ArrangeJob. Without a selection (no printer yet) plain spacing is used.
    DynamicPrintConfig cfg;
    bool               have_cfg = true;
    try {
        cfg = selection_config();
    } catch (const std::exception &) {
        have_cfg = false;
    }

    ArrangePolygons                items, fixed;
    std::vector<ModelInstance *>   item_instances;
    auto is_movable = [&](ModelObject *obj, size_t i) {
        if (!movable)
            return true;
        for (const auto &[o, idx] : *movable)
            if (o == obj && idx == i)
                return true;
        return false;
    };
    for (ModelObject *obj : m_model->objects)
        for (size_t i = 0; i < obj->instances.size(); ++i) {
            const bool mov = is_movable(obj, i);
            if (!mov && plate_of(*obj, i) != plate)
                continue;
            if (movable == nullptr && plate_of(*obj, i) != plate)
                continue;
            ArrangePolygon ap;
            obj->instances[i]->get_arrange_polygon(&ap, have_cfg ? cfg : DynamicPrintConfig());
            // Degenerate outlines (empty or flat meshes) crash the nester; such objects stay put.
            if (ap.poly.contour.size() < 3 || std::abs(ap.poly.contour.area()) <= 0.)
                continue;
            // Like ArrangeJob: the nester skips items still marked UNARRANGED (== BIN_ID_UNFIT).
            ap.bed_idx = 0;
            ap.height  = obj->instance_bounding_box(i).size().z();
            if (ap.extrude_ids.empty())
                ap.extrude_ids.push_back(1);
            if (mov) {
                items.push_back(std::move(ap));
                item_instances.push_back(obj->instances[i]);
            } else {
                ap.bed_idx = 0;
                fixed.push_back(std::move(ap));
            }
        }
    if (items.empty())
        return;

    ArrangeParams params;
    params.min_obj_distance = scaled(ARRANGE_DISTANCE);
    // A phone-sized scene nests quickly on one thread, and exceptions from TBB workers are fragile.
    params.parallel = false;
    if (have_cfg) {
        params.is_seq_print            = cfg.opt_enum<PrintSequence>("print_sequence") == PrintSequence::ByObject;
        params.clearance_radius        = cfg.opt_float("extruder_clearance_radius");
        params.clearance_height_to_rod = cfg.opt_float("extruder_clearance_height_to_rod");
        params.clearance_height_to_lid = cfg.opt_float("extruder_clearance_height_to_lid");
        params.printable_height        = cfg.opt_float("printable_height");
        params.nozzle_height           = cfg.opt_float("nozzle_height");
        if (params.is_seq_print)
            params.bed_shrink_x = params.bed_shrink_y = BED_SHRINK_SEQ_PRINT;
        // The spacing only takes effect through the polygons' inflation.
        arrangement::update_selected_items_inflation(items, &cfg, params);
        arrangement::update_unselected_items_inflation(fixed, &cfg, params);
    } else {
        for (ArrangePolygon &ap : items)
            ap.inflation = params.min_obj_distance / 2;
        for (ArrangePolygon &ap : fixed)
            ap.inflation = params.min_obj_distance / 2;
    }
    arrangement::arrange(items, fixed, bed, params);

    // Whatever does not fit goes onto a new plate, like the desktop app does.
    std::vector<std::pair<ModelObject *, size_t>> overflow;
    for (size_t k = 0; k < items.size(); ++k) {
        ModelInstance *inst = item_instances[k];
        if (items[k].bed_idx == 0) {
            inst->apply_arrange_result(items[k].translation.cast<double>(), items[k].rotation);
        } else {
            ModelObject *obj = inst->get_object();
            for (size_t i = 0; i < obj->instances.size(); ++i)
                if (obj->instances[i] == inst)
                    overflow.emplace_back(obj, i);
        }
    }
    for (ModelObject *obj : m_model->objects)
        obj->invalidate_bounding_box();

    // Items that do not even fit an empty plate stay where they are instead of spawning plates.
    const bool hopeless = fixed.empty() && overflow.size() == items.size();
    if (!overflow.empty() && !hopeless && m_plates.size() < MAX_PLATES) {
        const size_t old_count = m_plates.size();
        m_plates.push_back({});
        relayout(old_count, m_layout_rect);
        const int target = int(m_plates.size()) - 1;
        const auto o = plate_origin(target);
        for (const auto &[obj, i] : overflow) {
            // Park them in the new plate's centre; the arrange call below spreads them out.
            const Vec3d c = obj->instance_bounding_box(i).center();
            ModelInstance *inst = obj->instances[i];
            inst->set_offset(inst->get_offset() +
                             Vec3d(o[0] + (rect[0] + rect[2]) / 2 - c.x(), o[1] + (rect[1] + rect[3]) / 2 - c.y(), 0.));
            obj->invalidate_bounding_box();
        }
        arrange_plate(target, &overflow);
    }
}

void OrcaEngine::write_mesh()
{
    // Written next to the target and renamed over it, so the app never reads a half-written file.
    const std::string tmp = m_mesh_path + ".tmp";
    std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
    if (!f)
        throw std::runtime_error("Cannot write " + m_mesh_path);
    auto emit = [&f](const TriangleMesh &mesh, float obj_code, float type_code) {
        const indexed_triangle_set &its = mesh.its;
        for (const Vec3i32 &tri : its.indices) {
            const Vec3f &a = its.vertices[tri[0]], &b = its.vertices[tri[1]], &c = its.vertices[tri[2]];
            Vec3f n = (b - a).cross(c - a);
            const float len = n.norm();
            n = len > 0.f ? Vec3f(n / len) : Vec3f(0.f, 0.f, 1.f);
            for (const Vec3f *v : {&a, &b, &c}) {
                for (int k = 0; k < 3; ++k) write_raw(f, (*v)[k]);
                for (int k = 0; k < 3; ++k) write_raw(f, n[k]);
                write_raw(f, obj_code);
                write_raw(f, type_code);
            }
        }
    };
    for (size_t oi = 0; oi < m_model->objects.size(); ++oi) {
        const ModelObject *obj = m_model->objects[oi];
        for (size_t ii = 0; ii < obj->instances.size(); ++ii)
            for (const ModelVolume *vol : obj->volumes) {
                TriangleMesh mesh = vol->mesh();
                mesh.transform(obj->instances[ii]->get_matrix() * vol->get_matrix());
                // Parts are drawn in their filament's colour: type 100 + 0-based filament.
                const int type = vol->is_model_part() ? 100 + filament_of(*obj, *vol) : volume_type_code(vol->type());
                emit(mesh, float(oi * INSTANCE_ID_STRIDE + ii), float(type));
            }
    }

    // The prime tower as the desktop shows it before slicing: on a plate that uses more than one
    // filament, a translucent box at the tower position, prime_tower_width square and as tall as
    // the plate's objects (the real depth is only known after slicing). Selection id -2 never
    // matches a selection; type 99 is drawn by the translucent pass.
    DynamicPrintConfig config;
    try {
        if (m_bundle && !m_printer.empty() && m_sel_filaments.size() > 1)
            config = selection_config();
    } catch (const std::exception &) {
    }
    if (config.has("enable_prime_tower") && config.opt_bool("enable_prime_tower") && config.has("prime_tower_width")) {
        const double width = std::max(1., config.opt_float("prime_tower_width"));
        const auto *cfg_x = config.option<ConfigOptionFloats>("wipe_tower_x");
        const auto *cfg_y = config.option<ConfigOptionFloats>("wipe_tower_y");
        for (size_t p = 0; p < m_plates.size(); ++p) {
            std::set<int> used;
            double height = 0.;
            for (const ModelObject *obj : m_model->objects)
                for (size_t i = 0; i < obj->instances.size(); ++i) {
                    if (plate_of(*obj, i) != int(p))
                        continue;
                    height = std::max(height, obj->instance_bounding_box(i).max.z());
                    const DynamicPrintConfig &oc = obj->config.get();
                    const int obj_ext = oc.has("extruder") ? oc.opt_int("extruder") : 0;
                    for (const ModelVolume *vol : obj->volumes) {
                        if (!vol->is_model_part())
                            continue;
                        const DynamicPrintConfig &vc = vol->config.get();
                        const int ext = vc.has("extruder") ? vc.opt_int("extruder") : 0;
                        used.insert(ext > 0 ? ext : obj_ext > 0 ? obj_ext : 1);
                        if (!vol->mmu_segmentation_facets.empty())
                            used.insert(-1); // colour painting: more than one filament
                    }
                }
            if (used.size() < 2 || height <= 0.)
                continue;
            const PlateInfo &plate = m_plates[p];
            auto at = [p](const ConfigOptionFloats *o) { return o && !o->values.empty() ? o->values[std::min(p, o->values.size() - 1)] : 0.; };
            // Kept on the bed, as the slicer keeps the real (usually shallower) tower.
            const std::array<double, 4> bed = bed_rect();
            const double x = std::clamp(plate.has_wipe_tower_pos ? plate.wipe_tower_x : at(cfg_x), bed[0], std::max(bed[0], bed[2] - width));
            const double y = std::clamp(plate.has_wipe_tower_pos ? plate.wipe_tower_y : at(cfg_y), bed[1], std::max(bed[1], bed[3] - width));
            const auto origin = plate_origin(int(p));
            TriangleMesh tower = make_cube(width, width, height);
            tower.translate(float(origin[0] + x), float(origin[1] + y), 0.f);
            emit(tower, -2.f, 99.f);
        }
    }
    f.close();
    if (std::rename(tmp.c_str(), m_mesh_path.c_str()) != 0)
        throw std::runtime_error("Cannot write " + m_mesh_path);
    ++m_mesh_version;
}

json OrcaEngine::scene_json()
{
    const std::array<double, 4> rect = bed_rect();
    json plates = json::array();
    std::vector<int> per_plate(m_plates.size(), 0);
    json objects = json::array();
    for (const ModelObject *obj : m_model->objects) {
        json instances = json::array();
        for (size_t i = 0; i < obj->instances.size(); ++i) {
            const ModelInstance *inst = obj->instances[i];
            const BoundingBoxf3 bb = obj->instance_bounding_box(i);
            const int plate = plate_of(*obj, i);
            if (plate >= 0)
                ++per_plate[plate];
            Vec3d rot = inst->get_rotation() * (180. / M_PI);
            instances.push_back({{"plate", plate},
                                 {"offset", vec_json(inst->get_offset())},
                                 {"rotation", vec_json(rot)},
                                 {"scale", vec_json(inst->get_scaling_factor())},
                                 {"mirror", vec_json(inst->get_transformation().get_mirror())},
                                 {"size", vec_json(bb.size())},
                                 {"min", vec_json(bb.min)}});
        }
        json volumes = json::array();
        size_t triangles = 0;
        for (const ModelVolume *vol : obj->volumes) {
            triangles += vol->mesh().facets_count();
            const Vec3d size = vol->mesh().bounding_box().size().cwiseProduct(vol->get_scaling_factor());
            volumes.push_back({{"name", vol->name},
                               {"type", volume_type_code(vol->type())},
                               {"settings", config_json(vol->config)},
                               {"offset", vec_json(vol->get_offset())},
                               {"size", vec_json(size)}});
        }
        json ranges = json::array();
        for (const auto &[range, cfg] : obj->layer_config_ranges)
            ranges.push_back({{"from", range.first}, {"to", range.second}, {"settings", config_json(cfg)}});
        objects.push_back({{"name", obj->name},
                           {"triangles", triangles},
                           {"layer_ranges", ranges},
                           {"layer_profile", !obj->layer_height_profile.empty()},
                           {"settings", config_json(obj->config)},
                           {"volumes", volumes},
                           {"instances", instances}});
    }
    for (size_t p = 0; p < m_plates.size(); ++p) {
        const auto o = plate_origin(int(p));
        plates.push_back({{"origin", {o[0], o[1]}}, {"bed_type", m_plates[p].bed_type}, {"instances", per_plate[p]}});
    }
    json gcodes = json::array();
    for (size_t p = 0; p < m_plates.size(); ++p) {
        json items = json::array();
        if (auto it = m_model->plates_custom_gcodes.find(int(p)); it != m_model->plates_custom_gcodes.end())
            for (const CustomGCode::Item &item : it->second.gcodes)
                items.push_back({{"z", item.print_z},
                                 {"type", item.type == CustomGCode::ColorChange ? "color" : item.type == CustomGCode::Custom ? "custom" : "pause"},
                                 {"extra", item.extra},
                                 {"color", item.color},
                                 {"extruder", item.extruder}});
        plates[p]["layer_gcodes"] = items;
        if (m_plates[p].has_wipe_tower_pos)
            plates[p]["wipe_tower"] = {m_plates[p].wipe_tower_x, m_plates[p].wipe_tower_y};
    }
    return {{"mesh", m_mesh_path},
            {"paint_mesh", m_paint_path},
            {"paint_version", m_paint_version},
            {"mesh_version", m_mesh_version},
            {"can_undo", !m_undo.empty()},
            {"can_redo", !m_redo.empty()},
            {"calibration_active", m_calib_config != nullptr},
            {"plate_size", {rect[2] - rect[0], rect[3] - rect[1]}},
            {"plates", plates},
            {"objects", objects}};
}

json OrcaEngine::commit()
{
    m_aabb.clear();
    write_mesh();
    write_paint_mesh();
    return scene_json();
}

// --- Public scene API ------------------------------------------------------------------------

json OrcaEngine::scene()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return commit();
}

json OrcaEngine::load_models(const std::vector<std::string> &paths, bool append, int plate)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    push_undo();
    if (!append) {
        m_model->clear_objects();
        m_plates.assign(1, {});
        plate = 0;
    }
    plate = std::clamp(plate, 0, int(m_plates.size()) - 1);

    const std::array<double, 4> rect = bed_rect();
    const auto origin = plate_origin(plate);
    const Vec3d plate_center(origin[0] + (rect[0] + rect[2]) / 2, origin[1] + (rect[1] + rect[3]) / 2, 0.);
    bool plate_was_empty = true;
    for (const ModelObject *obj : m_model->objects)
        for (size_t i = 0; i < obj->instances.size(); ++i)
            if (plate_of(*obj, i) == plate)
                plate_was_empty = false;

    std::vector<std::pair<ModelObject *, size_t>> added;
    bool keep_layout = plate_was_empty && paths.size() == 1;
    for (const std::string &path : paths) {
        DynamicPrintConfig        file_config;
        ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
        Model loaded = Model::read_from_file(path, &file_config, &substitutions, LoadStrategy::LoadModel);
        if (loaded.objects.empty())
            continue;

        std::vector<ModelObject *> file_objects;
        for (const ModelObject *src : loaded.objects) {
            ModelObject *obj = m_model->add_object(*src);
            // Like the desktop Plater: files without placement (STL, OBJ, ...) keep their CAD
            // coordinates, which may be anywhere; center them.
            if (obj->instances.empty()) {
                obj->center_around_origin();
                obj->add_instance();
                keep_layout = false;
            }
            file_objects.push_back(obj);
        }
        // Move the file's objects as a group onto the target plate, keeping their relative layout.
        BoundingBoxf3 group;
        for (ModelObject *obj : file_objects)
            for (size_t i = 0; i < obj->instances.size(); ++i)
                group.merge(obj->instance_bounding_box(i));
        const Vec3d shift(plate_center.x() - group.center().x(), plate_center.y() - group.center().y(), 0.);
        for (ModelObject *obj : file_objects) {
            for (ModelInstance *inst : obj->instances)
                inst->set_offset(inst->get_offset() + shift);
            drop_to_bed(*obj);
            for (size_t i = 0; i < obj->instances.size(); ++i)
                added.emplace_back(obj, i);
        }
    }
    if (added.empty())
        throw std::runtime_error("The file does not contain any printable object");

    // A single placed file (e.g. a 3MF plate) keeps its layout; everything else is arranged
    // around what is already on the plate.
    if (!keep_layout && (added.size() > 1 || !plate_was_empty))
        arrange_plate(plate, &added);
    return commit();
}

json OrcaEngine::set_transform(int object, int instance, const json &transform)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    push_undo();
    ModelInstance *inst = obj.instances[instance];
    Geometry::Transformation t = inst->get_transformation();
    if (transform.contains("offset"))
        t.set_offset(vec_from(transform["offset"]));
    if (transform.contains("rotation"))
        t.set_rotation(vec_from(transform["rotation"]) * (M_PI / 180.));
    if (transform.contains("scale")) {
        const Vec3d s = vec_from(transform["scale"]);
        if (s.minCoeff() <= 0.)
            throw std::runtime_error("Scale must be positive");
        t.set_scaling_factor(s);
    }
    if (transform.contains("mirror"))
        t.set_mirror(vec_from(transform["mirror"]));
    inst->set_transformation(t);
    drop_to_bed(obj);
    return commit();
}

json OrcaEngine::delete_object(int object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    object_at(object);
    push_undo();
    m_model->delete_object(size_t(object));
    return commit();
}

json OrcaEngine::delete_instance(int object, int instance) { return delete_items({{object, instance}}); }

json OrcaEngine::delete_items(const Items &items)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Items sorted = checked_items(items);
    push_undo();
    // Last first, so the indices of the rest stay valid.
    for (auto it = sorted.rbegin(); it != sorted.rend(); ++it) {
        ModelObject &obj = *m_model->objects[it->first];
        if (obj.instances.size() == 1)
            m_model->delete_object(size_t(it->first));
        else
            obj.delete_instance(size_t(it->second));
    }
    return commit();
}

json OrcaEngine::duplicate(const Items &items, int copies)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Items sorted = checked_items(items);
    if (copies < 1 || copies > 100)
        throw std::runtime_error("Invalid number of copies");
    push_undo();
    std::map<int, std::vector<std::pair<ModelObject *, size_t>>> added; // by plate
    for (const auto &[o, i] : sorted) {
        ModelObject &obj = *m_model->objects[o];
        const int plate = std::max(0, plate_of(obj, size_t(i)));
        for (int k = 0; k < copies; ++k) {
            obj.add_instance(*obj.instances[i]);
            added[plate].emplace_back(&obj, obj.instances.size() - 1);
        }
        obj.invalidate_bounding_box();
    }
    for (auto &[plate, list] : added)
        arrange_plate(plate, &list);
    return commit();
}

json OrcaEngine::move_items(const Items &items, double dx, double dy)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Items sorted = checked_items(items);
    push_undo();
    for (const auto &[o, i] : sorted) {
        ModelObject &obj = *m_model->objects[o];
        obj.instances[i]->set_offset(obj.instances[i]->get_offset() + Vec3d(dx, dy, 0.));
        obj.invalidate_bounding_box();
    }
    return commit();
}

json OrcaEngine::arrange(int plate)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    push_undo();
    if (plate >= 0 && plate < int(m_plates.size())) {
        arrange_plate(plate);
    } else {
        // Everything, starting from plate 0; overflow creates further plates.
        std::vector<std::pair<ModelObject *, size_t>> all;
        for (ModelObject *obj : m_model->objects)
            for (size_t i = 0; i < obj->instances.size(); ++i)
                all.emplace_back(obj, i);
        m_plates.assign(1, {});
        m_layout_rect = bed_rect();
        arrange_plate(0, &all);
    }
    return commit();
}

json OrcaEngine::lay_on_face(int object, int instance, const std::array<double, 3> &normal)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    Vec3d n(normal[0], normal[1], normal[2]);
    if (n.norm() < 1e-9)
        throw std::runtime_error("Invalid face");
    push_undo();
    ModelInstance *inst = obj.instances[instance];
    // Rotate the world so that the picked face normal points down (-Z).
    const Eigen::Quaterniond q = Eigen::Quaterniond::FromTwoVectors(n.normalized(), -Vec3d::UnitZ());
    const Matrix3d rotation = q.toRotationMatrix() * Geometry::rotation_transform(inst->get_rotation()).linear();
    Geometry::Transformation t = inst->get_transformation();
    t.set_rotation(Geometry::extract_euler_angles(rotation));
    inst->set_transformation(t);
    drop_to_bed(obj);
    return commit();
}

json OrcaEngine::auto_orient(int object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    push_undo();
    for (size_t oi = 0; oi < m_model->objects.size(); ++oi) {
        if (object >= 0 && int(oi) != object)
            continue;
        ModelObject *obj = m_model->objects[oi];
        for (ModelInstance *inst : obj->instances)
            orientation::orient(inst);
        drop_to_bed(*obj);
    }
    return commit();
}

json OrcaEngine::split(int object, bool to_parts)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    if (to_parts) {
        size_t parts = 0;
        for (size_t v = 0; v < obj.volumes.size(); ++v)
            if (obj.volumes[v]->type() == ModelVolumeType::MODEL_PART)
                parts += obj.volumes[v]->split(1, false);
        if (parts <= 1) {
            m_undo.pop_back();
            throw std::runtime_error("The object consists of a single part and cannot be split");
        }
        obj.invalidate_bounding_box();
    } else {
        ModelObjectPtrs new_objects;
        obj.split(&new_objects, false);
        if (new_objects.size() <= 1) {
            // split() added the single copy to the model; drop it again.
            for (ModelObject *o : new_objects)
                m_model->delete_object(o);
            m_undo.pop_back();
            throw std::runtime_error("The object consists of a single part and cannot be split");
        }
        m_model->delete_object(size_t(object));
        for (ModelObject *o : new_objects)
            drop_to_bed(*o);
    }
    return commit();
}

json OrcaEngine::cut(int object, int instance, double z, bool keep_upper, bool keep_lower, bool flip_upper)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    if (!keep_upper && !keep_lower)
        throw std::runtime_error("Keep at least one part");
    const BoundingBoxf3 bb = obj.instance_bounding_box(size_t(instance));
    if (z <= bb.min.z() || z >= bb.max.z())
        throw std::runtime_error("The cut height must lie within the object");
    push_undo();

    // enum_bitmask's operator|= does not compile (unused upstream), so combine with operator|.
    ModelObjectCutAttributes attributes = flip_upper ? ModelObjectCutAttribute::FlipUpper : ModelObjectCutAttribute::PlaceOnCutUpper;
    if (keep_upper)
        attributes = attributes | ModelObjectCutAttribute::KeepUpper;
    if (keep_lower)
        attributes = attributes | ModelObjectCutAttribute::KeepLower;

    // Like GLGizmoCut::get_cut_matrix: the plane is given relative to the instance offset.
    const Vec3d center = bb.center();
    const Vec3d offset = obj.instances[size_t(instance)]->get_offset();
    Cut cutter(&obj, instance, Geometry::translation_transform(Vec3d(center.x(), center.y(), z) - offset), attributes);
    const ModelObjectPtrs &parts = cutter.perform_with_plane();
    const int plate = std::max(0, plate_of(obj, size_t(instance)));

    std::vector<std::pair<ModelObject *, size_t>> added;
    for (const ModelObject *part : parts) {
        ModelObject *copy = m_model->add_object(*part);
        drop_to_bed(*copy);
        for (size_t i = 0; i < copy->instances.size(); ++i)
            added.emplace_back(copy, i);
    }
    m_model->delete_object(size_t(object));
    arrange_plate(plate, &added);
    return commit();
}

json OrcaEngine::simplify(int object, double ratio)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (ratio <= 0. || ratio >= 1.)
        throw std::runtime_error("The ratio must be between 0 and 1");
    push_undo();
    for (ModelVolume *vol : obj.volumes) {
        if (!vol->is_model_part())
            continue;
        indexed_triangle_set its = vol->mesh().its;
        const uint32_t target = std::max<uint32_t>(4, uint32_t(its.indices.size() * ratio));
        its_quadric_edge_collapse(its, target);
        vol->set_mesh(std::move(its));
        vol->calculate_convex_hull();
        vol->invalidate_convex_hull_2d();
    }
    obj.invalidate_bounding_box();
    drop_to_bed(obj);
    return commit();
}

json OrcaEngine::repair(int object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    int fixed = 0;
    for (ModelVolume *vol : obj.volumes) {
        indexed_triangle_set its = vol->mesh().its;
        fixed += its_merge_vertices(its);
        fixed += its_remove_degenerate_faces(its);
        fixed += its_compactify_vertices(its);
        vol->set_mesh(std::move(its));
        vol->calculate_convex_hull();
        vol->invalidate_convex_hull_2d();
    }
    obj.invalidate_bounding_box();
    json out = commit();
    out["repaired"] = fixed;
    return out;
}

json OrcaEngine::add_volume(int object, int type, const std::string &shape, const std::array<double, 3> &size,
                            const std::array<double, 3> &position)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    const ModelVolumeType vtype = volume_type_from_code(type);
    const Vec3d s(std::max(0.1, size[0]), std::max(0.1, size[1]), std::max(0.1, size[2]));
    push_undo();

    TriangleMesh mesh;
    if (shape == "cylinder")
        mesh = make_cylinder(s.x() / 2., s.z());
    else if (shape == "sphere")
        mesh = TriangleMesh(its_make_sphere(s.x() / 2., 2. * M_PI / 90.));
    else
        mesh = make_cube(s.x(), s.y(), s.z());

    ModelVolume *vol = obj.add_volume(std::move(mesh), vtype); // centres the geometry
    Vec3d pos(position[0], position[1], position[2]);
    if (!std::isfinite(pos.x())) {
        // Default: middle of the object, in object coordinates.
        pos = obj.raw_mesh_bounding_box().center();
    }
    vol->set_offset(pos);
    switch (vtype) {
    case ModelVolumeType::SUPPORT_BLOCKER: vol->name = "Support blocker"; break;
    case ModelVolumeType::SUPPORT_ENFORCER: vol->name = "Support enforcer"; break;
    case ModelVolumeType::NEGATIVE_VOLUME: vol->name = "Negative volume"; break;
    default: vol->name = "Modifier"; break;
    }
    obj.invalidate_bounding_box();
    return commit();
}

json OrcaEngine::delete_volume(int object, int volume)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (volume < 0 || volume >= int(obj.volumes.size()))
        throw std::runtime_error("Invalid volume");
    size_t parts = 0;
    for (const ModelVolume *v : obj.volumes)
        parts += v->is_model_part();
    if (obj.volumes[volume]->is_model_part() && parts <= 1)
        throw std::runtime_error("The last part of an object cannot be deleted");
    push_undo();
    obj.delete_volume(size_t(volume));
    obj.invalidate_bounding_box();
    drop_to_bed(obj);
    return commit();
}

json OrcaEngine::set_object_setting(const std::vector<int> &objects, int volume, const std::string &key, const json &value)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (objects.empty() || (objects.size() > 1 && volume >= 0))
        throw std::runtime_error("Invalid object");
    for (int object : objects)
        if (volume >= int(object_at(object).volumes.size()))
            throw std::runtime_error("Invalid volume");
    if (print_config_def.get(key) == nullptr)
        throw std::runtime_error("Unknown setting: " + key);
    const std::string str = value.is_string() ? value.get<std::string>() : value.is_boolean() ? (value.get<bool>() ? "1" : "0") : value.dump();
    if (!value.is_null()) {
        // Checked once up front, so a bad value changes none of the objects.
        DynamicPrintConfig probe;
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
        try {
            probe.set_deserialize(key, str, ctx);
        } catch (const std::exception &ex) {
            throw std::runtime_error("Invalid value for " + key + ": " + ex.what());
        }
    }
    push_undo();
    for (int object : objects) {
        ModelObject &obj = *m_model->objects[object];
        ModelConfig &config = volume < 0 ? static_cast<ModelConfig &>(obj.config) : obj.volumes[volume]->config;
        if (value.is_null()) {
            config.erase(key);
        } else {
            ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
            config.set_deserialize(key, str, ctx);
        }
    }
    return commit();
}

json OrcaEngine::add_plate()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    require_printer();
    if (m_plates.size() >= MAX_PLATES)
        throw std::runtime_error("Maximum number of plates reached");
    push_undo();
    const size_t old_count = m_plates.size();
    m_plates.push_back({});
    relayout(old_count, m_layout_rect);
    return commit();
}

json OrcaEngine::delete_plate(int plate)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (plate < 0 || plate >= int(m_plates.size()) || m_plates.size() == 1)
        throw std::runtime_error("This plate cannot be deleted");
    push_undo();
    // Drop the plate's instances, then close the gap in the plate grid.
    for (int oi = int(m_model->objects.size()) - 1; oi >= 0; --oi) {
        ModelObject *obj = m_model->objects[oi];
        for (int i = int(obj->instances.size()) - 1; i >= 0; --i)
            if (plate_of(*obj, size_t(i)) == plate)
                obj->delete_instance(size_t(i));
        if (obj->instances.empty())
            m_model->delete_object(size_t(oi));
    }
    const size_t old_count = m_plates.size();
    m_plates.erase(m_plates.begin() + plate);
    relayout(old_count, m_layout_rect, [plate](int p) { return p > plate ? p - 1 : p; });
    return commit();
}

json OrcaEngine::set_plate_bed_type(int plate, const std::string &bed_type)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (plate < 0 || plate >= int(m_plates.size()))
        throw std::runtime_error("Invalid plate");
    push_undo();
    m_plates[plate].bed_type = bed_type;
    return commit();
}

json OrcaEngine::undo()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_undo.empty())
        throw std::runtime_error("Nothing to undo");
    m_redo.push_back({std::make_shared<Model>(*m_model), m_plates});
    m_model  = std::make_unique<Model>(*m_undo.back().model);
    m_plates = m_undo.back().plates;
    m_undo.pop_back();
    // Back before the calibration started: its test settings no longer apply (the desktop starts
    // a calibration as a new project, so it has no undo across it).
    if (m_calib_config && m_undo.size() < m_calib_undo_depth) {
        m_calib.reset();
        m_calib_config.reset();
        m_calib_name.clear();
        m_calib_undo_depth = 0;
    }
    return commit();
}

json OrcaEngine::redo()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_redo.empty())
        throw std::runtime_error("Nothing to redo");
    m_undo.push_back({std::make_shared<Model>(*m_model), m_plates});
    m_model  = std::make_unique<Model>(*m_redo.back().model);
    m_plates = m_redo.back().plates;
    m_redo.pop_back();
    return commit();
}

} // namespace orca
