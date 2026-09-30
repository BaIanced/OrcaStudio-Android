// Scene editing for OrcaEngine: plates, object transforms and tools, undo/redo.
//
// Plates are laid out like the desktop app's PartPlateList: a grid of `cols` columns
// (ceil(sqrt(count))), each plate the size of the printer bed, separated by a fifth of its size;
// plate 0 sits at the bed origin, further rows extend towards -Y. An instance belongs to the plate
// under the centre of its bounding box. Keeping this layout makes positions compatible with
// desktop 3MF projects.

#include "OrcaEngine.hpp"

#include <cmath>
#include <fstream>
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

void OrcaEngine::push_undo()
{
    m_undo.push_back({std::make_shared<Model>(*m_model), m_plates});
    if (m_undo.size() > MAX_UNDO)
        m_undo.pop_front();
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
            obj->instances[i]->get_arrange_polygon(&ap);
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
    std::ofstream f(m_mesh_path, std::ios::binary | std::ios::trunc);
    if (!f)
        throw std::runtime_error("Cannot write " + m_mesh_path);
    for (size_t oi = 0; oi < m_model->objects.size(); ++oi) {
        const ModelObject *obj = m_model->objects[oi];
        for (const ModelInstance *inst : obj->instances)
            for (const ModelVolume *vol : obj->volumes) {
                TriangleMesh mesh = vol->mesh();
                mesh.transform(inst->get_matrix() * vol->get_matrix());
                const float obj_code = float(oi), type_code = float(volume_type_code(vol->type()));
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
            }
    }
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

json OrcaEngine::delete_instance(int object, int instance)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    push_undo();
    if (obj.instances.size() == 1)
        m_model->delete_object(size_t(object));
    else
        obj.delete_instance(size_t(instance));
    return commit();
}

json OrcaEngine::duplicate(int object, int copies)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (copies < 1 || copies > 100)
        throw std::runtime_error("Invalid number of copies");
    push_undo();
    const int plate = std::max(0, plate_of(obj, obj.instances.size() - 1));
    std::vector<std::pair<ModelObject *, size_t>> added;
    for (int k = 0; k < copies; ++k) {
        obj.add_instance(*obj.instances.back());
        added.emplace_back(&obj, obj.instances.size() - 1);
    }
    obj.invalidate_bounding_box();
    arrange_plate(plate, &added);
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

json OrcaEngine::set_object_setting(int object, int volume, const std::string &key, const json &value)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (volume >= int(obj.volumes.size()))
        throw std::runtime_error("Invalid volume");
    if (print_config_def.get(key) == nullptr)
        throw std::runtime_error("Unknown setting: " + key);
    push_undo();
    ModelConfig &config = volume < 0 ? static_cast<ModelConfig &>(obj.config) : obj.volumes[volume]->config;
    if (value.is_null()) {
        config.erase(key);
    } else {
        const std::string str = value.is_string() ? value.get<std::string>() : value.is_boolean() ? (value.get<bool>() ? "1" : "0") : value.dump();
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
        try {
            config.set_deserialize(key, str, ctx);
        } catch (const std::exception &ex) {
            m_undo.pop_back();
            throw std::runtime_error("Invalid value for " + key + ": " + ex.what());
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
