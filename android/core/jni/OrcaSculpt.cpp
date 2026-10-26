// OrcaEngine: live sculpting on a volume's mesh (MeshSculpt.hpp). A session keeps the part's mesh, its topology and a
// hit tree between calls, so a drag only costs the dabs: each call writes just the triangles it changed (a patch the
// app copies into its GL buffer) instead of the whole scene mesh. The part's mesh is written back at the stroke's end
// (or before any other engine call, see dispatch()). Works in the volume's own coordinates; rays and the brush radius
// come in world mm. The mesh is refined at a stroke's start when it is too coarse for the brush (a dynamic-topology
// start); after that only vertices move, so facet indices (and painting) stay valid.

#include "OrcaEngine.hpp"
#include "MeshSculpt.hpp"
#include "Thumbnails.hpp"

#include <cmath>
#include <cstdio>
#include <fstream>
#include <limits>
#include <optional>
#include <stdexcept>

#include "libslic3r/AABBTreeIndirect.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"

#include <boost/log/trivial.hpp>
#include <chrono>

namespace orca {

using namespace Slic3r;

struct OrcaEngine::SculptSession {
    int object = -1, instance = -1, volume = -1;
    // The part's mesh this session matches (set when built and at each flush): another mesh there = stale session.
    std::shared_ptr<const TriangleMesh> current;
    meshdeform::Mesh mesh;
    meshsculpt::Topology topo;
    bool dirty = false; // vertices moved since the last flush
    // The edge length the part was refined to for the brush (strokes with a brush as big skip the check: sculpted
    // edges stretch past it, and refining them every stroke grew the mesh and rebuilt the session each time).
    double refined_to = std::numeric_limits<double>::max();
    // Hits: a tree over [base] (the mesh when the session was built); faces moved since then are tested one by one.
    std::shared_ptr<const TriangleMesh> base;
    AABBTreeIndirect::Tree<3, float> tree;
    double eps = 1e-6;
    std::vector<char> touched;
    std::vector<int> touched_list;
    // The part's copies in the scene mesh, in write_mesh() order: first triangle, drawn matrix, ids. Valid for [copies_version].
    struct Copy {
        size_t first;
        Transform3d frame;
        bool flip;
        float id, type;
    };
    std::vector<Copy> copies;
    int copies_version = -1;
    Transform3d frame = Transform3d::Identity(); // volume -> world of the sculpted copy
    // Grab: the vertices under the brush at the stroke's first hit, their weights and start places; the drag plane (local).
    bool grabbing = false;
    std::vector<int> grab_verts;
    std::vector<double> grab_w;
    std::vector<meshdeform::Vec3> grab_orig;
    Vec3d grab_anchor = Vec3d::Zero(), grab_normal = Vec3d::UnitZ();
    meshsculpt::StrokeState layer;
    // Per-call dedupe of moved vertices / changed faces.
    std::vector<uint32_t> vstamp, fstamp;
    uint32_t stamp = 0;
};

static meshdeform::Mesh to_mesh(const indexed_triangle_set &src)
{
    meshdeform::Mesh m;
    m.vertices.reserve(src.vertices.size());
    for (const stl_vertex &v : src.vertices)
        m.vertices.push_back({v.x(), v.y(), v.z()});
    m.faces.reserve(src.indices.size());
    for (const stl_triangle_vertex_indices &f : src.indices)
        m.faces.push_back({f[0], f[1], f[2]});
    return m;
}

static indexed_triangle_set to_its(const meshdeform::Mesh &m)
{
    indexed_triangle_set its;
    its.vertices.reserve(m.vertices.size());
    for (const meshdeform::Vec3 &v : m.vertices)
        its.vertices.emplace_back(float(v.x), float(v.y), float(v.z));
    its.indices.reserve(m.faces.size());
    for (const auto &f : m.faces)
        its.indices.emplace_back(f[0], f[1], f[2]);
    return its;
}

// Moller-Trumbore; the ray parameter or -1.
static double ray_triangle(const Vec3d &o, const Vec3d &d, const meshdeform::Vec3 &a0, const meshdeform::Vec3 &b0, const meshdeform::Vec3 &c0)
{
    const Vec3d a(a0.x, a0.y, a0.z), b(b0.x, b0.y, b0.z), c(c0.x, c0.y, c0.z);
    const Vec3d e1 = b - a, e2 = c - a, p = d.cross(e2);
    const double det = e1.dot(p);
    if (std::abs(det) < 1e-14)
        return -1.;
    const double inv = 1. / det;
    const Vec3d s = o - a;
    const double u = s.dot(p) * inv;
    if (u < 0. || u > 1.)
        return -1.;
    const Vec3d q = s.cross(e1);
    const double v = d.dot(q) * inv;
    if (v < 0. || u + v > 1.)
        return -1.;
    const double t = e2.dot(q) * inv;
    return t > 0. ? t : -1.;
}

void OrcaEngine::sculpt_build(int object, int instance, int volume)
{
    ModelObject &obj = object_at(object);
    ModelVolume *vol = obj.volumes[volume];
    auto s = std::make_shared<SculptSession>();
    s->object = object;
    s->instance = instance;
    s->volume = volume;
    s->current = s->base = vol->mesh_ptr();
    const indexed_triangle_set &its = s->base->its;
    const auto t0 = std::chrono::steady_clock::now();
    auto ms = [&t0] { return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count(); };
    s->mesh = to_mesh(its);
    const auto t_mesh = ms();
    s->topo = meshsculpt::build_topology(s->mesh);
    const auto t_topo = ms();
    s->tree = AABBTreeIndirect::build_aabb_tree_over_indexed_triangle_set(its.vertices, its.indices);
    BOOST_LOG_TRIVIAL(warning) << "sculpt: session on " << its.indices.size() << " faces: mesh " << t_mesh << " ms, topology " << t_topo
                               << " ms, tree " << ms() << " ms";
    const double l = its_average_edge_length(its);
    if (l > 0)
        s->eps = 1e-6 * l * l;
    s->touched.assign(its.indices.size(), 0);
    m_sculpt = std::move(s);
}

// The sculpted part's copies in the scene mesh; the same order, matrices and ids as write_mesh().
void OrcaEngine::sculpt_copies()
{
    SculptSession &s = *m_sculpt;
    s.copies.clear();
    size_t first = 0;
    for (size_t oi = 0; oi < m_model->objects.size(); ++oi) {
        const ModelObject *obj = m_model->objects[oi];
        for (size_t ii = 0; ii < obj->instances.size(); ++ii)
            for (size_t vi = 0; vi < obj->volumes.size(); ++vi) {
                const ModelVolume *vol = obj->volumes[vi];
                if (int(oi) == s.object && int(vi) == s.volume) {
                    const auto design = design_offset(oi, ii);
                    const Transform3d shown = design ? Transform3d(Eigen::Translation3d((*design)[0], (*design)[1], (*design)[2])) : obj->instances[ii]->get_matrix();
                    const Transform3d frame = shown * vol->get_matrix();
                    const float id = int(oi) == m_build_object ? -1000.f - float(vi) : float(oi * INSTANCE_ID_STRIDE + ii);
                    s.copies.push_back({first, frame, frame.linear().determinant() < 0., id, float(100 + filament_of(*obj, *vol))});
                    if (int(ii) == s.instance)
                        s.frame = frame;
                }
                first += vol->mesh().its.indices.size();
            }
    }
    s.copies_version = m_mesh_version;
}

bool OrcaEngine::sculpt_flush_locked()
{
    if (!m_sculpt || !m_sculpt->dirty)
        return false;
    SculptSession &s = *m_sculpt;
    if (s.object < 0 || s.object >= int(m_model->objects.size()) || s.volume >= int(m_model->objects[s.object]->volumes.size()) ||
        m_model->objects[s.object]->volumes[s.volume]->mesh_ptr() != s.current) {
        m_sculpt.reset();
        return false;
    }
    ModelObject &obj = *m_model->objects[s.object];
    ModelVolume *vol = obj.volumes[s.volume];
    vol->set_mesh(to_its(s.mesh));
    vol->calculate_convex_hull();
    vol->invalidate_convex_hull_2d();
    obj.invalidate_bounding_box();
    s.current = vol->mesh_ptr();
    s.dirty = false;
    m_aabb.clear();
    m_neighbors.clear();
    return true;
}

bool OrcaEngine::sculpt_flush()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return sculpt_flush_locked();
}

json OrcaEngine::sculpt_end()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_sculpt)
        m_sculpt->grabbing = false;
    if (!sculpt_flush_locked())
        return scene_json();
    drop_to_bed(*m_model->objects[m_sculpt->object]);
    return commit();
}

json OrcaEngine::sculpt(const std::array<int, 3> &target, const std::vector<std::array<double, 6>> &rays, const std::string &brush,
                        double radius, double strength, bool invert, bool new_stroke, bool end, bool probe)
{
    if (probe)
        new_stroke = end = false;
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!(radius > 0.) || !std::isfinite(radius))
        throw std::runtime_error("Invalid brush radius");
    const meshsculpt::Brush kind = meshsculpt::brush_of(brush);
    const auto [oi, ii, vi] = target;
    ModelObject &obj = object_at(oi);
    if (ii < 0 || ii >= int(obj.instances.size()) || vi < 0 || vi >= int(obj.volumes.size()))
        throw std::runtime_error("Invalid sculpt target");
    ModelVolume *vol = obj.volumes[vi];
    if (!vol->is_model_part())
        throw std::runtime_error("Only model parts can be sculpted");

    bool committed = false;
    // A probe on another part (hover) keeps that part's sculpt too.
    if (probe && m_sculpt && (m_sculpt->object != oi || m_sculpt->volume != vi))
        sculpt_flush_locked();
    if (new_stroke) {
        // Another part's unfinished stroke goes into its mesh first.
        if (m_sculpt && (m_sculpt->object != oi || m_sculpt->volume != vi))
            sculpt_flush_locked();
        push_undo();
    }
    // A session that no longer matches this part's mesh (undo, another edit, a first stroke) is rebuilt. A session whose
    // tree has gone stale under many moved faces is rebuilt at a stroke's start.
    bool fresh = m_sculpt && m_sculpt->object == oi && m_sculpt->volume == vi && m_sculpt->current == vol->mesh_ptr();
    if (fresh && new_stroke && m_sculpt->touched_list.size() > std::max<size_t>(20000, m_sculpt->mesh.faces.size() / 10)) {
        sculpt_flush_locked();
        fresh = false;
    }
    if (!fresh) {
        // Same part, same mesh (the stale-tree rebuild): it is still refined for the last brush.
        const double refined = m_sculpt && m_sculpt->object == oi && m_sculpt->volume == vi && m_sculpt->current == vol->mesh_ptr()
            ? m_sculpt->refined_to : std::numeric_limits<double>::max();
        sculpt_build(oi, ii, vi);
        m_sculpt->refined_to = refined;
    }
    if (m_sculpt->copies_version != m_mesh_version || m_sculpt->instance != ii) {
        m_sculpt->instance = ii;
        sculpt_copies();
    }
    SculptSession &s = *m_sculpt;
    if (new_stroke)
        s.grabbing = false;

    // Volume units per world mm: the copy's and the part's scale together (cube root of the volume factor).
    const double scale = std::cbrt(std::abs(s.frame.linear().determinant()));
    const double local_radius = radius / std::max(scale, 1e-9);

    // Too coarse for this brush: split edges to a fifth of the radius, within a face budget (the budget wins on huge
    // parts). The refined mesh goes into the part and the scene at once, then the session starts over on it.
    const double edge = local_radius / 5.;
    if (new_stroke && edge < s.refined_to * 0.9) {
        s.refined_to = edge;
        const size_t budget = std::max<size_t>(400000, s.mesh.faces.size());
        double longest = 0.;
        for (const auto &f : s.mesh.faces)
            for (int k = 0; k < 3; ++k) {
                const auto &a = s.mesh.vertices[f[k]], &b = s.mesh.vertices[f[(k + 1) % 3]];
                longest = std::max(longest, (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z));
            }
        if (std::sqrt(longest) > edge && s.mesh.faces.size() < budget) {
            meshdeform::Mesh m = std::move(s.mesh);
            meshdeform::refine_to_length(m, edge, budget);
            vol->set_mesh(to_its(m));
            vol->supported_facets.reset();
            vol->seam_facets.reset();
            vol->mmu_segmentation_facets.reset();
            vol->fuzzy_skin_facets.reset();
            vol->calculate_convex_hull();
            vol->invalidate_convex_hull_2d();
            obj.invalidate_bounding_box();
            commit();
            committed = true;
            sculpt_build(oi, ii, vi);
            m_sculpt->refined_to = edge;
            sculpt_copies();
        }
    }
    SculptSession &ss = *m_sculpt;
    // A new stroke (or a rebuilt session) starts its heights from zero.
    if (new_stroke || ss.layer.stamp.size() != ss.mesh.vertices.size())
        ss.layer.begin(ss.mesh.vertices.size());
    const Transform3d to_local = ss.frame.inverse();

    // The surface under a ray now: tree hits on faces that haven't moved, moved faces one by one.
    // The hit point and its face's vertex nearest to it (the brush's seed).
    struct Hit {
        Vec3d point;
        int seed;
        int face;
    };
    auto hit_of = [&](const Vec3d &o, const Vec3d &d) -> std::optional<Hit> {
        double best = std::numeric_limits<double>::max();
        int face = -1;
        std::vector<igl::Hit<float>> hits;
        AABBTreeIndirect::intersect_ray_all_hits(ss.base->its.vertices, ss.base->its.indices, ss.tree, o, d, hits, ss.eps);
        for (const auto &h : hits)
            if (!ss.touched[size_t(h.id)]) {
                best = h.t;
                face = h.id;
                break;
            }
        for (int f : ss.touched_list) {
            const auto &tri = ss.mesh.faces[size_t(f)];
            const double t = ray_triangle(o, d, ss.mesh.vertices[tri[0]], ss.mesh.vertices[tri[1]], ss.mesh.vertices[tri[2]]);
            if (t > 0. && t < best) {
                best = t;
                face = f;
            }
        }
        if (face < 0)
            return std::nullopt;
        const Vec3d p = o + d * best;
        int seed = -1;
        double near = std::numeric_limits<double>::max();
        for (int v : ss.mesh.faces[size_t(face)]) {
            const auto &q = ss.mesh.vertices[size_t(v)];
            const double d2 = (Vec3d(q.x, q.y, q.z) - p).squaredNorm();
            if (d2 < near) {
                near = d2;
                seed = v;
            }
        }
        return Hit{p, seed, face};
    };

    ss.vstamp.resize(ss.mesh.vertices.size(), 0);
    ss.fstamp.resize(ss.mesh.faces.size(), 0);
    if (++ss.stamp == 0) {
        std::fill(ss.vstamp.begin(), ss.vstamp.end(), 0);
        std::fill(ss.fstamp.begin(), ss.fstamp.end(), 0);
        ss.stamp = 1;
    }
    std::vector<int> moved, dab_moved;
    bool any_hit = false;
    // The brush cursor: the last dab's centre and surface normal (volume coordinates).
    Vec3d cursor_point = Vec3d::Zero(), cursor_normal = Vec3d::UnitZ();
    int cursor_face = -1;
    for (const auto &r : rays) {
        const Vec3d o = to_local * Vec3d(r[0], r[1], r[2]);
        const Vec3d d = (to_local.linear() * Vec3d(r[3], r[4], r[5])).normalized();
        dab_moved.clear();
        if (probe) {
            if (const auto p = hit_of(o, d)) {
                any_hit = true;
                cursor_point = p->point;
                cursor_face = p->face;
            }
            continue;
        }
        if (kind == meshsculpt::Brush::Grab) {
            // Grab: the vertices under the first hit follow the finger on the plane through that hit facing the view.
            if (!ss.grabbing) {
                const auto p = hit_of(o, d);
                if (!p)
                    continue;
                any_hit = true;
                ss.grabbing = true;
                ss.grab_anchor = p->point;
                ss.grab_normal = -d;
                cursor_point = p->point;
                cursor_normal = -d;
                cursor_face = -1;
                ss.grab_verts.clear();
                ss.grab_w.clear();
                ss.grab_orig.clear();
                // The vertices a zero-strength dab would take (joined to the hit), with its falloff.
                meshsculpt::Stroke probe;
                probe.brush = meshsculpt::Brush::Grab;
                probe.center = {p->point.x(), p->point.y(), p->point.z()};
                probe.radius = local_radius;
                probe.seed = p->seed;
                meshsculpt::dab(ss.mesh, ss.topo, probe, &ss.grab_verts);
                for (int v : ss.grab_verts) {
                    const auto &q = ss.mesh.vertices[size_t(v)];
                    const double t = 1. - (Vec3d(q.x, q.y, q.z) - p->point).squaredNorm() / (local_radius * local_radius);
                    ss.grab_w.push_back(t * t);
                    ss.grab_orig.push_back(q);
                }
                continue;
            }
            const double den = d.dot(ss.grab_normal);
            if (std::abs(den) < 1e-9)
                continue;
            const double t = (ss.grab_anchor - o).dot(ss.grab_normal) / den;
            if (!(t > 0.))
                continue;
            any_hit = true;
            cursor_point = o + d * t;
            cursor_normal = ss.grab_normal;
            cursor_face = -1;
            const Vec3d delta = o + d * t - ss.grab_anchor;
            for (size_t k = 0; k < ss.grab_verts.size(); ++k) {
                const auto &v0 = ss.grab_orig[k];
                const double w = ss.grab_w[k];
                ss.mesh.vertices[size_t(ss.grab_verts[k])] = {v0.x + delta.x() * w, v0.y + delta.y() * w, v0.z + delta.z() * w};
            }
            dab_moved = ss.grab_verts;
        } else {
            const auto p = hit_of(o, d);
            if (!p)
                continue;
            any_hit = true;
            cursor_point = p->point;
            cursor_face = p->face;
            meshsculpt::Stroke st;
            st.brush = kind;
            st.center = {p->point.x(), p->point.y(), p->point.z()};
            st.seed = p->seed;
            st.layer = &ss.layer;
            st.radius = local_radius;
            st.strength = std::clamp(strength, 0., 1.);
            st.invert = invert;
            meshsculpt::dab(ss.mesh, ss.topo, st, &dab_moved);
        }
        // The next dab's normals and hits follow the surface just made.
        meshsculpt::update_normals(ss.mesh, ss.topo, dab_moved);
        for (int v : dab_moved) {
            if (ss.vstamp[size_t(v)] != ss.stamp) {
                ss.vstamp[size_t(v)] = ss.stamp;
                moved.push_back(v);
            }
            for (int j = ss.topo.fstart[v]; j < ss.topo.fstart[v + 1]; ++j) {
                const int f = ss.topo.faces[size_t(j)];
                if (!ss.touched[size_t(f)]) {
                    ss.touched[size_t(f)] = 1;
                    ss.touched_list.push_back(f);
                }
            }
        }
    }

    // The changed faces, in order, as runs per copy (small gaps are sent along, so a dab is a few uploads, not hundreds).
    std::vector<int> faces;
    for (int v : moved)
        for (int j = ss.topo.fstart[v]; j < ss.topo.fstart[v + 1]; ++j) {
            const int f = ss.topo.faces[size_t(j)];
            if (ss.fstamp[size_t(f)] != ss.stamp) {
                ss.fstamp[size_t(f)] = ss.stamp;
                faces.push_back(f);
            }
        }
    std::sort(faces.begin(), faces.end());
    if (!moved.empty())
        ss.dirty = true;

    std::string patch;
    if (!faces.empty() && !end) {
        std::vector<std::pair<int, int>> runs; // [first, last]
        for (int f : faces) {
            if (!runs.empty() && f - runs.back().second <= 16)
                runs.back().second = f;
            else
                runs.push_back({f, f});
        }
        // Binary, native order: int32 run count; per run int32 first triangle (whole scene), int32 count, then per
        // triangle 3 x (position, normal, id, type) floats as in write_mesh().
        patch = m_mesh_path + ".patch";
        std::ofstream out(patch, std::ios::binary | std::ios::trunc);
        if (!out)
            throw std::runtime_error("Cannot write " + patch);
        auto put = [&out](auto v) { out.write(reinterpret_cast<const char *>(&v), sizeof(v)); };
        put(int32_t(runs.size() * ss.copies.size()));
        for (const auto &c : ss.copies)
            for (const auto &[a, b] : runs) {
                put(int32_t(c.first + size_t(a)));
                put(int32_t(b - a + 1));
                for (int f = a; f <= b; ++f) {
                    const auto &tri = ss.mesh.faces[size_t(f)];
                    Vec3f w[3];
                    for (int k = 0; k < 3; ++k) {
                        const auto &v = ss.mesh.vertices[size_t(tri[k])];
                        w[k] = (c.frame * Vec3d(v.x, v.y, v.z)).cast<float>();
                    }
                    if (c.flip)
                        std::swap(w[1], w[2]);
                    Vec3f n = (w[1] - w[0]).cross(w[2] - w[0]);
                    const float len = n.norm();
                    n = len > 0.f ? Vec3f(n / len) : Vec3f(0.f, 0.f, 1.f);
                    for (const Vec3f &p : w) {
                        for (int k = 0; k < 3; ++k) put(p[k]);
                        for (int k = 0; k < 3; ++k) put(n[k]);
                        put(c.id);
                        put(c.type);
                    }
                }
            }
    }

    if (end) {
        ss.grabbing = false;
        sculpt_flush_locked();
        drop_to_bed(obj);
        return commit();
    }
    json out = committed ? scene_json() : json::object();
    out["patch"] = patch;
    out["mesh_version"] = m_mesh_version;
    out["hit"] = any_hit;
    if (any_hit) {
        // The hit face as the dab left it; normals go to the world by the inverse transpose.
        if (cursor_face >= 0) {
            const auto &tri = ss.mesh.faces[size_t(cursor_face)];
            const auto &a = ss.mesh.vertices[size_t(tri[0])], &b = ss.mesh.vertices[size_t(tri[1])], &c = ss.mesh.vertices[size_t(tri[2])];
            const Vec3d n = Vec3d(b.x - a.x, b.y - a.y, b.z - a.z).cross(Vec3d(c.x - a.x, c.y - a.y, c.z - a.z));
            if (n.squaredNorm() > 0.)
                cursor_normal = n;
        }
        const Vec3d wp = ss.frame * cursor_point;
        Vec3d wn = ss.frame.linear().inverse().transpose() * cursor_normal;
        wn = wn.squaredNorm() > 0. ? Vec3d(wn.normalized()) : Vec3d(Vec3d::UnitZ());
        out["point"] = {wp.x(), wp.y(), wp.z()};
        out["normal"] = {wn.x(), wn.y(), wn.z()};
    }
    return out;
}

} // namespace orca
