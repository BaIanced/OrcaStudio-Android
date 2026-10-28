// OrcaEngine: hollow. Each model part of the copy gets an inner cavity `wall` mm inside its surface (OrcaSlicer's SLA
// hollowing: OpenVDB offset of the level set), so FDM prints use less filament. The cavity is a closed inner shell
// of the same part (wound inward); slicing treats it as a hole. Painting on the part is dropped.

#include "OrcaEngine.hpp"

#include <algorithm>
#include <chrono>
#include <stdexcept>

#include <boost/log/trivial.hpp>
#include <sys/system_properties.h>
#include <tbb/task_arena.h>

#include "libslic3r/Model.hpp"
#include "libslic3r/SLA/Hollowing.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orca {

using namespace Slic3r;

json OrcaEngine::hollow_object(int object, int instance, double wall)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    if (!(wall > 0.))
        throw std::runtime_error("The wall thickness must be positive");

    // WSA's ARM translator livelocked OpenVDB's band expansion with 16 threads (see smooth_blend).
    char bridge[PROP_VALUE_MAX] = {};
    const bool translated = __system_property_get("ro.dalvik.vm.native.bridge", bridge) > 0 && std::string(bridge) != "0";
    tbb::task_arena arena(translated ? 4 : tbb::task_arena::automatic);

    const Transform3d inst = obj.instances[instance]->get_matrix();
    std::vector<std::pair<ModelVolume *, indexed_triangle_set>> done;
    sla::HollowingConfig cfg;
    cfg.min_thickness = wall;
    cfg.quality = 0.5;
    cfg.closing_distance = std::min(0.5, wall / 2.);
    const auto t0 = std::chrono::steady_clock::now();
    for (ModelVolume *vol : obj.volumes) {
        if (!vol->is_model_part())
            continue;
        const Transform3d full = inst * vol->get_matrix();
        indexed_triangle_set its = vol->mesh().its;
        if (its.indices.empty())
            continue;
        its_transform(its, full);
        // OpenVDB signs by flood fill, so an inside-out part still voxelizes; keep the outer shell wound outward.
        if (its_volume(its) < 0.)
            its_flip_triangles(its);
        TriangleMesh mesh(std::move(its));
        const size_t before = mesh.its.indices.size();
        arena.execute([&] { sla::hollow_mesh(mesh, cfg, sla::hfRemoveInsideTriangles); });
        if (mesh.its.indices.size() <= before)
            continue; // too thin for this wall: nothing to carve out
        indexed_triangle_set out = std::move(mesh.its);
        its_merge_vertices(out);
        its_transform(out, full.inverse());
        done.emplace_back(vol, std::move(out));
    }
    if (done.empty())
        throw std::runtime_error("Nothing to hollow: the walls are too thin for this thickness");
    BOOST_LOG_TRIVIAL(warning) << "hollow: " << done.size() << " parts, wall " << wall << " mm in "
                               << std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() << " ms";

    push_undo();
    for (auto &[vol, out] : done) {
        vol->set_mesh(std::move(out));
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
