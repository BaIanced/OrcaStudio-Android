// OrcaEngine: smooth blend. The object's model parts are fused into one mesh by an OpenVDB morphological
// closing (grow the surface by r, shrink it back by r): joins and crevices fill with smooth fillets, convex
// edges stay. Works on imported STLs too. The result replaces the model parts (modifiers stay); painting is dropped.

#include "OrcaEngine.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <stdexcept>

#include <boost/log/trivial.hpp>
#include <sys/system_properties.h>
#include <tbb/task_arena.h>

#include "libslic3r/Model.hpp"
#include <openvdb/tools/Composite.h>
#include <openvdb/tools/LevelSetRebuild.h>
#include <openvdb/tools/MeshToVolume.h>

#include "libslic3r/OpenVDBUtils.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orca {

namespace {

// openvdb::tools::meshToVolume input over an indexed triangle set, scaled so a voxel is one unit.
struct MeshAdapter {
    const indexed_triangle_set &its;
    float scale;
    size_t polygonCount() const { return its.indices.size(); }
    size_t pointCount() const { return its.vertices.size(); }
    size_t vertexCount(size_t) const { return 3; }
    void getIndexSpacePoint(size_t n, size_t v, openvdb::Vec3d &pos) const
    {
        const Slic3r::Vec3f &p = its.vertices[size_t(its.indices[n](Eigen::Index(v)))];
        pos = {double(p.x()) * scale, double(p.y()) * scale, double(p.z()) * scale};
    }
};

} // namespace

using namespace Slic3r;

json OrcaEngine::smooth_blend(int object, int instance, double radius)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    if (!(radius > 0.))
        throw std::runtime_error("The blend radius must be positive");

    const Transform3d inst = obj.instances[instance]->get_matrix();
    indexed_triangle_set all;
    int first = -1;
    int parts = 0;
    for (size_t i = 0; i < obj.volumes.size(); ++i) {
        const ModelVolume *vol = obj.volumes[i];
        if (!vol->is_model_part())
            continue;
        if (first < 0)
            first = int(i);
        ++parts;
        indexed_triangle_set its = vol->mesh().its;
        its_transform(its, inst * vol->get_matrix());
        its_merge(all, its);
    }
    if (first < 0 || all.indices.empty())
        throw std::runtime_error("The object has no model part");

    using clock = std::chrono::steady_clock;
    const auto t0 = clock::now();
    auto ms = [&](clock::time_point a) { return std::chrono::duration<double, std::milli>(clock::now() - a).count(); };
    const BoundingBoxf3 box = bounding_box(all);
    const double diag = box.size().norm();
    // The closing's cost grows with the narrow band (r / voxel voxels wide): a 40 mm radius at diag/300 touched
    // 91M voxels on a flat gear and never finished on WSA. 300 voxels along the diagonal, coarser when the box
    // of the grown object would hold more than kVoxelBudget voxels (a bound on the band, ~4 s / 230 MB for the
    // gear on a desktop). The radius is at least 2 voxels so the closing has something to grip, at most a third
    // of the diagonal.
    constexpr double kVoxelBudget = 8e6;
    const double grow = std::clamp(radius, 0., diag / 3.);
    const Vec3d size = box.size();
    const double grown = (size.x() + 2. * grow) * (size.y() + 2. * grow) * (size.z() + 2. * grow);
    const double voxel = std::max(diag / 300., std::cbrt(grown / kVoxelBudget));
    const double r = std::clamp(radius, 2. * voxel, diag / 3.);
    const float scale = float(1. / voxel);
    // Bands in voxels: the grow needs r + 3 outside the surface, the shrink r + 3 inside the grown one; the rest
    // only 3 (half the work of r + 3 on both sides, same surface).
    const float band = float(r * scale) + 3.f;
    const float thin = 3.f;

    m_interrupt = false;
    struct Interrupter {
        const std::atomic<bool> &flag;
        void start(const char * = nullptr) {}
        void end() {}
        bool wasInterrupted(int = -1) const { return flag.load(std::memory_order_relaxed); }
    } interrupter{m_interrupt};
    auto check = [&] {
        if (m_interrupt)
            throw std::runtime_error("Cancelled");
    };

    const std::vector<indexed_triangle_set> shells = its_split(all);
    BOOST_LOG_TRIVIAL(warning) << "smooth blend: start, " << all.indices.size() << " faces, " << shells.size() << " shells, diag " << diag << " mm, voxel " << voxel << " mm, r " << r << " mm, split in " << ms(t0) << " ms";
    // As Slic3r::mesh_to_grid (one level set per shell, so overlapping shells union cleanly), interruptible.
    openvdb::initialize();
    // Under an ARM translator (WSA's Houdini: ro.dalvik.vm.native.bridge set) meshToVolume's band expansion
    // livelocked with 16 threads (2026-10-26, 176k-face gear, r 40: stuck >10 min mid-layer, all cores busy);
    // 4 threads finished in 18 s, 1 in 47 s. Native ARM devices keep every core.
    char bridge[PROP_VALUE_MAX] = {};
    const bool translated = __system_property_get("ro.dalvik.vm.native.bridge", bridge) > 0 && std::string(bridge) != "0";
    tbb::task_arena arena(translated ? 4 : tbb::task_arena::automatic);
    indexed_triangle_set out = arena.execute([&] {
    openvdb::FloatGrid::Ptr grid;
    for (const indexed_triangle_set &shell : shells) {
        // OpenVDB signs by flood fill, not winding: inside-out shells (old blends, some STLs) voxelize fine.
        if (std::abs(its_volume(shell)) < EPSILON)
            continue;
        auto sub = openvdb::tools::meshToVolume<openvdb::FloatGrid>(interrupter, MeshAdapter{shell, scale}, openvdb::math::Transform(), band, thin, 0);
        check();
        if (grid && sub)
            openvdb::tools::csgUnion(*grid, *sub);
        else if (sub)
            grid = std::move(sub);
    }
    if (!grid)
        throw std::runtime_error("The object could not be voxelized");
    grid = openvdb::tools::levelSetRebuild(*grid, 0.f, band, thin, nullptr, &interrupter);
    check();
    BOOST_LOG_TRIVIAL(warning) << "smooth blend: voxelized " << grid->activeVoxelCount() << " voxels in " << ms(t0) << " ms";
    auto t1 = clock::now();
    grid = openvdb::tools::levelSetRebuild(*grid, float(r * scale), thin, band, nullptr, &interrupter);
    check();
    BOOST_LOG_TRIVIAL(warning) << "smooth blend: grown in " << ms(t1) << " ms";
    t1 = clock::now();
    grid = openvdb::tools::levelSetRebuild(*grid, float(-r * scale), thin, thin, nullptr, &interrupter);
    check();
    BOOST_LOG_TRIVIAL(warning) << "smooth blend: shrunk in " << ms(t1) << " ms";
    t1 = clock::now();
    // grid_to_mesh divides by this to get back to mm.
    grid->insertMeta("voxel_scale", openvdb::FloatMetadata(scale));
    return grid_to_mesh(*grid, 0., 0.05);
    });
    BOOST_LOG_TRIVIAL(warning) << "smooth blend: meshed " << out.indices.size() << " faces";
    if (out.indices.empty())
        throw std::runtime_error("The blend produced no surface");
    its_merge_vertices(out);
    // volumeToMesh winds the faces inward (signed volume < 0); a second blend then skipped the shell as empty.
    if (its_volume(out) < 0.)
        its_flip_triangles(out);

    push_undo();
    ModelVolume *keep = obj.volumes[first];
    const Transform3d back = (inst * keep->get_matrix()).inverse();
    its_transform(out, back);
    keep->set_mesh(std::move(out));
    keep->supported_facets.reset();
    keep->seam_facets.reset();
    keep->mmu_segmentation_facets.reset();
    keep->fuzzy_skin_facets.reset();
    keep->calculate_convex_hull();
    keep->invalidate_convex_hull_2d();
    for (int i = int(obj.volumes.size()) - 1; i > first && parts > 1; --i)
        if (obj.volumes[i]->is_model_part()) {
            obj.delete_volume(size_t(i));
            --parts;
        }
    obj.invalidate_bounding_box();
    drop_to_bed(obj);
    return commit();
}

} // namespace orca
