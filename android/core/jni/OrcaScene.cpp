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
#include <map>
#include <set>
#include <stdexcept>

#include <boost/log/trivial.hpp>

#include "libslic3r/CustomGCode.hpp"
#include "libslic3r/GCode/WipeTowerEstimate.hpp"
#include "libslic3r/CutUtils.hpp"
#include "libslic3r/Geometry.hpp"
#include "libslic3r/MeshBoolean.hpp"
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
    case 0: return ModelVolumeType::MODEL_PART;
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
    trim_undo();
    m_redo.clear();
}

void OrcaEngine::trim_undo()
{
    while (m_undo.size() > m_undo_limit) {
        m_undo.pop_front();
        if (m_calib_undo_depth > 0)
            --m_calib_undo_depth;
    }
}

json OrcaEngine::rollback_on_error(const std::function<json()> &edit)
{
    const Model *top = m_undo.empty() ? nullptr : m_undo.back().model.get();
    auto redo = m_redo;
    auto calib = m_calib;
    auto calib_config = m_calib_config;
    auto calib_name = m_calib_name;
    const size_t calib_depth = m_calib_undo_depth;
    try {
        return edit();
    } catch (...) {
        // The edit's own snapshot is the scene from before it.
        if (!m_undo.empty() && m_undo.back().model.get() != top) {
            m_model  = std::make_unique<Model>(*m_undo.back().model);
            m_plates = m_undo.back().plates;
            m_undo.pop_back();
        }
        m_redo             = std::move(redo);
        m_calib            = std::move(calib);
        m_calib_config     = std::move(calib_config);
        m_calib_name       = std::move(calib_name);
        m_calib_undo_depth = calib_depth;
        throw;
    }
}

json OrcaEngine::set_undo_limit(size_t limit)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    // Snapshots share the meshes (ModelVolume keeps them in a shared_ptr), so a deep history is cheap.
    m_undo_limit = std::max<size_t>(limit, 1);
    trim_undo();
    return scene_json();
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

// Free space kept between the prime tower (with brim) and any object, mm.
static constexpr double TOWER_CLEARANCE = 3.;

std::optional<OrcaEngine::TowerBox> OrcaEngine::tower_box(const DynamicPrintConfig &config, int plate) const
{
    if (!config.has("enable_prime_tower") || !config.opt_bool("enable_prime_tower") || !config.has("prime_tower_width"))
        return std::nullopt;
    std::set<int> used;
    double height = 0.;
    const std::array<double, 2> origin = plate_origin(plate);
    std::vector<BoundingBoxf3> footprints; // plate-local, to keep the tower clear of the objects
    for (const ModelObject *obj : m_model->objects)
        for (size_t i = 0; i < obj->instances.size(); ++i) {
            if (plate_of(*obj, i) != plate)
                continue;
            const BoundingBoxf3 ibb = obj->instance_bounding_box(i);
            footprints.push_back(ibb);
            height = std::max(height, ibb.max.z());
            const DynamicPrintConfig &oc = obj->config.get();
            const int obj_ext = oc.has("extruder") ? oc.opt_int("extruder") : 0;
            for (const ModelVolume *vol : obj->volumes) {
                if (!vol->is_model_part())
                    continue;
                const DynamicPrintConfig &vc = vol->config.get();
                const int ext = vc.has("extruder") ? vc.opt_int("extruder") : 0;
                used.insert(ext > 0 ? ext : obj_ext > 0 ? obj_ext : 1);
                // Each painted colour is a filament of its own: a rib tower grows in both axes with the count, so
                // counting them as one placed it too close to the edge ("partially outside", user 2026-10-19).
                for (size_t e : vol->get_extruders_from_multi_material_painting())
                    used.insert(int(e) + 1);
            }
        }
    if (used.size() < 2 || height <= 0.)
        return std::nullopt;
    std::vector<unsigned int> ids;
    for (int e : used)
        ids.push_back(unsigned(e - 1));
    const double layer_height = config.has("layer_height") ? config.opt_float("layer_height") : 0.2;
    const WipeTowerFootprint fp = estimate_wipe_tower_footprint(config, resolve_wipe_tower_type(config), ids, layer_height, height);
    if (fp.depth <= 0.)
        return std::nullopt;
    // The brim and a cone base stick out of the body box; the real footprint must lie on the bed (PartPlate.cpp
    // estimate_wipe_tower_polygon). Without the depth a tower near the far edge failed with "partially outside".
    const BoundingBox outline = get_extents(estimate_wipe_tower_first_layer_outline(config, resolve_wipe_tower_type(config), fp.width, fp.depth, height));
    const double bulge = std::max({0., unscaled(outline.max.x()) - fp.width, unscaled(outline.max.y()) - fp.depth, -unscaled(outline.min.x()), -unscaled(outline.min.y())});
    const double margin = WIPE_TOWER_MARGIN + fp.brim_width + bulge;
    const PlateInfo &pi = m_plates[plate];
    auto at = [plate](const char *key, const DynamicPrintConfig &c) {
        const auto *o = c.option<ConfigOptionFloats>(key);
        return o && !o->values.empty() ? o->values[std::min<size_t>(plate, o->values.size() - 1)] : 0.;
    };
    const std::array<double, 4> bed = bed_rect();
    auto fit = [&](double v, double lo, double hi, double size) {
        const double min = lo + margin;
        return std::clamp(v, min, std::max(min, hi - size - margin));
    };
    const double x0 = fit(pi.has_wipe_tower_pos ? pi.wipe_tower_x : at("wipe_tower_x", config), bed[0], bed[2], fp.width);
    const double y0 = fit(pi.has_wipe_tower_pos ? pi.wipe_tower_y : at("wipe_tower_y", config), bed[1], bed[3], fp.depth);
    // The tower never overlaps an object (user 2026-10-15): the box plus brim, cone and a clearance must stay off every
    // object's footprint. A position that clashes slides to the nearest free spot; with none, `clash` blocks slicing.
    const double pad = fp.brim_width + bulge + TOWER_CLEARANCE;
    auto clashes = [&](double x, double y) {
        for (const BoundingBoxf3 &b : footprints)
            if (x - pad < b.max.x() - origin[0] && x + fp.width + pad > b.min.x() - origin[0] &&
                y - pad < b.max.y() - origin[1] && y + fp.depth + pad > b.min.y() - origin[1])
                return true;
        return false;
    };
    if (!clashes(x0, y0))
        return TowerBox{x0, y0, fp.width, fp.depth, height};
    const double lo_x = bed[0] + margin, hi_x = std::max(lo_x, bed[2] - fp.width - margin);
    const double lo_y = bed[1] + margin, hi_y = std::max(lo_y, bed[3] - fp.depth - margin);
    double best = 1e30, bx = x0, by = y0;
    for (double y = lo_y; y <= hi_y + 1e-6; y += 2.)
        for (double x = lo_x; x <= hi_x + 1e-6; x += 2.) {
            const double d = (x - x0) * (x - x0) + (y - y0) * (y - y0);
            if (d < best && !clashes(x, y)) {
                best = d;
                bx = x;
                by = y;
            }
        }
    TowerBox box{bx, by, fp.width, fp.depth, height};
    box.clash = best >= 1e30;
    return box;
}

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
    // libnest2d's final centring shifts the new items by the whole pile's centre, fixed objects
    // included (nfpplacer.hpp finalAlign), which pushed imports and copies off a filled plate.
    // The placer's own spots already avoid the fixed objects, like FillBedJob's.
    params.do_final_align = fixed.empty();
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

// The object's parts in its own frame (the design pose before it is set on the bed).
static BoundingBoxf3 design_box(const ModelObject &obj)
{
    BoundingBoxf3 box;
    for (const ModelVolume *vol : obj.volumes)
        if (vol->is_model_part())
            box.merge(vol->mesh().transformed_bounding_box(vol->get_matrix()));
    return box;
}

// The copy's matrix as drawn and picked: [design] = OrcaEngine::design_offset() (the Build workspace's object).
static Transform3d shown_matrix(const ModelInstance &inst, const std::optional<std::array<double, 3>> &design)
{
    return design ? Transform3d(Eigen::Translation3d((*design)[0], (*design)[1], (*design)[2])) : inst.get_matrix();
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
            for (size_t vi = 0; vi < obj->volumes.size(); ++vi) {
                const ModelVolume *vol = obj->volumes[vi];
                TriangleMesh mesh = vol->mesh();
                // Mirrored copies keep their triangles facing out (the section view draws back faces as cut faces).
                mesh.transform(shown_matrix(*obj->instances[ii], design_offset(oi, ii)) * vol->get_matrix(), true);
                // Parts are drawn in their filament's colour: type 100 + 0-based filament.
                const int type = vol->is_model_part() ? 100 + filament_of(*obj, *vol) : volume_type_code(vol->type());
                // The Build workspace's object: one id per volume (its copies share them), see set_build_object().
                const float id = int(oi) == m_build_object ? -1000.f - float(vi) : float(oi * INSTANCE_ID_STRIDE + ii);
                emit(mesh, id, float(type));
            }
    }

    // The prime tower as the desktop shows it before slicing: on a plate that uses more than one
    // filament, a translucent box at the tower position, prime_tower_width square and as tall as
    // the plate's objects (the real depth is only known after slicing). Selection id -2 - plate
    // never matches a selection (the renderer highlights and drags one tower by it); type 99 is drawn by the translucent pass.
    DynamicPrintConfig config;
    try {
        if (m_bundle && !m_printer.empty() && m_sel_filaments.size() > 1)
            config = selection_config();
    } catch (const std::exception &) {
    }
    m_tower_boxes.assign(m_plates.size(), std::nullopt);
    for (size_t p = 0; p < m_plates.size(); ++p) {
        m_tower_boxes[p] = tower_box(config, int(p));
        if (!m_tower_boxes[p])
            continue;
        const TowerBox &t = *m_tower_boxes[p];
        const auto origin = plate_origin(int(p));
        TriangleMesh tower = make_cube(t.width, t.depth, t.height);
        tower.translate(float(origin[0] + t.x), float(origin[1] + t.y), 0.f);
        emit(tower, -2.f - float(p), 99.f);
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
            if (m_build_object >= 0 && size_t(m_build_object) < m_model->objects.size() && obj == m_model->objects[m_build_object]) {
                const Transform3d   shown = shown_matrix(*inst, design_offset(size_t(m_build_object), i));
                const BoundingBoxf3 parts = design_box(*obj);
                const BoundingBoxf3 box   = parts.defined ? parts.transformed(shown) : bb;
                instances.back()["design"] = {{"offset", vec_json(Vec3d(shown.translation()))},
                                              {"min", vec_json(box.min)},
                                              {"size", vec_json(box.size())}};
            }
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
                               {"rotation", vec_json(vol->get_rotation() * (180. / M_PI))},
                               {"scale", vec_json(vol->get_scaling_factor())},
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
        if (!obj->module_name.empty())
            objects.back()["module"] = obj->module_name;
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
        if (m_plates[p].has_wipe_tower_pos) {
            // Show the position the tower really takes (on the bed), not a raw value dragged past the edge.
            const bool boxed = p < m_tower_boxes.size() && m_tower_boxes[p];
            plates[p]["wipe_tower"] = {boxed ? m_tower_boxes[p]->x : m_plates[p].wipe_tower_x,
                                       boxed ? m_tower_boxes[p]->y : m_plates[p].wipe_tower_y};
        }
        if (p < m_tower_boxes.size() && m_tower_boxes[p])
            plates[p]["tower"] = {m_tower_boxes[p]->x, m_tower_boxes[p]->y, m_tower_boxes[p]->width, m_tower_boxes[p]->depth, m_tower_boxes[p]->height};
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
    m_neighbors.clear();
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
    // A file without printable objects (or one that fails to read) must not leave the plate wiped.
    return rollback_on_error([&] { return load_models_locked(paths, append, plate); });
}

json OrcaEngine::load_models_locked(const std::vector<std::string> &paths, bool append, int plate)
{
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
    // A placed layout wider than one bed (e.g. a multi-plate 3MF imported as models) would leave
    // objects beside the plates; arrange it instead, which spills the rest onto new plates.
    if (keep_layout)
        for (const auto &[obj, i] : added)
            if (plate_at(obj->instance_bounding_box(i).center(), m_plates.size(), rect) < 0)
                keep_layout = false;

    // A single placed file (e.g. a 3MF plate) keeps its layout; everything else is arranged
    // around what is already on the plate.
    if (!keep_layout && (added.size() > 1 || !plate_was_empty))
        arrange_plate(plate, &added);
    return commit();
}

// With volume >= 0 the transform is the part's own (in object coordinates), as the desktop's
// part manipulation panel in local coordinates.
json OrcaEngine::set_transform(int object, int instance, const json &transform, int volume)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    if (volume >= int(obj.volumes.size()))
        throw std::runtime_error("Invalid volume");
    push_undo();
    ModelInstance *inst = obj.instances[instance];
    ModelVolume *vol = volume >= 0 ? obj.volumes[volume] : nullptr;
    Geometry::Transformation t = vol ? vol->get_transformation() : inst->get_transformation();
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
    if (vol) {
        vol->set_transformation(t);
        obj.invalidate_bounding_box();
    } else
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
        // Objects off every plate (beside the grid after an import, or left by a deleted plate) go
        // onto the plate nearest to them (user, 2026-10-13): into free spots on other plates, whose
        // objects stay put; the arranged plate takes its own floaters along with its objects.
        const std::array<double, 4> rect = bed_rect();
        std::vector<std::vector<std::pair<ModelObject *, size_t>>> floaters(m_plates.size());
        for (ModelObject *obj : m_model->objects)
            for (size_t i = 0; i < obj->instances.size(); ++i) {
                const Vec3d c = obj->instance_bounding_box(i).center();
                if (plate_at(c, m_plates.size(), rect) >= 0)
                    continue;
                size_t best = 0;
                double best_dist = 0.;
                for (size_t p = 0; p < m_plates.size(); ++p) {
                    const auto o = origin_of(int(p), m_plates.size(), rect);
                    // Distance from the object's centre to the plate's rectangle.
                    const double dx = std::max({o[0] + rect[0] - c.x(), 0., c.x() - o[0] - rect[2]});
                    const double dy = std::max({o[1] + rect[1] - c.y(), 0., c.y() - o[1] - rect[3]});
                    if (p == 0 || dx * dx + dy * dy < best_dist) {
                        best      = p;
                        best_dist = dx * dx + dy * dy;
                    }
                }
                floaters[best].emplace_back(obj, i);
            }
        std::vector<std::pair<ModelObject *, size_t>> own = floaters[plate];
        for (ModelObject *obj : m_model->objects)
            for (size_t i = 0; i < obj->instances.size(); ++i)
                if (plate_of(*obj, i) == plate)
                    own.emplace_back(obj, i);
        // Plates only get added at the end, so the indices stay valid across the calls.
        for (size_t p = 0; p < floaters.size(); ++p)
            if (int(p) != plate && !floaters[p].empty())
                arrange_plate(int(p), &floaters[p]);
        arrange_plate(plate, &own);
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

// Like ObjectList::merge(true) ("Assemble"): the given instances become the parts of one new object,
// each keeping its place on the bed; their objects lose those instances (or go when none is left).
json OrcaEngine::assemble(const Items &items)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Items sorted = checked_items(items);
    if (sorted.size() < 2)
        throw std::runtime_error("Select at least two objects to assemble");
    push_undo();

    ModelObject *assembly = m_model->add_object();
    assembly->name = "Assembly";
    assembly->add_instance();
    for (const auto &[o, i] : sorted) {
        const ModelObject &object = *m_model->objects[o];
        const ModelInstance &inst = *object.instances[i];
        const Transform3d &trafo = inst.get_transformation().get_matrix();
        for (const ModelVolume *volume : object.volumes) {
            ModelVolume *v = assembly->add_volume(*volume);
            v->set_transformation(trafo * v->get_matrix());
            // Object settings can't go to a part (print object options), but the filament can.
            if (object.volumes.size() > 1)
                v->config.assign_config(volume->config);
            if (!v->config.option("extruder"))
                if (const ConfigOption *opt = object.config.option("extruder"))
                    v->config.set_key_value("extruder", new ConfigOptionInt(opt->getInt()));
        }
        for (const std::string &key : object.config.keys())
            if (!assembly->config.has(key))
                assembly->config.set_key_value(key, object.config.option(key)->clone());
        if (!inst.printable)
            assembly->printable = assembly->instances[0]->printable = false;
        for (const auto &range : object.layer_config_ranges)
            assembly->layer_config_ranges.emplace(range);
    }
    assembly->sort_volumes(true);
    assembly->ensure_on_bed();
    assembly->center_around_origin();
    assembly->translate_instances(-assembly->origin_translation);
    assembly->origin_translation = Vec3d::Zero();
    assembly->instances[0]->set_assemble_transformation(assembly->instances[0]->get_transformation());
    assembly->invalidate_bounding_box();

    for (auto it = sorted.rbegin(); it != sorted.rend(); ++it) {
        ModelObject &obj = *m_model->objects[it->first];
        if (obj.instances.size() == 1)
            m_model->delete_object(size_t(it->first));
        else
            obj.delete_instance(size_t(it->second));
    }
    return commit();
}

// GLGizmoMeshBoolean's Union / Difference / Intersection of two parts of one object (mcut). The
// result replaces part `src`; `tool` goes too unless kept (Union always removes it).
json OrcaEngine::mesh_boolean(int object, int src, int tool, const std::string &op, bool keep_tool)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    const int n = int(obj.volumes.size());
    if (src < 0 || src >= n || tool < 0 || tool >= n || src == tool)
        throw std::runtime_error("Pick two different parts");
    if (!obj.volumes[src]->is_model_part() || !obj.volumes[tool]->is_model_part())
        throw std::runtime_error("Booleans work on parts, not on modifiers");
    static const std::map<std::string, std::string> ops{{"union", "UNION"}, {"difference", "A_NOT_B"}, {"intersection", "INTERSECTION"}};
    const auto mcut_op = ops.find(op);
    if (mcut_op == ops.end())
        throw std::runtime_error("Unknown boolean operation");

    const ModelVolume *src_vol = obj.volumes[src];
    const ModelVolume *tool_vol = obj.volumes[tool];
    TriangleMesh src_mesh = src_vol->mesh();
    src_mesh.transform(src_vol->get_matrix());
    TriangleMesh tool_mesh = tool_vol->mesh();
    tool_mesh.transform(tool_vol->get_matrix());
    std::vector<TriangleMesh> results;
    MeshBoolean::mcut::make_boolean(src_mesh, tool_mesh, results, mcut_op->second);
    if (results.empty() || results.front().empty())
        throw std::runtime_error("The boolean operation failed or left nothing. The parts may not overlap, or a mesh is not closed; try Repair first.");

    // VolumeInfo::save_painting: painting moved into object coordinates like the meshes.
    auto painting = [](const ModelVolume *v) {
        auto saved = v->save_painting();
        if (saved)
            saved->mesh.transform(v->get_matrix());
        return saved;
    };
    std::vector<std::optional<TriangleSelector::SavedPainting>> paintings{painting(src_vol)};
    if (op != "difference")
        paintings.push_back(painting(tool_vol));

    push_undo();
    const bool sinking = obj.min_z() < SINKING_Z_THRESHOLD;
    // add_volume centres the mesh and sets the offset to match, so the result stays in place.
    // (The gizmo then sets the old part's offset, which shifts a union with a part off to one side.)
    ModelVolume *result = obj.add_volume(std::move(results.front()));
    for (const auto &saved : paintings)
        result->restore_painting(saved, true);
    result->name = src_vol->name + " - " + op;
    result->set_new_unique_id();
    result->config.apply(src_vol->config);
    result->set_type(src_vol->type());
    result->set_material_id(src_vol->material_id());

    // Delete the larger index first so the other stays valid.
    std::vector<int> gone{src};
    if (op == "union" || !keep_tool)
        gone.push_back(tool);
    std::sort(gone.rbegin(), gone.rend());
    for (int v : gone)
        obj.delete_volume(size_t(v));
    obj.invalidate_bounding_box();
    if (!sinking)
        drop_to_bed(obj);
    return commit();
}

json OrcaEngine::cut(int object, int instance, double z, bool keep_upper, bool keep_lower, bool flip_upper, bool float_upper, double rot_x, double rot_y, double rot_z)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    if (!keep_upper && !keep_lower)
        throw std::runtime_error("Keep at least one part");
    const BoundingBoxf3 bb = obj.instance_bounding_box(size_t(instance));
    // The plane goes through the box's middle (x, y) at height z, turned by rot_deg (Euler, as an instance rotation).
    const Vec3d center = bb.center();
    const Vec3d plane_point(center.x(), center.y(), z);
    const Transform3d plane_rotation = Geometry::rotation_transform(Vec3d(Geometry::deg2rad(rot_x), Geometry::deg2rad(rot_y), Geometry::deg2rad(rot_z)));
    const Vec3d plane_normal = plane_rotation.linear() * Vec3d::UnitZ();
    bool above = false, below = false;
    for (int i = 0; i < 8; ++i) {
        const Vec3d corner((i & 1) ? bb.max.x() : bb.min.x(), (i & 2) ? bb.max.y() : bb.min.y(), (i & 4) ? bb.max.z() : bb.min.z());
        const double side = plane_normal.dot(corner - plane_point);
        above = above || side > 1e-4;
        below = below || side < -1e-4;
    }
    if (!above || !below)
        throw std::runtime_error("The cut plane must pass through the object");
    push_undo();

    // enum_bitmask's operator|= does not compile (unused upstream), so combine with operator|.
    // KeepPaint: without it the cut discards colour / support / seam painting (desktop app_config keep_painting, default on).
    ModelObjectCutAttributes attributes = (flip_upper ? ModelObjectCutAttribute::FlipUpper : ModelObjectCutAttribute::PlaceOnCutUpper) | ModelObjectCutAttribute::KeepPaint;
    if (keep_upper)
        attributes = attributes | ModelObjectCutAttribute::KeepUpper;
    if (keep_lower)
        attributes = attributes | ModelObjectCutAttribute::KeepLower;

    // Like GLGizmoCut::get_cut_matrix: the plane is given relative to the instance offset.
    const Vec3d offset = obj.instances[size_t(instance)]->get_offset();
    Cut cutter(&obj, instance, Geometry::translation_transform(plane_point - offset) * plane_rotation, attributes);
    const ModelObjectPtrs &parts = cutter.perform_with_plane();
    const int plate = std::max(0, plate_of(obj, size_t(instance)));

    std::vector<std::pair<ModelObject *, size_t>> added;
    // The upper part comes first in the result (Cut::post_process).
    bool first = true;
    for (const ModelObject *part : parts) {
        ModelObject *copy = m_model->add_object(*part);
        // Like the desktop's per-object auto drop: ensure_on_bed() leaves instances without it where they are.
        if (first && keep_upper && float_upper)
            for (ModelInstance *inst : copy->instances)
                inst->auto_drop = false;
        first = false;
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

// Like Plater::priv::replace_volume_with_stl with "keep_painting" on.
json OrcaEngine::replace_mesh(int object, const std::string &path, const std::string &module)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    const auto old_it = std::find_if(obj.volumes.begin(), obj.volumes.end(), [](const ModelVolume *v) { return v->is_model_part(); });
    if (old_it == obj.volumes.end())
        throw std::runtime_error("The object has no part to replace");

    Model loaded = Model::read_from_file(path, nullptr, nullptr, LoadStrategy::AddDefaultInstances | LoadStrategy::LoadModel);
    if (loaded.objects.size() != 1 || loaded.objects.front()->volumes.size() != 1)
        throw std::runtime_error("Unable to replace with more than one volume");
    loaded.objects.front()->center_around_origin();

    push_undo();
    const size_t old_idx = size_t(old_it - obj.volumes.begin());
    ModelVolume *old_volume = obj.volumes[old_idx];
    const bool sinking = obj.min_z() < SINKING_Z_THRESHOLD;
    ModelVolume *new_volume = obj.add_volume(*loaded.objects.front()->volumes.front());
    new_volume->set_new_unique_id();
    new_volume->name = old_volume->name;
    new_volume->config.apply(old_volume->config);
    new_volume->set_type(old_volume->type());
    new_volume->set_material_id(old_volume->material_id());
    new_volume->set_transformation(old_volume->get_transformation());
    new_volume->translate(new_volume->get_transformation().get_matrix_no_offset() * (new_volume->source.mesh_offset - old_volume->source.mesh_offset));
    if (auto saved = old_volume->save_painting()) {
        saved->mesh.transform(Geometry::translation_transform(new_volume->mesh().get_init_shift()));
        new_volume->restore_painting(saved);
    }
    std::swap(obj.volumes[old_idx], obj.volumes.back());
    obj.delete_volume(obj.volumes.size() - 1);
    if (!module.empty())
        obj.module_name = module;
    obj.invalidate_bounding_box();
    if (!sinking)
        drop_to_bed(obj);
    return commit();
}

json OrcaEngine::set_volumes(int object, const std::vector<VolumeSpec> &specs, bool keep_center)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    // Everything is loaded and checked before the object changes, so a bad entry leaves it as it was.
    std::map<std::string, TriangleMesh> loaded;
    std::vector<const VolumeSpec *> used;
    bool has_part = false;
    for (const VolumeSpec &s : specs) {
        const ModelVolumeType vtype = volume_type_from_code(s.type);
        if (s.keep >= int(obj.volumes.size()) || s.anchor >= int(obj.volumes.size()))
            throw std::runtime_error("Invalid volume");
        if (s.keep < 0 && !loaded.count(s.stl)) {
            TriangleMesh mesh;
            // OpenSCAD writes no file (or an empty one) for an empty shape, e.g. blank text.
            if (std::ifstream probe(s.stl, std::ios::binary | std::ios::ate); probe && probe.tellg() > 0) {
                Model m = Model::read_from_file(s.stl, nullptr, nullptr, LoadStrategy::LoadModel);
                // The loader centres the mesh into the volume offset: bake it back into the piece's own frame.
                for (const ModelObject *o : m.objects)
                    for (const ModelVolume *v : o->volumes) {
                        TriangleMesh part = v->mesh();
                        part.transform(v->get_matrix());
                        mesh.merge(part);
                    }
            }
            loaded.emplace(s.stl, std::move(mesh));
        }
        if ((s.keep >= 0 ? obj.volumes[s.keep]->mesh() : loaded.at(s.stl)).empty())
            continue; // e.g. blank text
        used.push_back(&s);
        has_part |= vtype == ModelVolumeType::MODEL_PART;
    }
    if (!has_part)
        throw std::runtime_error("The design has no solid shape");

    push_undo();
    const bool   sinking    = obj.min_z() < SINKING_Z_THRESHOLD;
    // Box of some volumes as placed by the first copy.
    auto placed_box = [&obj](const std::vector<const ModelVolume *> &vols) {
        BoundingBoxf3 box;
        for (const ModelVolume *v : vols)
            box.merge(v->mesh().transformed_bounding_box(obj.instances.front()->get_matrix() * v->get_matrix()));
        return box;
    };
    // Unchanged pieces pin the design in place (the old volumes' frame may differ, e.g. after a 3MF reload).
    std::vector<const ModelVolume *> old_anchors, new_anchors;
    for (const VolumeSpec *s : used)
        if (s->anchor >= 0)
            old_anchors.push_back(obj.volumes[s->anchor]);
    const Vec3d  old_center = old_anchors.empty() ? obj.instance_bounding_box(0).center() : placed_box(old_anchors).center();
    const auto   first_part = std::find_if(obj.volumes.begin(), obj.volumes.end(), [](const ModelVolume *v) { return v->is_model_part(); });
    // New pieces take the first part's settings (filament etc.), as replace_mesh does.
    const DynamicPrintConfig part_config = first_part != obj.volumes.end() ? (*first_part)->config.get() : DynamicPrintConfig();
    const size_t old_count = obj.volumes.size();
    std::map<std::string, const ModelVolume *> shared; // repeats of one STL share its mesh
    for (const VolumeSpec *s : used) {
        const ModelVolumeType vtype = volume_type_from_code(s->type);
        ModelVolume *vol;
        if (s->keep >= 0) {
            vol = obj.add_volume(*obj.volumes[s->keep], vtype);
        } else if (auto it = shared.find(s->stl); it != shared.end()) {
            vol = obj.add_volume_with_shared_mesh(*it->second, vtype);
            // It comes without convex hull (null, get_convex_hull() would crash): as the 3MF loader does (bbs_3mf.cpp:5240).
            vol->calculate_convex_hull();
            vol->config.apply(part_config);
        } else {
            // Not centred: the piece's own frame stays the mesh frame, so `matrix` applies as given.
            vol = obj.add_volume(TriangleMesh(loaded.at(s->stl)), vtype, false);
            vol->config.apply(part_config);
            shared.emplace(s->stl, vol);
        }
        vol->set_new_unique_id();
        if (!s->own_matrix) {
            Transform3d m = Transform3d::Identity();
            for (int r = 0; r < 4; ++r)
                for (int c = 0; c < 4; ++c)
                    m(r, c) = s->matrix[size_t(r * 4 + c)];
            vol->set_transformation(Geometry::Transformation(m));
        }
        if (!s->name.empty())
            vol->name = s->name;
        // The shape's own filament (Build workspace), or none: the part then prints with the object's.
        if (s->extruder > 0)
            vol->config.set_key_value("extruder", new ConfigOptionInt(s->extruder));
        else if (s->extruder == 0)
            vol->config.erase("extruder");
        if (s->anchor >= 0)
            new_anchors.push_back(vol);
    }
    // Not ModelObject::delete_volume: with one volume left it folds the volume matrix into the instances, and
    // the volume matrices must stay the design pose (the instance is the print pose).
    for (size_t i = 0; i < old_count; ++i)
        delete obj.volumes[i];
    obj.volumes.erase(obj.volumes.begin(), obj.volumes.begin() + ptrdiff_t(old_count));
    obj.invalidate_bounding_box();
    if (keep_center) {
        const Vec3d shift = old_center - (new_anchors.empty() ? obj.instance_bounding_box(0).center() : placed_box(new_anchors).center());
        for (ModelInstance *inst : obj.instances)
            inst->set_offset(inst->get_offset() + Vec3d(shift.x(), shift.y(), 0.));
        obj.invalidate_bounding_box();
    }
    if (!sinking)
        drop_to_bed(obj);
    return commit();
}

json OrcaEngine::set_build_object(int object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (object >= 0)
        object_at(object);
    m_build_object = object;
    return commit();
}

std::optional<std::array<double, 3>> OrcaEngine::design_offset(size_t object, size_t instance) const
{
    if (int(object) != m_build_object)
        return std::nullopt;
    // Design pose: the print pose's turn, scale and mirror left out, bottom on the bed, at the copy's X/Y.
    const ModelObject  &obj = *m_model->objects[object];
    const BoundingBoxf3 box = design_box(obj);
    const Vec3d         off = obj.instances[instance]->get_offset();
    return std::array<double, 3>{off.x(), off.y(), box.defined ? -box.min.z() : off.z()};
}

json OrcaEngine::set_object_module(int object, const std::string &module)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    object_at(object).module_name = module;
    return commit();
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

    const BoundingBoxf3 raw_bb = obj.raw_mesh_bounding_box();
    const BoundingBoxf3 inst_bb = obj.instance_bounding_box(0);
    const Vec3d mesh_size = mesh.bounding_box().size();
    ModelVolume *vol = obj.add_volume(std::move(mesh), vtype); // centres the geometry
    Vec3d pos(position[0], position[1], position[2]);
    if (!std::isfinite(pos.x())) {
        if (vtype == ModelVolumeType::MODEL_PART) {
            // ObjectList::load_generic_subobject: a new part sits beside the object (+X), on the bed.
            const Geometry::Transformation &it = obj.instances[0]->get_transformation();
            const Vec3d world = Vec3d(inst_bb.max.x(), inst_bb.min.y(), inst_bb.min.z()) + 0.5 * mesh_size - it.get_offset();
            vol->set_transformation(it.get_matrix_no_offset().inverse());
            pos = it.get_matrix_no_offset().inverse() * world;
        } else {
            // Default: middle of the object, in object coordinates.
            pos = raw_bb.center();
        }
    }
    vol->set_offset(pos);
    switch (vtype) {
    case ModelVolumeType::SUPPORT_BLOCKER: vol->name = "Support blocker"; break;
    case ModelVolumeType::SUPPORT_ENFORCER: vol->name = "Support enforcer"; break;
    case ModelVolumeType::NEGATIVE_VOLUME: vol->name = "Negative volume"; break;
    case ModelVolumeType::MODEL_PART: vol->name = "Part " + std::to_string(obj.volumes.size()); break;
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
    // Objects outside every plate (an import with more than fit, or left beside the grid): the new
    // plate takes the one nearest to it, instead of appearing at a grid spot that may hold none. A
    // floater that already lies in the new plate's spot keeps it alone (no overlap at the centre).
    const std::array<double, 4> rect = bed_rect();
    const int plate = int(m_plates.size()) - 1;
    const auto origin = plate_origin(plate);
    const Vec2d centre(origin[0] + (rect[0] + rect[2]) / 2, origin[1] + (rect[1] + rect[3]) / 2);
    ModelInstance *nearest = nullptr;
    Vec2d nearest_centre;
    bool occupied = false;
    for (ModelObject *obj : m_model->objects)
        for (size_t i = 0; i < obj->instances.size(); ++i) {
            const Vec3d c = obj->instance_bounding_box(i).center();
            const int at = plate_at(c, m_plates.size(), rect);
            occupied |= at == plate;
            if (at >= 0)
                continue;
            if (!nearest || (c.head<2>() - centre).squaredNorm() < (nearest_centre - centre).squaredNorm()) {
                nearest = obj->instances[i];
                nearest_centre = c.head<2>();
            }
        }
    if (nearest && !occupied) {
        nearest->set_offset(nearest->get_offset() + Vec3d(centre.x() - nearest_centre.x(), centre.y() - nearest_centre.y(), 0.));
        nearest->get_object()->invalidate_bounding_box();
    }
    return commit();
}

json OrcaEngine::delete_plates(std::vector<int> plates)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    std::sort(plates.begin(), plates.end());
    plates.erase(std::unique(plates.begin(), plates.end()), plates.end());
    if (plates.empty() || plates.front() < 0 || plates.back() >= int(m_plates.size()) || plates.size() >= m_plates.size())
        throw std::runtime_error("This plate cannot be deleted");
    push_undo();
    const size_t old_count = m_plates.size();
    for (auto it = plates.rbegin(); it != plates.rend(); ++it)
        m_plates.erase(m_plates.begin() + *it);
    // Like the desktop (PartPlateList::delete_plate), a deleted plate's objects stay: they move off
    // the grid as floaters (into the free grid spots after the last plate, one spot per plate, skipping
    // spots other floaters already use), and the plates after it close the gap.
    const std::array<double, 4> rect = bed_rect();
    std::vector<Vec3d> floaters;
    for (const ModelObject *obj : m_model->objects)
        for (size_t i = 0; i < obj->instances.size(); ++i)
            if (plate_at(obj->instance_bounding_box(i).center(), old_count, m_layout_rect) < 0)
                floaters.push_back(obj->instance_bounding_box(i).center());
    std::vector<int> spots;
    for (int s = int(m_plates.size()); spots.size() < plates.size(); ++s) {
        const auto o = origin_of(s, m_plates.size(), rect);
        if (std::none_of(floaters.begin(), floaters.end(), [&](const Vec3d &c) {
                return c.x() >= o[0] + rect[0] && c.x() <= o[0] + rect[2] && c.y() >= o[1] + rect[1] && c.y() <= o[1] + rect[3];
            }))
            spots.push_back(s);
    }
    relayout(old_count, m_layout_rect, [&plates, &spots](int p) {
        const auto it = std::lower_bound(plates.begin(), plates.end(), p);
        const int k = int(it - plates.begin());
        return it != plates.end() && *it == p ? spots[k] : p - k;
    });
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
