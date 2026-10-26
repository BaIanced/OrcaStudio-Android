// OrcaEngine: smooth blend. The object's model parts are fused into one mesh by an OpenVDB morphological
// closing (grow the surface by r, shrink it back by r): joins and crevices fill with smooth fillets, convex
// edges stay. Works on imported STLs too. The result replaces the model parts (modifiers stay); painting is dropped.

#include "OrcaEngine.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>

#include "libslic3r/Model.hpp"
#include "libslic3r/OpenVDBUtils.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orca {

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

    const BoundingBoxf3 box = bounding_box(all);
    const double diag = box.size().norm();
    // About 300 voxels along the diagonal (a few million at most); the radius is at least 2 voxels so the
    // closing has something to grip. Capped at a third of the diagonal.
    const double voxel = diag / 300.;
    const double r = std::clamp(radius, 2. * voxel, diag / 3.);
    const float scale = float(1. / voxel);
    const float band = float(r * scale) + 3.f;

    openvdb::FloatGrid::Ptr grid = mesh_to_grid(all, {}, scale, band, band);
    if (!grid)
        throw std::runtime_error("The object could not be voxelized");
    grid = redistance_grid(*grid, r * scale, band, band);
    grid = redistance_grid(*grid, -r * scale, band, band);
    indexed_triangle_set out = grid_to_mesh(*grid, 0., 0.05);
    if (out.indices.empty())
        throw std::runtime_error("The blend produced no surface");
    its_merge_vertices(out);

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
