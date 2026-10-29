// OrcaEngine: per-region edits (interaction plan step 8b slice 2) on the faces a Regions tap picks: smoothing them (Taubin,
// the region's border pinned) or putting a modifier volume on them (the region offset into the part, closed into a solid).
// The kernels are standalone (MeshRegion.hpp); this file picks the region and maps meshes in and out.

#include "OrcaEngine.hpp"
#include "MeshRegion.hpp"

#include <cmath>
#include <stdexcept>

#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orca {

using namespace Slic3r;

json OrcaEngine::region_edit(int object, int instance, int volume, int facet, double edge_angle, const std::string &op, double amount)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    if (volume < 0 || volume >= int(obj.volumes.size()))
        throw std::runtime_error("Invalid volume");
    ModelVolume *vol = obj.volumes[volume];
    const indexed_triangle_set &its = vol->mesh().its;
    if (facet < 0 || facet >= int(its.indices.size()))
        throw std::runtime_error("Invalid facet");
    if (!std::isfinite(amount))
        throw std::runtime_error("Invalid amount");

    // The same faces as the Regions fill from this seed (it ignores existing paint).
    std::vector<int> neighbors;
    neighbors.reserve(3 * its.indices.size());
    for (const Vec3i32 &n : its_face_neighbors(its))
        neighbors.insert(neighbors.end(), {n.x(), n.y(), n.z()});
    const std::vector<bool> faces = flood_surface(its, neighbors, facet, edge_angle > 0. ? edge_angle : 180.);
    std::vector<uint8_t> region(faces.begin(), faces.end());

    // Smoothing works in the part's own coordinates (the umbrella Laplacian commutes with affine maps); the modifier's
    // depth is in printed millimetres, so it is built in the copy's plate frame.
    const Transform3d frame = op == "modifier" ? obj.instances[instance]->get_matrix() * vol->get_matrix() : Transform3d::Identity();
    meshregion::Mesh m;
    m.vertices.reserve(its.vertices.size());
    for (const stl_vertex &v : its.vertices) {
        const Vec3d w = frame * v.cast<double>();
        m.vertices.push_back({w.x(), w.y(), w.z()});
    }
    m.faces.reserve(its.indices.size());
    for (const stl_triangle_vertex_indices &f : its.indices)
        m.faces.push_back({f[0], f[1], f[2]});

    if (op == "smooth") {
        const int iterations = std::clamp(int(std::lround(amount)), 1, 100);
        if (meshregion::smooth_region(m, region, iterations) == 0)
            throw std::runtime_error("The region has no inner vertices to smooth");
        push_undo();
        indexed_triangle_set out = its;
        for (size_t i = 0; i < m.vertices.size(); ++i)
            out.vertices[i] = Vec3d(m.vertices[i].x, m.vertices[i].y, m.vertices[i].z).cast<float>();
        // Same faces in the same order: the painting stays on them.
        vol->set_mesh(std::move(out));
        vol->calculate_convex_hull();
        vol->invalidate_convex_hull_2d();
        obj.invalidate_bounding_box();
        drop_to_bed(obj);
        return commit();
    }
    if (op == "modifier") {
        if (amount <= 0.)
            throw std::runtime_error("Invalid depth");
        const meshregion::Mesh shell = meshregion::region_shell(m, region, 0.05, amount);
        // Plate frame -> object frame; add_volume centres the mesh and sets the offset to match, so it stays in place.
        const Transform3d back = obj.instances[instance]->get_matrix().inverse();
        indexed_triangle_set out;
        out.vertices.reserve(shell.vertices.size());
        for (const meshregion::Vec3 &v : shell.vertices)
            out.vertices.push_back((back * Vec3d(v.x, v.y, v.z)).cast<float>());
        out.indices.reserve(shell.faces.size());
        for (const auto &f : shell.faces)
            out.indices.emplace_back(f[0], f[1], f[2]);
        push_undo();
        ModelVolume *mod = obj.add_volume(TriangleMesh(std::move(out)), ModelVolumeType::PARAMETER_MODIFIER);
        mod->name = "Region modifier";
        obj.invalidate_bounding_box();
        return commit();
    }
    throw std::runtime_error("Unknown region edit: " + op);
}

} // namespace orca
