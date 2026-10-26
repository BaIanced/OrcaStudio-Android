// OrcaEngine: bend / twist / taper an object's mesh (works on imported STLs too). The parts are refined to a
// short edge length, then their vertices are warped in the plate frame of one copy, so the effect runs along
// the axis the user sees and the parts stay joined. Painting is dropped: the facets change.

#include "OrcaEngine.hpp"
#include "MeshDeform.hpp"

#include <stdexcept>

#include "libslic3r/Model.hpp"

namespace orca {

using namespace Slic3r;

json OrcaEngine::deform(int object, int instance, const std::string &kind, int axis, double amount)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    meshdeform::Params p{};
    if (kind == "bend")
        p.kind = meshdeform::Kind::Bend;
    else if (kind == "twist")
        p.kind = meshdeform::Kind::Twist;
    else if (kind == "taper")
        p.kind = meshdeform::Kind::Taper;
    else
        throw std::runtime_error("Unknown deform kind: " + kind);
    p.axis = axis;
    p.amount = amount;

    // Every volume in this copy's plate frame; the box (and so the effect) comes from the model parts only.
    const Transform3d inst = obj.instances[instance]->get_matrix();
    std::vector<meshdeform::Mesh> meshes(obj.volumes.size());
    std::vector<Transform3d> frames(obj.volumes.size());
    meshdeform::Vec3 lo{1e300, 1e300, 1e300}, hi{-1e300, -1e300, -1e300};
    size_t faces = 0;
    for (size_t i = 0; i < obj.volumes.size(); ++i) {
        const ModelVolume *vol = obj.volumes[i];
        frames[i] = inst * vol->get_matrix();
        const indexed_triangle_set &its = vol->mesh().its;
        meshdeform::Mesh &m = meshes[i];
        m.vertices.reserve(its.vertices.size());
        for (const stl_vertex &v : its.vertices) {
            const Vec3d w = frames[i] * v.cast<double>();
            m.vertices.push_back({w.x(), w.y(), w.z()});
            if (vol->is_model_part()) {
                lo = {std::min(lo.x, w.x()), std::min(lo.y, w.y()), std::min(lo.z, w.z())};
                hi = {std::max(hi.x, w.x()), std::max(hi.y, w.y()), std::max(hi.z, w.z())};
            }
        }
        m.faces.reserve(its.indices.size());
        for (const stl_triangle_vertex_indices &f : its.indices)
            m.faces.push_back({f[0], f[1], f[2]});
        faces += its.indices.size();
    }
    if (lo.x > hi.x)
        throw std::runtime_error("The object has no model part");

    // One edge length for all parts: 1/60 of the box diagonal, longer when the face budget (about 1.5M over
    // the whole object, shared by the parts' sizes) would run out first.
    const double diag = std::sqrt((hi.x - lo.x) * (hi.x - lo.x) + (hi.y - lo.y) * (hi.y - lo.y) + (hi.z - lo.z) * (hi.z - lo.z));
    const size_t budget = std::max<size_t>(1500000, faces * 2);
    push_undo();
    for (size_t i = 0; i < obj.volumes.size(); ++i) {
        meshdeform::Mesh &m = meshes[i];
        const size_t share = std::max<size_t>(m.faces.size() * 2, size_t(double(budget) * m.faces.size() / std::max<size_t>(1, faces)));
        meshdeform::refine_to_length(m, diag / 60., share);
        meshdeform::warp(m, p, lo, hi);

        const Transform3d back = frames[i].inverse();
        indexed_triangle_set its;
        its.vertices.reserve(m.vertices.size());
        for (const meshdeform::Vec3 &v : m.vertices)
            its.vertices.push_back((back * Vec3d(v.x, v.y, v.z)).cast<float>());
        its.indices.reserve(m.faces.size());
        for (const auto &f : m.faces)
            its.indices.emplace_back(f[0], f[1], f[2]);
        ModelVolume *vol = obj.volumes[i];
        vol->set_mesh(std::move(its));
        vol->supported_facets.reset();
        vol->seam_facets.reset();
        vol->mmu_segmentation_facets.reset();
        vol->fuzzy_skin_facets.reset();
        vol->calculate_convex_hull();
        vol->invalidate_convex_hull_2d();
    }
    obj.invalidate_bounding_box();
    drop_to_bed(obj);
    return commit();
}

} // namespace orca
