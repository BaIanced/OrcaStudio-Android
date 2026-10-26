// OrcaEngine: sculpt dabs on a volume's mesh (MeshSculpt.hpp). Works in the volume's own coordinates; the brush radius
// comes in world mm and is divided by the copy's scale. The mesh is refined once per stroke when it is too coarse for
// the brush (like a dynamic-topology start), then only vertices move, so facet indices (and painting) stay valid.

#include "OrcaEngine.hpp"
#include "MeshSculpt.hpp"

#include <cmath>
#include <stdexcept>

#include "libslic3r/Model.hpp"

namespace orca {

using namespace Slic3r;

json OrcaEngine::sculpt(const std::vector<PaintHit> &hits, const std::string &brush, double radius, double strength, bool invert, bool new_stroke)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!(radius > 0.) || !std::isfinite(radius))
        throw std::runtime_error("Invalid brush radius");
    const meshsculpt::Brush kind = meshsculpt::brush_of(brush);
    if (new_stroke)
        m_sculpt_prev.reset();
    if (hits.empty())
        return scene_json();

    // All hits of a call are on one volume in practice (the app picks one object); a batch that spans volumes is cut at the change.
    const PaintHit &first = hits.front();
    ModelObject &obj = object_at(first.object);
    if (first.instance < 0 || first.instance >= int(obj.instances.size()) || first.volume < 0 || first.volume >= int(obj.volumes.size()))
        throw std::runtime_error("Invalid sculpt target");
    ModelVolume *vol = obj.volumes[first.volume];
    if (!vol->is_model_part())
        throw std::runtime_error("Only model parts can be sculpted");
    if (first.facet >= int(vol->mesh().its.indices.size()))
        throw std::runtime_error("Invalid sculpt target");

    // Volume units per world mm: the copy's and the part's scale together (cube root of the volume factor).
    const Transform3d frame = obj.instances[first.instance]->get_matrix() * vol->get_matrix();
    const double scale = std::cbrt(std::abs(frame.linear().determinant()));
    const double local_radius = radius / std::max(scale, 1e-9);

    const indexed_triangle_set &src = vol->mesh().its;
    meshdeform::Mesh m;
    m.vertices.reserve(src.vertices.size());
    for (const stl_vertex &v : src.vertices)
        m.vertices.push_back({v.x(), v.y(), v.z()});
    m.faces.reserve(src.indices.size());
    for (const stl_triangle_vertex_indices &f : src.indices)
        m.faces.push_back({f[0], f[1], f[2]});

    if (new_stroke)
        push_undo();

    // Too coarse for this brush: split edges to a fifth of the radius, within a face budget (the budget wins on huge parts).
    bool refined = false;
    if (new_stroke) {
        const size_t budget = std::max<size_t>(400000, m.faces.size());
        double longest = 0.;
        for (const auto &f : m.faces)
            for (int k = 0; k < 3; ++k) {
                const auto &a = m.vertices[f[k]], &b = m.vertices[f[(k + 1) % 3]];
                longest = std::max(longest, std::sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)));
            }
        if (longest > local_radius / 5. && m.faces.size() < budget) {
            meshdeform::refine_to_length(m, local_radius / 5., budget);
            refined = true;
        }
    }

    const meshsculpt::Topology topo = meshsculpt::build_topology(m);
    for (const PaintHit &h : hits) {
        if (h.object != first.object || h.instance != first.instance || h.volume != first.volume)
            break;
        meshsculpt::Stroke s;
        s.brush = kind;
        s.center = {h.local[0], h.local[1], h.local[2]};
        s.radius = local_radius;
        s.strength = std::clamp(strength, 0., 1.);
        s.invert = invert;
        if (kind == meshsculpt::Brush::Grab && m_sculpt_prev && h.join)
            s.delta = {s.center.x - (*m_sculpt_prev)[0], s.center.y - (*m_sculpt_prev)[1], s.center.z - (*m_sculpt_prev)[2]};
        m_sculpt_prev = h.local;
        meshsculpt::dab(m, topo, s);
    }

    indexed_triangle_set its;
    its.vertices.reserve(m.vertices.size());
    for (const meshdeform::Vec3 &v : m.vertices)
        its.vertices.emplace_back(float(v.x), float(v.y), float(v.z));
    its.indices.reserve(m.faces.size());
    for (const auto &f : m.faces)
        its.indices.emplace_back(f[0], f[1], f[2]);
    vol->set_mesh(std::move(its));
    if (refined) {
        vol->supported_facets.reset();
        vol->seam_facets.reset();
        vol->mmu_segmentation_facets.reset();
        vol->fuzzy_skin_facets.reset();
    }
    vol->calculate_convex_hull();
    vol->invalidate_convex_hull_2d();
    obj.invalidate_bounding_box();
    return commit();
}

} // namespace orca
