// OrcaEngine tools that work on single objects with the current selection: picking, painting,
// variable layer height, per-plate custom G-code / wipe tower, flushing volumes and the process
// option dependency rules.

#include "OrcaEngine.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <functional>
#include <limits>
#include <map>
#include <memory>
#include <set>
#include <stdexcept>
#include <type_traits>
#include <utility>

#include <boost/log/trivial.hpp>
#include <CGAL/Exact_predicates_inexact_constructions_kernel.h>
#include <CGAL/Polygon_mesh_processing/self_intersections.h>
#include <CGAL/Surface_mesh/Surface_mesh.h>

#include "libslic3r/AABBMesh.hpp"
#include "libslic3r/CustomGCode.hpp"
#include "libslic3r/FlushVolCalc.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Slicing.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/TriangleSelector.hpp"

#include "OptionToggles.hpp"
#include "Thumbnails.hpp"

namespace Slic3r { namespace GUI {
ToggleApp &wxGetApp()
{
    static ToggleApp app;
    return app;
}
}} // namespace Slic3r::GUI

namespace orca {

using namespace Slic3r;

namespace {

Vec3d to_vec(const std::array<double, 3> &a) { return Vec3d(a[0], a[1], a[2]); }
json  vec_json(const Vec3d &v) { return {v.x(), v.y(), v.z()}; }

template<class T> void write_raw(std::ofstream &f, const T &v) { f.write(reinterpret_cast<const char *>(&v), sizeof(T)); }

SlicingParameters slicing_params(const DynamicPrintConfig &config, const ModelObject &obj)
{
    const float height = float(obj.instance_bounding_box(0).size().z());
    return PrintObject::slicing_parameters(config, obj, height, Vec3d(1., 1., 1.));
}

} // namespace

// --- Picking and painting ----------------------------------------------------------------------

// The copy's matrix as drawn and picked: [design] = OrcaEngine::design_offset() (the Build workspace's object).
static Transform3d shown_matrix(const ModelInstance &inst, const std::optional<std::array<double, 3>> &design)
{
    return design ? Transform3d(Eigen::Translation3d((*design)[0], (*design)[1], (*design)[2])) : inst.get_matrix();
}

// The measure tool's snap targets on one mesh: which facet edges are sharp (> 25 deg between the facets, or open), the smooth
// patches between them (a CAD face: a flat side, a hole's wall) and, per vertex, its sharp edges (to follow an edge line).
struct OrcaEngine::SnapFeatures
{
    std::vector<Vec3i32> nb;             // per facet, k: the facet across edge k (vertex k to k + 1), -1 = open
    std::vector<uint8_t> sharp;          // per facet, bit k: edge k is sharp
    std::vector<int32_t> patch;          // per facet: its smooth patch
    std::vector<int32_t> vstart, vnext;  // per vertex v: vnext[vstart[v] .. vstart[v + 1]) = the far ends of its sharp edges
    std::map<int32_t, std::vector<float>> outlines; // per patch, as first asked for: its sharp edges (x, y, z per end, mesh coordinates)

    explicit SnapFeatures(const indexed_triangle_set &its)
    {
        const int nf = int(its.indices.size());
        nb = its_face_neighbors(its);
        std::vector<Vec3f> n(nf);
        for (int f = 0; f < nf; ++f)
            n[f] = its_face_normal(its, f).normalized();
        const float limit = std::cos(25.f * float(M_PI) / 180.f);
        sharp.assign(nf, 0);
        for (int f = 0; f < nf; ++f)
            for (int k = 0; k < 3; ++k)
                if (nb[f][k] < 0 || n[f].dot(n[nb[f][k]]) < limit)
                    sharp[f] |= uint8_t(1 << k);
        patch.assign(nf, -1);
        std::vector<int32_t> stack;
        for (int f = 0, next = 0; f < nf; ++f) {
            if (patch[f] >= 0)
                continue;
            patch[f] = next;
            stack.push_back(f);
            while (!stack.empty()) {
                const int c = stack.back();
                stack.pop_back();
                for (int k = 0; k < 3; ++k)
                    if (!((sharp[c] >> k) & 1) && nb[c][k] >= 0 && patch[nb[c][k]] < 0) {
                        patch[nb[c][k]] = next;
                        stack.push_back(nb[c][k]);
                    }
            }
            ++next;
        }
        // Each sharp edge once (from its lower facet; open edges always).
        const auto each_edge = [&](auto &&fn) {
            for (int f = 0; f < nf; ++f)
                for (int k = 0; k < 3; ++k)
                    if (((sharp[f] >> k) & 1) && (nb[f][k] < 0 || f < nb[f][k]))
                        fn(its.indices[f][k], its.indices[f][(k + 1) % 3]);
        };
        const size_t nv = its.vertices.size();
        vstart.assign(nv + 1, 0);
        each_edge([&](int a, int b) { ++vstart[a + 1]; ++vstart[b + 1]; });
        for (size_t v = 0; v < nv; ++v)
            vstart[v + 1] += vstart[v];
        vnext.resize(vstart[nv]);
        std::vector<int32_t> fill(vstart.begin(), vstart.end() - 1);
        each_edge([&](int a, int b) { vnext[fill[a]++] = b; vnext[fill[b]++] = a; });
    }

    // Where the edge line coming from prev goes on after v: its straightest sharp edge there, if it turns under 50 deg
    // (-1: the line ends, as at a box corner). It carries on through a junction (a crease meeting the line, a noisy mesh's spur).
    int32_t next(const indexed_triangle_set &its, int32_t prev, int32_t v) const
    {
        const Vec3f d0 = (its.vertices[v] - its.vertices[prev]).normalized();
        int32_t best = -1;
        float   best_dot = std::cos(50.f * float(M_PI) / 180.f);
        for (int32_t i = vstart[v]; i < vstart[v + 1]; ++i) {
            const int32_t w = vnext[i];
            if (w == prev)
                continue;
            const float c = d0.dot((its.vertices[w] - its.vertices[v]).normalized());
            if (c >= best_dot) { best_dot = c; best = w; }
        }
        return best;
    }

    // The edge line through the sharp edge a -> b: followed both ways (see next()).
    // [closed]: it came back round to a (a hole's rim).
    std::vector<int32_t> line(const indexed_triangle_set &its, int32_t a, int32_t b, bool &closed) const
    {
        std::set<int32_t> seen{a, b};
        const auto walk = [&](std::vector<int32_t> &run) {
            while (run.size() < 4096) {
                const int32_t nx = next(its, run[run.size() - 2], run.back());
                if (nx < 0)
                    return false;
                if (nx == run.front())
                    return true;
                if (!seen.insert(nx).second)
                    return false;
                run.push_back(nx);
            }
            return false;
        };
        std::vector<int32_t> ahead{a, b};
        closed = walk(ahead);
        if (closed)
            return ahead;
        std::vector<int32_t> behind{b, a};
        walk(behind);
        std::vector<int32_t> out(behind.rbegin(), behind.rend() - 2);
        out.insert(out.end(), ahead.begin(), ahead.end());
        return out;
    }

    // What the snap caught (the hit on facet [face]; an edge snap: on the sharp edge ea -> eb), for the measure tool to show (world line segments, x, y, z per end):
    // "vertex" (no lines), "edge" (a straight line), "ring" (a round closed line: a hole's rim, with its centre and diameter),
    // "curve" (any other edge line) or "face" (no snap: the outline of the smooth patch the point lies on).
    json feature(const indexed_triangle_set &its, const Transform3d &trafo, int face, const char *kind, int32_t ea, int32_t eb)
    {
        std::vector<float> lines;
        const auto add = [&](const Vec3f &local) {
            const Vec3d p = trafo * local.cast<double>();
            lines.insert(lines.end(), {float(p.x()), float(p.y()), float(p.z())});
        };
        json out;
        if (kind && std::string(kind) == "vertex") {
            out["kind"] = "vertex";
        } else if (kind) {
            bool closed = false;
            const std::vector<int32_t> run = line(its, ea, eb, closed);
            std::vector<Vec3d> pts;
            for (int32_t v : run)
                pts.push_back(trafo * its.vertices[v].cast<double>());
            for (size_t i = 0; i + 1 < run.size() + (closed ? 1 : 0); ++i) {
                add(its.vertices[run[i]]);
                add(its.vertices[run[(i + 1) % run.size()]]);
            }
            out["kind"] = "curve";
            if (closed && pts.size() >= 6) {
                Vec3d c = Vec3d::Zero();
                for (const Vec3d &q : pts)
                    c += q;
                c /= double(pts.size());
                double lo = std::numeric_limits<double>::max(), hi = 0., sum = 0.;
                for (const Vec3d &q : pts) {
                    const double r = (q - c).norm();
                    lo = std::min(lo, r); hi = std::max(hi, r); sum += r;
                }
                const double mean = sum / double(pts.size());
                if (hi - lo <= 0.1 * mean) {
                    out["kind"]     = "ring";
                    out["center"]   = vec_json(c);
                    out["diameter"] = 2. * mean;
                }
            } else if (!closed) {
                // Straight: every point within 1 % of the length (at least 0.02 mm) of the line between the ends.
                const Vec3d  a = pts.front(), e = pts.back() - a;
                const double len = e.norm(), tol = std::max(0.02, 0.01 * len);
                bool straight = len > 0.;
                for (const Vec3d &q : pts)
                    straight = straight && ((q - a) - (q - a).dot(e) / (len * len) * e).norm() <= tol;
                if (straight)
                    out["kind"] = "edge";
            }
        } else {
            out["kind"] = "face";
            const int32_t id = patch[face];
            auto it = outlines.find(id);
            if (it == outlines.end()) {
                std::vector<float> &o = outlines[id];
                for (size_t f = 0; f < patch.size(); ++f)
                    if (patch[f] == id)
                        for (int k = 0; k < 3; ++k)
                            if ((sharp[f] >> k) & 1)
                                for (int v : {its.indices[f][k], its.indices[f][(k + 1) % 3]})
                                    o.insert(o.end(), {its.vertices[v].x(), its.vertices[v].y(), its.vertices[v].z()});
                // Too busy to read (a scan's whole skin): no outline.
                if (o.size() > 6 * 20000)
                    o.clear();
                it = outlines.find(id);
            }
            for (size_t i = 0; i + 2 < it->second.size(); i += 3)
                add(Vec3f(it->second[i], it->second[i + 1], it->second[i + 2]));
        }
        out["lines"] = lines;
        return out;
    }
};

json OrcaEngine::pick(const std::array<double, 3> &origin, const std::array<double, 3> &dir, int only, double snap, double release,
                      const std::array<double, 3> &stick, const std::array<double, 3> &seed_origin, const std::array<double, 3> &seed_dir)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Vec3d s = to_vec(origin), d = to_vec(dir).normalized();
    json best = {{"hit", false}};
    double best_dist = std::numeric_limits<double>::max();
    const ModelVolume *best_vol = nullptr;
    Transform3d        best_trafo;
    int                best_face = -1;
    Vec3d              best_world = Vec3d::Zero();
    const auto cast = [&](const Vec3d &rs, const Vec3d &rd) {
        for (size_t oi = 0; oi < m_model->objects.size(); ++oi) {
            if (only >= 0 && int(oi) != only)
                continue;
            const ModelObject *obj = m_model->objects[oi];
            for (size_t ii = 0; ii < obj->instances.size(); ++ii)
                for (size_t vi = 0; vi < obj->volumes.size(); ++vi) {
                    const ModelVolume *vol = obj->volumes[vi];
                    const Transform3d trafo = shown_matrix(*obj->instances[ii], design_offset(oi, ii)) * vol->get_matrix();
                    const Transform3d inv   = trafo.inverse();
                    // Ray in volume coordinates; the hit distance is measured back in world space.
                    const Vec3d ls = inv * rs;
                    const Vec3d ld = (inv.linear() * rd).normalized();
                    std::shared_ptr<AABBMesh> &mesh = m_aabb[&vol->mesh()];
                    if (!mesh)
                        mesh = std::make_shared<AABBMesh>(vol->mesh());
                    const auto hit = mesh->query_ray_hit(ls, ld);
                    if (!hit.is_hit())
                        continue;
                    const Vec3d world = trafo * hit.position();
                    const double dist = (world - rs).norm();
                    if (dist >= best_dist)
                        continue;
                    best_dist  = dist;
                    best_vol   = vol;
                    best_trafo = trafo;
                    best_face  = int(hit.face());
                    best_world = world;
                    const Vec3d n = (trafo.linear().inverse().transpose() * hit.normal()).normalized();
                    best = {{"hit", true},
                            {"object", oi},
                            {"instance", ii},
                            {"volume", vi},
                            {"facet", hit.face()},
                            {"point", vec_json(world)},
                            {"normal", vec_json(n)},
                            {"local", vec_json(hit.position())}};
                }
        }
    };
    cast(s, d);
    // Aimed just off the model: the seed ray (one that hits, within the capture ring) gives the facet to look round, as
    // long as the snap catches an edge or corner of it; no point on that face otherwise.
    const Vec3d seed_s = to_vec(seed_origin), seed_d = to_vec(seed_dir);
    const bool  seeded = !best_vol && snap > 0. && !std::isnan(seed_s.x()) && !std::isnan(seed_d.x());
    if (seeded)
        cast(seed_s, seed_d.normalized());
    if (snap > 0. && best_vol) {
        // Corners within the snap distance, else the nearest sharp edge: looked for at the vertices of the facets within
        // reach (from the hit facet out), as a fine mesh's rim can be a few facets away. Distances are taken from the
        // pick ray, as on screen (a face seen edge-on is a few pixels but many mm wide); nothing hidden behind the hit.
        // [stick]: the point the last snap caught (a moving aim): that corner or edge holds out to [release] (no flicker).
        const indexed_triangle_set &its = best_vol->mesh().its;
        std::shared_ptr<SnapFeatures> &sf = m_neighbors[&best_vol->mesh()];
        if (!sf)
            sf = std::make_shared<SnapFeatures>(its);
        const Vec3d  p     = best_world;
        const Vec3d  held  = to_vec(stick);
        const bool   holds = !std::isnan(held.x()) && release > snap;
        const double reach = holds ? release : snap;
        const double depth = (p - s).dot(d) + reach;
        const auto at = [&](int32_t v) -> Vec3d { return best_trafo * its.vertices[v].cast<double>(); };
        const auto off_ray = [&](const Vec3d &q) {
            const Vec3d v = q - s;
            return v.dot(d) > depth ? std::numeric_limits<double>::max() : (v - v.dot(d) * d).norm();
        };
        // The point of segment a-b nearest the ray, and its distance from it.
        const auto seg_near = [&](const Vec3d &a, const Vec3d &b) {
            const Vec3d  e = b - a, w0 = a - s;
            const double ed = e.dot(d), den = e.squaredNorm() - ed * ed;
            const double t  = den > 1e-12 ? std::clamp((ed * w0.dot(d) - w0.dot(e)) / den, 0., 1.) : 0.;
            const Vec3d  q  = a + t * e;
            return std::make_pair(off_ray(q), q);
        };
        // Whether [held] lies on segment a-b (the last catch was this corner or a point of this edge).
        const auto on_seg = [&](const Vec3d &a, const Vec3d &b) {
            if (!holds)
                return false;
            const Vec3d  e = b - a;
            const double t = e.squaredNorm() > 1e-12 ? std::clamp((held - a).dot(e) / e.squaredNorm(), 0., 1.) : 0.;
            return (a + t * e - held).norm() < 1e-3;
        };
        // How far (world mm, up to [lim]) the edge line runs from v through w; [end]: where it got to.
        const auto run = [&](int32_t v, int32_t w, double lim, Vec3d &end) {
            double len = 0.;
            for (int32_t prev = v, n = 0;; ++n) {
                len += (at(w) - at(prev)).norm();
                const int32_t nx = len < lim && n < 512 ? sf->next(its, prev, w) : -1;
                if (nx < 0 || nx == v)
                    break;
                prev = w;
                w    = nx;
            }
            end = at(w);
            return len;
        };
        // Lines shorter than the snap distance (a few pixels on screen: a noisy mesh's creases) are no snap targets.
        const auto long_line = [&](int32_t a, int32_t b) {
            Vec3d e;
            return run(a, b, snap, e) + run(b, a, snap, e) - (at(b) - at(a)).norm() >= snap;
        };
        // A corner ends long lines: three or more, or two that turn 50 deg or more (not a hole's rim or a curve).
        const auto corner = [&](int32_t v) {
            std::vector<Vec3d> arms;
            for (int32_t i = sf->vstart[v]; i < sf->vstart[v + 1]; ++i) {
                Vec3d e;
                if (run(v, sf->vnext[i], snap, e) >= snap)
                    arms.push_back((e - at(v)).normalized());
            }
            return arms.size() >= 3 || (arms.size() == 2 && arms[0].dot(arms[1]) > -std::cos(50. * M_PI / 180.));
        };
        std::vector<int32_t> verts;
        {
            std::vector<int32_t> stack{best_face};
            std::set<int32_t>    seen{best_face};
            while (!stack.empty() && seen.size() < 4096) {
                const int32_t f = stack.back();
                stack.pop_back();
                for (int k = 0; k < 3; ++k) {
                    verts.push_back(its.indices[f][k]);
                    const int32_t g = sf->nb[f][k];
                    if (g >= 0 && !seen.count(g) && seg_near(at(its.indices[f][k]), at(its.indices[f][(k + 1) % 3])).first <= reach) {
                        seen.insert(g);
                        stack.push_back(g);
                    }
                }
            }
            std::sort(verts.begin(), verts.end());
            verts.erase(std::unique(verts.begin(), verts.end()), verts.end());
        }
        Vec3d  snapped = p;
        double best_d  = std::numeric_limits<double>::max();
        const char *kind = nullptr;
        int32_t     ea = -1, eb = -1;
        for (int32_t v : verts)
            if (sf->vstart[v + 1] > sf->vstart[v]) {
                const double dist = off_ray(at(v));
                if (dist < best_d && dist < (on_seg(at(v), at(v)) ? release : snap) && corner(v)) { best_d = dist; snapped = at(v); kind = "vertex"; }
            }
        if (!kind)
            for (int32_t v : verts)
                for (int32_t i = sf->vstart[v]; i < sf->vstart[v + 1]; ++i) {
                    const int32_t w = sf->vnext[i];
                    const auto [dist, q] = seg_near(at(v), at(w));
                    if (dist < best_d && dist < (on_seg(at(v), at(w)) ? release : snap) && long_line(v, w)) {
                        best_d = dist; snapped = q; kind = "edge"; ea = v; eb = w;
                    }
                }
        if (seeded && !kind)
            return {{"hit", false}};
        if (kind) {
            best["point"] = vec_json(snapped);
            best["snap"]  = kind;
        }
        best["feature"] = sf->feature(its, best_trafo, best_face, kind, ea, eb);
    }
    return best;
}


namespace {

// The facet annotation of a volume that a paint kind edits.
FacetsAnnotation &facets_of(ModelVolume &vol, const std::string &kind)
{
    if (kind == "fuzzy")
        return vol.fuzzy_skin_facets;
    if (kind == "support")
        return vol.supported_facets;
    if (kind == "seam")
        return vol.seam_facets;
    if (kind == "color")
        return vol.mmu_segmentation_facets;
    throw std::runtime_error("Unknown paint kind: " + kind);
}

} // namespace

void OrcaEngine::write_paint_mesh()
{
    using clock = std::chrono::steady_clock;
    auto ms = [](clock::time_point a) { return std::chrono::duration<double, std::milli>(clock::now() - a).count(); };
    m_paint_extract_ms = m_paint_bytes_ms = 0.;
    m_paint_triangles = 0;
    // File bytes of one copy's painted triangles: reused while the triangles and the copy's transform are the same
    // (written one float at a time, every copy of every painted object, on every paint call before).
    decltype(m_paint_bytes) bytes_used;
    std::vector<std::shared_ptr<std::vector<float>>> parts;
    bool changed = false;
    auto emit = [&](const PaintKey &key, const indexed_triangle_set &its, const Transform3d &trafo, float obj_code, float type_code) {
        const auto bytes_key = std::make_tuple(key, obj_code, type_code);
        std::array<double, 16> matrix;
        std::copy(trafo.matrix().data(), trafo.matrix().data() + 16, matrix.begin());
        auto it = m_paint_bytes.find(bytes_key);
        std::shared_ptr<std::vector<float>> data;
        if (it != m_paint_bytes.end() && it->second.trafo == matrix) {
            data = it->second.data;
        } else {
            const auto t = clock::now();
            data = std::make_shared<std::vector<float>>();
            data->reserve(its.indices.size() * 24);
            // A mirroring transform flips the winding; swap two corners so the triangles face out.
            const bool mirrored = trafo.matrix().block<3, 3>(0, 0).determinant() < 0.;
            for (const Vec3i32 &tri : its.indices) {
                const Vec3f a = (trafo * its.vertices[tri[0]].cast<double>()).cast<float>();
                const Vec3f b = (trafo * its.vertices[tri[mirrored ? 2 : 1]].cast<double>()).cast<float>();
                const Vec3f c = (trafo * its.vertices[tri[mirrored ? 1 : 2]].cast<double>()).cast<float>();
                Vec3f n = (b - a).cross(c - a);
                n = n.norm() > 0.f ? Vec3f(n.normalized()) : Vec3f(0.f, 0.f, 1.f);
                for (const Vec3f *v : {&a, &b, &c})
                    data->insert(data->end(), {(*v)[0], (*v)[1], (*v)[2], n[0], n[1], n[2], obj_code, type_code});
            }
            m_paint_bytes_ms += ms(t);
            changed = true;
        }
        bytes_used[bytes_key] = {matrix, data};
        m_paint_triangles += its.indices.size();
        parts.push_back(data);
    };
    const int max_filament = std::max<int>(1, int(m_sel_filaments.size()));
    // Painted triangles of one annotation state: from the cache, else from the live paint selector, else from one
    // selector per annotation (FacetsAnnotation::get_facets deserialized the whole annotation again for every state).
    decltype(m_paint_facets) used;
    const TriangleSelector *loaded = nullptr;
    std::unique_ptr<TriangleSelector> own;
    auto facets_for = [&](const PaintKey &key, const ModelVolume &vol, const FacetsAnnotation &annotation, EnforcerBlockerType state) -> const indexed_triangle_set & {
        auto it = m_paint_facets.find(key);
        std::shared_ptr<indexed_triangle_set> its;
        const LivePaint &live = m_live_paint;
        const bool is_live = live.selector && live.annotation == &annotation && live.timestamp == annotation.timestamp() &&
                             live.mesh.get() == &vol.mesh();
        if (it != m_paint_facets.end()) {
            its = it->second;
        } else if (auto before = m_paint_facets.find(PaintKey{&vol.mesh(), live.previous, int(state)});
                   is_live && int(state) != live.state && before != m_paint_facets.end() && before->second->indices.empty()) {
            its = before->second;
        } else {
            const auto t = clock::now();
            const TriangleSelector *selector = loaded;
            if (is_live) {
                selector = live.selector.get();
            } else if (selector == nullptr) {
                own = std::make_unique<TriangleSelector>(vol.mesh());
                own->deserialize(annotation.get_data(), false);
                selector = loaded = own.get();
            }
            its = std::make_shared<indexed_triangle_set>(selector->get_facets(state));
            m_paint_extract_ms += ms(t);
        }
        used[key] = its;
        return *its;
    };
    for (size_t oi = 0; oi < m_model->objects.size(); ++oi) {
        const ModelObject *obj = m_model->objects[oi];
        for (size_t vi = 0; vi < obj->volumes.size(); ++vi) {
            const ModelVolume *vol = obj->volumes[vi];
            // (annotation, state, overlay type code)
            std::vector<std::tuple<const FacetsAnnotation *, EnforcerBlockerType, float>> layers;
            if (!vol->fuzzy_skin_facets.empty())
                layers.emplace_back(&vol->fuzzy_skin_facets, EnforcerBlockerType::FUZZY_SKIN, 5.f);
            if (!vol->supported_facets.empty()) {
                layers.emplace_back(&vol->supported_facets, EnforcerBlockerType::ENFORCER, 6.f);
                layers.emplace_back(&vol->supported_facets, EnforcerBlockerType::BLOCKER, 7.f);
            }
            if (!vol->seam_facets.empty()) {
                layers.emplace_back(&vol->seam_facets, EnforcerBlockerType::ENFORCER, 8.f);
                layers.emplace_back(&vol->seam_facets, EnforcerBlockerType::BLOCKER, 9.f);
            }
            if (!vol->mmu_segmentation_facets.empty())
                for (int e = 1; e <= max_filament; ++e)
                    layers.emplace_back(&vol->mmu_segmentation_facets, EnforcerBlockerType(e), 10.f + float(e - 1));
            const FacetsAnnotation *previous = nullptr;
            for (const auto &[annotation, state, code] : layers) {
                if (annotation != previous) {
                    // A new annotation: the selector loaded for the previous one doesn't apply.
                    loaded = nullptr;
                    previous = annotation;
                }
                const PaintKey key{&vol->mesh(), uint64_t(annotation->timestamp()), int(state)};
                const indexed_triangle_set &painted = facets_for(key, *vol, *annotation, state);
                if (painted.indices.empty())
                    continue;
                // The Build workspace's object: the scene mesh's per-volume ids (write_mesh), so its paint shows in the solo view.
                for (size_t ii = 0; ii < obj->instances.size(); ++ii)
                    emit(key, painted, shown_matrix(*obj->instances[ii], design_offset(oi, ii)) * vol->get_matrix(),
                         int(oi) == m_build_object ? -1000.f - float(vi) : float(oi * INSTANCE_ID_STRIDE + ii), code);
            }
        }
    }
    m_paint_facets = std::move(used);
    // The same parts as last time (a stroke over paint already there): the file and the app's copy stay as they are,
    // since rewriting and re-reading ~10 MB took ~60 ms a call on a well-painted model (r320).
    changed = changed || bytes_used.size() != m_paint_bytes.size() || m_paint_version == 0;
    m_paint_bytes = std::move(bytes_used);
    if (!changed)
        return;
    // Written next to the target and renamed over it, so the app never reads a half-written file.
    const std::string tmp = m_paint_path + ".tmp";
    std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
    if (!f)
        throw std::runtime_error("Cannot write " + m_paint_path);
    for (const auto &data : parts)
        f.write(reinterpret_cast<const char *>(data->data()), std::streamsize(data->size() * sizeof(float)));
    f.close();
    if (std::rename(tmp.c_str(), m_paint_path.c_str()) != 0)
        throw std::runtime_error("Cannot write " + m_paint_path);
    ++m_paint_version;
}

// Faces reachable from [seed] over edges between faces whose normals differ by at most [angle] degrees: the desktop's
// smart fill test (TriangleSelector::seed_fill_select_triangles, neighbour against neighbour).
// [allowed]: faces the flood may enter (all when empty).
std::vector<bool> flood_surface(const indexed_triangle_set &its, const std::vector<int> &neighbors, int seed, double angle,
                                const std::function<bool(int)> &allowed)
{
    std::vector<bool> faces(its.indices.size(), false);
    if (seed < 0 || seed >= int(faces.size()))
        return faces;
    const double limit = std::cos(angle * M_PI / 180.) - EPSILON;
    std::vector<int> queue{seed};
    faces[seed] = true;
    while (!queue.empty()) {
        const int f = queue.back();
        queue.pop_back();
        const Vec3f n = its_face_normal(its, f).normalized();
        for (int k = 0; k < 3; ++k) {
            const int g = neighbors[3 * f + k];
            if (g >= 0 && !faces[g] && std::clamp(n.dot(its_face_normal(its, g).normalized()), 0.f, 1.f) >= limit && (!allowed || allowed(g))) {
                faces[g] = true;
                queue.push_back(g);
            }
        }
    }
    return faces;
}

namespace {

// The paint shop's layer lock by paint state (bit i of [locked] = filament i + 1); NONE = unpainted, locked with the part's
// own filament.
using FilamentLocks = std::array<bool, size_t(EnforcerBlockerType::ExtruderMax) + 1>;
FilamentLocks filament_locks(uint32_t locked, const ModelVolume &vol)
{
    FilamentLocks locks{};
    for (size_t s = 1; s < locks.size(); ++s)
        locks[s] = s <= 32 && (locked >> (s - 1) & 1u);
    locks[0] = locks[std::min<size_t>(std::max(1, vol.extruder_id()), locks.size() - 1)];
    return locks;
}

// The desktop's gap fill (TriangleSelectorPatch::update_triangles_per_patch / update_selector_triangles): a patch is a
// connected run of leaf triangles in one state; a patch under [max_area] mm2 takes the state that borders it most.
class GapFiller : public TriangleSelector
{
public:
    using TriangleSelector::TriangleSelector;

    // [locked] (by state, may be null): patches in those states stay.
    bool fill(float max_area, const FilamentLocks *locked = nullptr)
    {
        auto [neighbors, neighbors_propagated] = precompute_all_neighbors();
        std::vector<bool> visited(m_triangles.size(), false);
        struct Patch
        {
            std::vector<int> facets;
            std::map<EnforcerBlockerType, int> neighbor_types; // state -> touching triangles
            double area = 0.;
        };
        std::vector<Patch> patches;
        for (size_t start = 0; start < m_triangles.size(); ++start) {
            if (visited[start] || !m_triangles[start].valid() || m_triangles[start].is_split())
                continue;
            const EnforcerBlockerType state = m_triangles[start].get_state();
            Patch patch;
            std::vector<int> queue{int(start)};
            visited[start] = true;
            while (!queue.empty()) {
                const int f = queue.back();
                queue.pop_back();
                const Triangle &t = m_triangles[f];
                patch.facets.push_back(f);
                const stl_vertex &v0 = m_vertices[t.verts_idxs[0]].v;
                patch.area += double((v0 - m_vertices[t.verts_idxs[1]].v).cross(v0 - m_vertices[t.verts_idxs[2]].v).norm()) / 2.;
                std::vector<int> touching;
                const Vec3i32 &nb = neighbors[f];
                append_touching_subtriangles(nb(0), t.verts_idxs[1], t.verts_idxs[0], touching);
                append_touching_subtriangles(nb(1), t.verts_idxs[2], t.verts_idxs[1], touching);
                append_touching_subtriangles(nb(2), t.verts_idxs[0], t.verts_idxs[2], touching);
                for (int k = 0; k < 3; ++k) {
                    const int n = neighbors_propagated[f](k);
                    if (n != -1 && !m_triangles[n].is_split())
                        touching.push_back(n);
                }
                for (int g : touching) {
                    if (g < 0)
                        continue;
                    if (m_triangles[g].get_state() != state)
                        ++patch.neighbor_types[m_triangles[g].get_state()];
                    else if (!visited[g]) {
                        visited[g] = true;
                        queue.push_back(g);
                    }
                }
            }
            patches.push_back(std::move(patch));
        }
        bool changed = false;
        for (const Patch &patch : patches) {
            if (patch.area >= max_area || patch.neighbor_types.empty() ||
                (locked && (*locked)[size_t(m_triangles[patch.facets.front()].get_state())]))
                continue;
            // The surrounding state: the one touching the patch most (a speck on a border joins the longer side).
            const auto most = std::max_element(patch.neighbor_types.begin(), patch.neighbor_types.end(),
                                               [](const auto &a, const auto &b) { return a.second < b.second; });
            for (int f : patch.facets)
                m_triangles[f].set_state(most->first);
            changed = true;
        }
        return changed;
    }
};

// The state under a point of one original facet: walks down the facet's splits to the leaf triangle nearest the point.
class StateProbe : public TriangleSelector
{
public:
    using TriangleSelector::TriangleSelector;

    EnforcerBlockerType state_at(int facet, const Vec3f &p) const
    {
        int t = facet;
        while (m_triangles[t].is_split()) {
            const Triangle &tri = m_triangles[t];
            int best = tri.children[0];
            float best_d = FLT_MAX;
            for (int c = 0; c <= tri.number_of_split_sides(); ++c) {
                const float d = distance(m_triangles[tri.children[c]], p);
                if (d < best_d) {
                    best_d = d;
                    best = tri.children[c];
                }
            }
            t = best;
        }
        return m_triangles[t].get_state();
    }

private:
    // How far p lies outside the triangle (0 inside; children share the facet's plane, so in-plane distance decides).
    float distance(const Triangle &t, const Vec3f &p) const
    {
        const Vec3f &a = m_vertices[t.verts_idxs[0]].v, &b = m_vertices[t.verts_idxs[1]].v, &c = m_vertices[t.verts_idxs[2]].v;
        const Vec3f n = (b - a).cross(c - a);
        float d = 0.f;
        const Vec3f *corners[4] = {&a, &b, &c, &a};
        for (int k = 0; k < 3; ++k) {
            const Vec3f &u = *corners[k], &v = *corners[k + 1];
            const float side = (v - u).cross(p - u).dot(n);
            if (side < 0.f)
                d = std::max(d, -side / std::max(n.norm() * (v - u).norm(), 1e-12f));
        }
        return d;
    }
};

// The paint selector, with cursors kept off some triangles (Guarded).
class PaintSelector : public StateProbe
{
public:
    using StateProbe::StateProbe;
    using Locks = FilamentLocks;

    // A cursor that stays on the original facets in [faces] (edge detection; the flood and, since a height range seeds on
    // every facet in its band, each triangle) and off the triangles in [locks] states (the paint shop's layer lock: the
    // paint stops exactly at their borders; a whole locked facet stops the flood like an edge). Either may be null.
    template<class Base> class Guarded : public Base
    {
    public:
        template<class... Args>
        Guarded(const PaintSelector &selector, const std::vector<bool> *faces, const Locks *locks, Args &&...args)
            : Base(std::forward<Args>(args)...), m_selector(selector), m_faces(faces), m_locks(locks) {}
        bool is_facet_visible(int facet_idx, const std::vector<Vec3f> &face_normals) const override
        {
            return (!m_faces || (*m_faces)[facet_idx]) && Base::is_facet_visible(facet_idx, face_normals);
        }
        // Called first for every triangle the selection visits (select_triangle_recursive): a kept-off triangle is outside the
        // cursor; a split triangle holding a locked one isn't wholly inside, so the selection goes down to its children.
        int vertices_inside(const Triangle &tr, const std::vector<Vertex> &vertices) const override
        {
            m_skip = off(tr);
            if (m_skip)
                return 0;
            const int inside = Base::vertices_inside(tr, vertices);
            return inside == 3 && m_locks && tr.is_split() && m_selector.holds_locked(tr, *m_locks) ? 2 : inside;
        }
        bool is_pointer_in_triangle(const Vec3f &p1, const Vec3f &p2, const Vec3f &p3) const override
        {
            return !m_skip && Base::is_pointer_in_triangle(p1, p2, p3);
        }
        bool is_edge_inside_cursor(const Triangle &tr, const std::vector<Vertex> &vertices) const override
        {
            return !off(tr) && Base::is_edge_inside_cursor(tr, vertices);
        }
        bool claims_leaf(const Triangle &tr, const std::vector<Vertex> &vertices) const override
        {
            return !off(tr) && Base::claims_leaf(tr, vertices);
        }

    private:
        bool off(const Triangle &tr) const
        {
            return (m_faces && !(*m_faces)[tr.source_triangle]) || (m_locks && !tr.is_split() && (*m_locks)[size_t(tr.get_state())]);
        }
        const PaintSelector     &m_selector;
        const std::vector<bool> *m_faces;
        const Locks             *m_locks;
        mutable bool             m_skip = false;
    };

    bool holds_locked(const Triangle &tr, const Locks &locks) const
    {
        if (!tr.is_split())
            return locks[size_t(tr.get_state())];
        for (int c = 0; c <= tr.number_of_split_sides(); ++c)
            if (holds_locked(m_triangles[tr.children[c]], locks))
                return true;
        return false;
    }
};

// A guarded fill's selector: puts back the states a spare state stood in for, from the selector before the stroke.
class FillSelector : public PaintSelector
{
public:
    using PaintSelector::PaintSelector;

    void restore(EnforcerBlockerType spare, const StateProbe &before)
    {
        for (Triangle &t : m_triangles)
            if (t.valid() && !t.is_split() && t.get_state() == spare) {
                const Vec3f c = (m_vertices[t.verts_idxs[0]].v + m_vertices[t.verts_idxs[1]].v + m_vertices[t.verts_idxs[2]].v) / 3.f;
                t.set_state(before.state_at(t.source_triangle, c));
            }
    }
};

} // namespace

json OrcaEngine::paint_state_at(int object, int volume, int facet, const std::array<double, 3> &local, const std::string &kind)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    if (volume < 0 || volume >= int(obj.volumes.size()))
        throw std::runtime_error("Invalid volume");
    ModelVolume &vol = *obj.volumes[volume];
    const FacetsAnnotation &facets = facets_of(vol, kind);
    int state = 0;
    if (!facets.empty() && facet >= 0 && facet < int(vol.mesh().its.indices.size())) {
        StateProbe probe(vol.mesh());
        probe.deserialize(facets.get_data(), false);
        state = int(probe.state_at(facet, to_vec(local).cast<float>()));
    }
    return {{"state", state}, {"base", vol.extruder_id()}};
}

json OrcaEngine::crease_lines(int object, int instance, double angle)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    const double limit = std::cos(angle * M_PI / 180.);
    std::vector<float> lines;
    for (const ModelVolume *vol : obj.volumes) {
        if (!vol->is_model_part())
            continue;
        const Transform3d trafo = obj.instances[instance]->get_matrix() * vol->get_matrix();
        const indexed_triangle_set &its = vol->mesh().its;
        const std::vector<Vec3i32> neighbors = its_face_neighbors(its);
        for (int f = 0; f < int(its.indices.size()); ++f)
            for (int k = 0; k < 3; ++k) {
                const int g = neighbors[f][k];
                // Each shared edge once (from its lower face); open edges always.
                if (g >= 0 && (g < f || its_face_normal(its, f).normalized().dot(its_face_normal(its, g).normalized()) >= limit))
                    continue;
                const Vec2i32 e = its_triangle_edge(its.indices[f], k);
                for (int v : {e[0], e[1]}) {
                    const Vec3d p = trafo * its.vertices[v].cast<double>();
                    lines.insert(lines.end(), {float(p.x()), float(p.y()), float(p.z())});
                }
            }
    }
    return {{"lines", lines}};
}

json OrcaEngine::paint_by_part(int object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    // Every separate shell of every part, largest surface first: filament 1, 2, ... (round again past the last).
    struct Shell { ModelVolume *vol; std::vector<int> faces; double area = 0.; };
    std::vector<Shell> shells;
    for (ModelVolume *vol : obj.volumes) {
        if (!vol->is_model_part())
            continue;
        const indexed_triangle_set &its = vol->mesh().its;
        const std::vector<Vec3i32> neighbors = its_face_neighbors(its);
        std::vector<bool> seen(its.indices.size(), false);
        for (size_t start = 0; start < its.indices.size(); ++start) {
            if (seen[start])
                continue;
            Shell shell{vol};
            std::vector<int> queue{int(start)};
            seen[start] = true;
            while (!queue.empty()) {
                const int f = queue.back();
                queue.pop_back();
                shell.faces.push_back(f);
                const Vec3f &a = its.vertices[its.indices[f][0]];
                shell.area += double((its.vertices[its.indices[f][1]] - a).cross(its.vertices[its.indices[f][2]] - a).norm()) / 2.;
                for (int k = 0; k < 3; ++k)
                    if (const int n = neighbors[f][k]; n >= 0 && !seen[n]) {
                        seen[n] = true;
                        queue.push_back(n);
                    }
            }
            shells.push_back(std::move(shell));
        }
    }
    if (shells.size() < 2)
        return scene_json();
    std::stable_sort(shells.begin(), shells.end(), [](const Shell &a, const Shell &b) { return a.area > b.area; });
    push_undo();
    const int filaments = std::max<int>(1, int(m_sel_filaments.size()));
    std::map<ModelVolume *, std::unique_ptr<TriangleSelector>> selectors;
    for (size_t k = 0; k < shells.size(); ++k) {
        std::unique_ptr<TriangleSelector> &sel = selectors[shells[k].vol];
        if (!sel)
            sel = std::make_unique<TriangleSelector>(shells[k].vol->mesh());
        const auto state = EnforcerBlockerType(int(k % size_t(filaments)) + 1);
        for (int f : shells[k].faces)
            sel->set_facet(f, state);
    }
    for (auto &[vol, sel] : selectors)
        vol->mmu_segmentation_facets.set(*sel);
    write_mesh();
    write_paint_mesh();
    return scene_json();
}

struct OrcaEngine::FillPreview
{
    ModelVolume                                    *volume = nullptr;
    std::string                                     kind;
    TriangleSelector::TriangleSplittingData before;
    // m_undo's top when the preview was made, and whether that top is the stroke's own step.
    std::shared_ptr<Model>                          undo_top;
    bool                                            own_step = false;
};

void OrcaEngine::drop_stale_fill_preview()
{
    const std::shared_ptr<Model> top = m_undo.empty() ? nullptr : m_undo.back().model;
    if (m_fill_preview && m_fill_preview->undo_top != top)
        m_fill_preview.reset();
}

json OrcaEngine::paint(const std::vector<PaintHit> &hits, const std::array<double, 3> &camera, double radius, const std::string &kind,
                       int state, bool new_stroke, const std::vector<double> &section, double edge_angle,
                       const std::string &shape, double height, double overhang, double guard, bool over_colours, uint32_t locked)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (const PaintHit &h : hits) {
        ModelObject &obj = object_at(h.object);
        if (h.instance < 0 || h.instance >= int(obj.instances.size()) || h.volume < 0 || h.volume >= int(obj.volumes.size()))
            throw std::runtime_error("Invalid paint target");
        // A pick made before the mesh changed (simplify, repair, split) can name a facet it no longer has; the
        // selectors index their triangles by facet.
        if (h.facet >= int(obj.volumes[h.volume]->mesh().its.indices.size()))
            throw std::runtime_error("Invalid paint target");
    }
    const auto t0 = std::chrono::steady_clock::now();
    int loads = 0;
    drop_stale_fill_preview();
    if (new_stroke && !hits.empty())
        push_undo();
    if (new_stroke) {
        m_paint_prev.reset();
        m_band.reset();
        m_paint_region.object = -1;
        m_fill_preview.reset();
        m_stroke_undo = hits.empty() ? nullptr : m_undo.back().model;
    }

    // Guard plane: a horizontal plane at [guard] mm the paint doesn't cross. A stroke keeps to the side it started on, a
    // fill to the side under the finger. It takes the section plane's place (a selector clips by one plane).
    std::vector<double> clip_plane = section;
    if (std::isfinite(guard) && !hits.empty()) {
        if (new_stroke || shape == "fill") {
            const PaintHit &h = shape == "fill" ? hits.back() : hits.front();
            const ModelObject &obj = object_at(h.object);
            m_guard_below = (obj.instances[h.instance]->get_matrix() * obj.volumes[h.volume]->get_matrix() * to_vec(h.local)).z() < guard;
        }
        clip_plane = m_guard_below ? std::vector<double>{0., 0., 1., guard} : std::vector<double>{0., 0., -1., -guard};
    }
    const EnforcerBlockerType new_state = EnforcerBlockerType(std::clamp(state, 0, int(EnforcerBlockerType::ExtruderMax)));
    // Layer lock (colour only): the volume's locked states, kept here while its cursor paints.
    const bool locking = kind == "color" && locked != 0;
    FilamentLocks locks{};
    auto locks_of = [&](const ModelVolume &vol) -> const FilamentLocks & { return locks = filament_locks(locked, vol); };
    // Colour painting that starts or ends a volume's painting changes whether its plate gets a prime tower,
    // which is part of the scene mesh (write_mesh): the tower showed only after the next move (WSA r324).
    bool tower_changed = false;
    // The live selector of a volume's annotation, loaded again when the annotation changed elsewhere.
    auto live_selector = [&](ModelVolume &vol, FacetsAnnotation &facets) -> PaintSelector & {
        LivePaint &live = m_live_paint;
        if (!live.selector || live.annotation != &facets || live.timestamp != facets.timestamp() || live.mesh.get() != &vol.mesh()) {
            live = {&facets, vol.mesh_ptr(), 0, std::make_shared<PaintSelector>(vol.mesh())};
            live.selector->deserialize(facets.get_data(), false);
            ++loads;
        }
        // Always made here (the only place that sets it).
        return static_cast<PaintSelector &>(*live.selector);
    };
    // Saves the live selector into its annotation.
    auto save_live = [&](FacetsAnnotation &facets, bool was_empty) {
        LivePaint &live = m_live_paint;
        live.previous = facets.timestamp();
        live.state = int(new_state);
        facets.set(*live.selector);
        live.timestamp = facets.timestamp();
        if (kind == "color" && was_empty != facets.empty())
            tower_changed = true;
    };
    // The section plane in volume coordinates, as the desktop's get_clipping_plane_in_volume_coordinates:
    // n.(T q) <= d  <=>  (L^T n).q <= d - n.t.
    auto clip_of = [&](const Transform3d &trafo) {
        TriangleSelector::ClippingPlane clip;
        if (clip_plane.size() == 4) {
            const Vec3d n(clip_plane[0], clip_plane[1], clip_plane[2]);
            const Vec3d n_local = trafo.linear().transpose() * n;
            clip = TriangleSelector::ClippingPlane({float(n_local.x()), float(n_local.y()), float(n_local.z()),
                                                    float(clip_plane[3] - n.dot(trafo.translation()))});
        }
        return clip;
    };
    auto hit_z = [&](const PaintHit &h) {
        const ModelObject &obj = object_at(h.object);
        return float((obj.instances[h.instance]->get_matrix() * obj.volumes[h.volume]->get_matrix() * to_vec(h.local)).z());
    };

    // Fill: only the stroke's latest hit counts. It fills from the target's state before the stroke, so moving the finger
    // moves the fill (a preview: lifting keeps it, paint_fill_cancel drops it). Unchanged result = nothing rewritten.
    bool fill_changed = false;
    if (shape == "fill" && !hits.empty() && hits.back().facet >= 0) {
        const PaintHit &h = hits.back();
        ModelObject &obj = object_at(h.object);
        ModelVolume *vol = obj.volumes[h.volume];
        FacetsAnnotation &facets = facets_of(*vol, kind);
        if (m_fill_preview && (m_fill_preview->volume != vol || m_fill_preview->kind != kind)) {
            // Another part: it goes back to how it was, the fill moves here.
            FacetsAnnotation &old = facets_of(*m_fill_preview->volume, m_fill_preview->kind);
            if (old.get_data() != m_fill_preview->before) {
                const bool was_empty = old.empty();
                old.set_data(TriangleSelector::TriangleSplittingData(m_fill_preview->before));
                tower_changed |= m_fill_preview->kind == "color" && was_empty != old.empty();
                fill_changed = true;
            }
            m_fill_preview.reset();
        }
        if (!m_fill_preview) {
            const std::shared_ptr<Model> top = m_undo.empty() ? nullptr : m_undo.back().model;
            m_fill_preview   = std::make_shared<FillPreview>(FillPreview{vol, kind, facets.get_data(), top, top && top == m_stroke_undo});
        }
        const Transform3d trafo = obj.instances[h.instance]->get_matrix() * vol->get_matrix();
        FillSelector selector(vol->mesh());
        selector.deserialize(m_fill_preview->before, false);
        ++loads;
        Transform3d trafo_no_translate = trafo;
        trafo_no_translate.translation() = Vec3d::Zero();
        const Vec3f camera_local = (trafo.inverse() * to_vec(camera)).cast<float>();
        const indexed_triangle_set &its = vol->mesh().its;
        PaintRegion &region = m_paint_region;
        if (region.mesh.get() != &vol->mesh()) {
            region.mesh = vol->mesh_ptr();
            region.neighbors.clear();
            for (const Vec3i32 &n : its_face_neighbors(its))
                region.neighbors.insert(region.neighbors.end(), {n.x(), n.y(), n.z()});
            region.object = -1;
        }
        // Faces with a corner on the kept side of the guard / section plane, and with one beyond it.
        auto reaches = [&](int f, bool kept_side) {
            const Vec3d n(clip_plane[0], clip_plane[1], clip_plane[2]);
            for (int k = 0; k < 3; ++k)
                if ((n.dot(trafo * its.vertices[its.indices[f][k]].cast<double>()) <= clip_plane[3]) == kept_side)
                    return true;
            return false;
        };
        std::function<bool(int)> kept;
        if (clip_plane.size() == 4)
            kept = [&](int f) { return reaches(f, true); };
        const bool guarded = std::isfinite(guard);
        // The guard's two sides as height bands: HeightRange splits the triangles at the band's ends, at the plane.
        constexpr float FAR = 1e5f;
        const float kept_z = m_guard_below ? float(guard) - FAR : float(guard), beyond_z = m_guard_below ? float(guard) : float(guard) - FAR;
        const PaintSelector::Locks *fill_locks = locking ? &locks_of(*vol) : nullptr;
        if (fill_locks && !over_colours && (*fill_locks)[size_t(selector.state_at(h.facet, to_vec(h.local).cast<float>()))]) {
            // A fill of a locked colour: nothing (the target goes back to how it was before the stroke).
        } else if (over_colours) {
            // Fill over colours: the faces up to the edge angle (else the whole shell) whatever their colour; with a guard,
            // only their part on its side. Locked colours stay (the height range seeds on every facet, so the fill goes round them).
            const std::vector<bool> faces = flood_surface(its, region.neighbors, h.facet, edge_angle > 0. ? edge_angle : 180., kept);
            if (guarded || fill_locks)
                selector.select_patch(h.facet, std::make_unique<PaintSelector::Guarded<TriangleSelector::HeightRange>>(
                                                   selector, &faces, fill_locks, guarded ? kept_z : -FAR, camera_local, guarded ? FAR : 2.f * FAR, trafo,
                                                   TriangleSelector::ClippingPlane()),
                                      new_state, trafo_no_translate, true);
            else
                for (int f = 0; f < int(faces.size()); ++f)
                    if (faces[f])
                        selector.set_facet(f, new_state);
        } else if (guarded) {
            // The desktop's fill (same colour only) cut at the guard: the far side takes a spare state first, which splits
            // the triangles at the plane and stops the fill there; then the far side gets its own states back.
            const auto spare = EnforcerBlockerType::ExtruderMax;
            std::vector<bool> done(its.indices.size(), false);
            for (int f = 0; f < int(its.indices.size()); ++f) {
                if (done[f] || !reaches(f, false))
                    continue;
                // One seed per connected run of faces reaching beyond (select_patch floods from its seed).
                const std::vector<bool> run = flood_surface(its, region.neighbors, f, 180., [&](int g) { return reaches(g, false); });
                for (size_t g = 0; g < run.size(); ++g)
                    done[g] = done[g] || run[g];
                selector.select_patch(f, std::make_unique<TriangleSelector::HeightRange>(beyond_z, camera_local, FAR, trafo, TriangleSelector::ClippingPlane()),
                                      spare, trafo_no_translate, true);
            }
            selector.bucket_fill_select_triangles(to_vec(h.local).cast<float>(), h.facet, TriangleSelector::ClippingPlane(),
                                                  edge_angle > 0. ? float(edge_angle) : -1.f, true, true);
            selector.seed_fill_apply_on_triangles(new_state);
            StateProbe before(vol->mesh());
            before.deserialize(m_fill_preview->before, false);
            selector.restore(spare, before);
        } else {
            selector.bucket_fill_select_triangles(to_vec(h.local).cast<float>(), h.facet, clip_of(trafo), edge_angle > 0. ? float(edge_angle) : -1.f,
                                                  true, true);
            selector.seed_fill_apply_on_triangles(new_state);
        }
        TriangleSelector::TriangleSplittingData data = selector.serialize();
        if (data != facets.get_data()) {
            const bool was_empty = facets.empty();
            facets.set_data(std::move(data));
            tower_changed |= kind == "color" && was_empty != facets.empty();
            fill_changed = true;
        }
        m_paint_prev = h;
    }
    if (shape == "height") {
        // Height range (GLGizmoPainterBase::get_projected_height_range): the band from the hit up, on every part of the object.
        // A stroke is one band: each hit extends the stroke's range, and a call that grew it paints the whole range once. A
        // band per hit cost ~8 ms each, and every band refines its two edges to 0.1 mm, so a fast drag left gaps and piled
        // up splits (user r405: "very slow and choppy"). Painting only the new strips still left each old edge's splits
        // behind (177k -> 462k paint triangles); a triangle wholly inside the range loses its splits (select_triangle).
        auto paint_band = [&](const BandStroke &band) {
            ModelObject &obj = object_at(band.object);
            for (size_t vi = 0; vi < obj.volumes.size(); ++vi) {
                ModelVolume *vol = obj.volumes[vi];
                if (!vol->is_model_part())
                    continue;
                const Transform3d trafo = obj.instances[band.instance]->get_matrix() * vol->get_matrix();
                const indexed_triangle_set &its = vol->mesh().its;
                // Parts with no facet reaching into the range stay untouched. The range cursor floods from every facet in
                // its range (TriangleSelector::select_patch), so the seed is only a valid index.
                const bool reaches = std::any_of(its.indices.begin(), its.indices.end(), [&](const stl_triangle_vertex_indices &t) {
                    float a = FLT_MAX, b = -FLT_MAX;
                    for (int k = 0; k < 3; ++k) {
                        const float vz = float((trafo * its.vertices[t[k]].cast<double>()).z());
                        a = std::min(a, vz);
                        b = std::max(b, vz);
                    }
                    return b >= band.lo && a <= band.hi;
                });
                if (!reaches)
                    continue;
                FacetsAnnotation &facets = facets_of(*vol, kind);
                const bool was_empty = facets.empty();
                Transform3d trafo_no_translate = trafo;
                trafo_no_translate.translation() = Vec3d::Zero();
                const Vec3f camera_local = (trafo.inverse() * to_vec(camera)).cast<float>();
                PaintSelector &selector = live_selector(*vol, facets);
                const float lo = band.lo, h = band.hi - band.lo;
                selector.select_patch(0, locking ? std::unique_ptr<TriangleSelector::Cursor>(std::make_unique<PaintSelector::Guarded<TriangleSelector::HeightRange>>(
                                                       selector, nullptr, &locks_of(*vol), lo, camera_local, h, trafo, clip_of(trafo)))
                                                 : TriangleSelector::SinglePointCursor::cursor_factory(lo, camera_local, h, trafo, clip_of(trafo)),
                                      new_state, trafo_no_translate, true);
                save_live(facets, was_empty);
            }
        };
        // Snap the band to the object's layer boundaries (outward): an edge between layers slices to a clean line, one inside
        // a layer leaves jagged triangles on the slicer (user r406: 0.08 mm layers, 0.1 mm band).
        auto snap = [&](const PaintHit &h, float z, float top) {
            const ModelObject &obj = object_at(h.object);
            const SlicingParameters params = slicing_params(selection_config(), obj);
            std::vector<coordf_t> profile = obj.layer_height_profile.get();
            if (profile.empty())
                PrintObject::update_layer_height_profile(obj, params, profile);
            const std::vector<coordf_t> tops = generate_object_layers(params, profile, false);
            std::vector<float> edges{0.f};
            for (size_t i = 1; i < tops.size(); i += 2)
                edges.push_back(float(tops[i]));
            const float eps = 1e-3f;
            // The start is the bottom of the layer clicked. Rounding to the nearest edge (r409, against clicks in the old-colour
            // teeth along a band's edge, now claimed by the bands) made the upper half of a one-band gap repaint the band
            // above (user r414: "does not fill in the area until i reach the red line"). The end snaps outward from the
            // band's own height above the start.
            auto it_n = std::upper_bound(edges.begin(), edges.end(), z + eps);
            const float lo = it_n == edges.begin() ? edges.front() : *(it_n - 1);
            auto it_hi = std::lower_bound(edges.begin(), edges.end(), lo + (top - z) - eps);
            float hi = it_hi == edges.end() ? lo + (top - z) : *it_hi;
            if (hi <= lo + eps)
                hi = it_hi == edges.end() || it_hi + 1 == edges.end() ? lo + (top - z) : *(it_hi + 1);
            return std::make_pair(lo, hi);
        };
        bool grew = false;
        for (const PaintHit &h : hits) {
            const auto [z, top] = snap(h, hit_z(h), hit_z(h) + float(height));
            if (m_band && (m_band->object != h.object || m_band->instance != h.instance)) {
                if (grew)
                    paint_band(*m_band);
                m_band.reset();
            }
            if (!m_band) {
                m_band = BandStroke{h.object, h.instance, z, top};
                grew   = true;
            } else if (z < m_band->lo || top > m_band->hi) {
                m_band->lo = std::min(m_band->lo, z);
                m_band->hi = std::max(m_band->hi, top);
                grew       = true;
            }
        }
        if (m_band && grew)
            paint_band(*m_band);
        if (!hits.empty())
            m_paint_prev = hits.back();
    }
    const bool brush = shape != "height" && shape != "triangle" && shape != "fill";
    const TriangleSelector::CursorType cursor_type = shape == "circle" ? TriangleSelector::CursorType::CIRCLE : TriangleSelector::CursorType::SPHERE;
    // Runs of hits on the same volume copy share one selector: deserialized once, saved once.
    for (size_t i = shape == "height" || shape == "fill" ? hits.size() : 0; i < hits.size();) {
        const PaintHit &first = hits[i];
        ModelObject &obj = object_at(first.object);
        ModelVolume *vol = obj.volumes[first.volume];
        FacetsAnnotation &facets = facets_of(*vol, kind);
        const bool was_empty = facets.empty();

        const Transform3d trafo = obj.instances[first.instance]->get_matrix() * vol->get_matrix();
        Transform3d trafo_no_translate = trafo;
        trafo_no_translate.translation() = Vec3d::Zero();
        const Vec3f camera_local = (trafo.inverse() * to_vec(camera)).cast<float>();
        const TriangleSelector::ClippingPlane clip = clip_of(trafo);
        PaintSelector &selector = live_selector(*vol, facets);
        const PaintSelector::Locks *vol_locks = locking ? &locks_of(*vol) : nullptr;
        // Edge detection: the stroke's surface on this volume, from its first hit there.
        PaintRegion &region = m_paint_region;
        const bool limited = brush && edge_angle > 0.;
        if (limited && (region.object != first.object || region.volume != first.volume || region.mesh.get() != &vol->mesh())) {
            if (region.mesh.get() != &vol->mesh()) {
                region.mesh = vol->mesh_ptr();
                region.neighbors.clear();
                for (const Vec3i32 &n : its_face_neighbors(vol->mesh().its))
                    region.neighbors.insert(region.neighbors.end(), {n.x(), n.y(), n.z()});
            }
            region.object = first.object;
            region.volume = first.volume;
            region.faces  = flood_surface(vol->mesh().its, region.neighbors, first.facet, edge_angle);
        }
        for (; i < hits.size() && hits[i].object == first.object && hits[i].instance == first.instance && hits[i].volume == first.volume; ++i) {
            const PaintHit &h = hits[i];
            const Vec3f center = to_vec(h.local).cast<float>();
            if (!brush) {
                // As GLGizmoPainterBase::gizmo_event: select the hit triangle / its same-state region, then paint it. A fill
                // paints once per line: dragging on over the filled region would only repaint it.
                if (h.facet < 0 || (shape == "fill" && h.join) || (vol_locks && (*vol_locks)[size_t(selector.state_at(h.facet, center))]))
                    continue;
                selector.bucket_fill_select_triangles(center, h.facet, clip, shape == "fill" && edge_angle > 0. ? float(edge_angle) : -1.f,
                                                      shape == "fill", true);
                selector.seed_fill_apply_on_triangles(new_state);
                m_paint_prev = h;
                continue;
            }
            // Off the stroke's surface: nothing to paint; the line goes on from the last hit on it.
            if (limited && (h.facet < 0 || h.facet >= int(region.faces.size()) || !region.faces[h.facet]))
                continue;
            // A fast stroke's hits are far apart: join them with a capsule (GLGizmoPainterBase::gizmo_event) so the line
            // has no gaps; a hit after a miss or on another part starts a new line.
            const PaintHit *prev = h.join && m_paint_prev && m_paint_prev->object == h.object && m_paint_prev->instance == h.instance &&
                                   m_paint_prev->volume == h.volume ? &*m_paint_prev : nullptr;
            std::unique_ptr<TriangleSelector::Cursor> cursor;
            // The factories' cursor types, kept to the stroke's surface / off locked colours.
            auto guarded = [&](auto *type, auto &&...args) -> std::unique_ptr<TriangleSelector::Cursor> {
                return std::make_unique<PaintSelector::Guarded<std::remove_pointer_t<decltype(type)>>>(selector, limited ? &region.faces : nullptr,
                                                                                                       vol_locks, args...);
            };
            const bool circle = cursor_type == TriangleSelector::CursorType::CIRCLE;
            if ((limited || vol_locks) && prev && circle)
                cursor = guarded((TriangleSelector::Capsule2D *) nullptr, to_vec(prev->local).cast<float>(), center, camera_local, float(radius), trafo, clip);
            else if ((limited || vol_locks) && prev)
                cursor = guarded((TriangleSelector::Capsule3D *) nullptr, to_vec(prev->local).cast<float>(), center, camera_local, float(radius), trafo, clip);
            else if ((limited || vol_locks) && circle)
                cursor = guarded((TriangleSelector::Circle *) nullptr, center, camera_local, float(radius), trafo, clip);
            else if (limited || vol_locks)
                cursor = guarded((TriangleSelector::Sphere *) nullptr, center, camera_local, float(radius), trafo, clip);
            else if (prev)
                cursor = TriangleSelector::DoublePointCursor::cursor_factory(to_vec(prev->local).cast<float>(), center, camera_local, float(radius),
                                                                             cursor_type, trafo, clip);
            else
                cursor = TriangleSelector::SinglePointCursor::cursor_factory(center, camera_local, float(radius), cursor_type, trafo, clip);
            selector.select_patch(prev ? prev->facet : h.facet, std::move(cursor), new_state, trafo_no_translate, true, float(overhang));
            m_paint_prev = h;
        }
        save_live(facets, was_empty);
    }
    if (tower_changed)
        write_mesh();
    const auto t1 = std::chrono::steady_clock::now();
    if (shape != "fill" || fill_changed)
        write_paint_mesh();
    const auto t2 = std::chrono::steady_clock::now();
    json out = scene_json();
    const auto t3 = std::chrono::steady_clock::now();
    auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    if (ms(t0, t3) > 30.)
        BOOST_LOG_TRIVIAL(warning) << "paint: " << hits.size() << " hits, " << loads << " selector loads, select " << ms(t0, t1)
                                << " ms, paint mesh " << ms(t1, t2) << " ms (extract " << m_paint_extract_ms << ", bytes "
                                << m_paint_bytes_ms << ", " << m_paint_triangles << " triangles), scene " << ms(t2, t3) << " ms";
    return out;
}

json OrcaEngine::paint_fill_cancel()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    // A preview another edit came after is kept as painted (its step stays in the history with that edit's on top).
    drop_stale_fill_preview();
    if (m_fill_preview) {
        FacetsAnnotation &facets = facets_of(*m_fill_preview->volume, m_fill_preview->kind);
        const bool was_empty = facets.empty();
        facets.set_data(std::move(m_fill_preview->before));
        // The stroke's undo step would undo nothing now. The top is still that step (drop_stale_fill_preview).
        if (m_fill_preview->own_step)
            m_undo.pop_back();
        if (m_fill_preview->kind == "color" && was_empty != facets.empty())
            write_mesh();
        m_fill_preview.reset();
        m_paint_prev.reset();
        write_paint_mesh();
    }
    return scene_json();
}

bool OrcaEngine::paint_fill_live()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    drop_stale_fill_preview();
    return m_fill_preview != nullptr;
}

json OrcaEngine::paint_clear(int object, const std::string &kind)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    bool tower_changed = false;
    for (ModelVolume *vol : obj.volumes) {
        if ((kind.empty() || kind == "color") && !vol->mmu_segmentation_facets.empty())
            tower_changed = true;
        if (kind.empty()) {
            for (const char *k : {"fuzzy", "support", "seam", "color"})
                facets_of(*vol, k).reset();
        } else {
            facets_of(*vol, kind).reset();
        }
    }
    if (tower_changed)
        write_mesh();
    write_paint_mesh();
    return scene_json();
}

json OrcaEngine::paint_gap_fill(int object, const std::string &kind, double area, uint32_t locked)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    bool pushed = false, tower_changed = false;
    for (ModelVolume *vol : obj.volumes) {
        FacetsAnnotation &facets = facets_of(*vol, kind);
        if (facets.empty())
            continue;
        GapFiller filler(vol->mesh());
        filler.deserialize(facets.get_data(), false);
        const FilamentLocks locks = filament_locks(locked, *vol);
        if (!filler.fill(float(area), kind == "color" && locked != 0 ? &locks : nullptr))
            continue;
        if (!pushed)
            push_undo();
        pushed = true;
        facets.set(filler);
        if (kind == "color" && facets.empty())
            tower_changed = true;
    }
    if (tower_changed)
        write_mesh();
    write_paint_mesh();
    return scene_json();
}

// The desktop's "Remap filaments" (GLGizmoMmuSegmentation::remap_filament_assignments): [dest][i] is the 0-based filament
// that painted filament i becomes. Colour painting only; [base] (the paint shop's "Turn into") also moves the unpainted
// faces: parts whose own filament is remapped get the new one.
json OrcaEngine::paint_remap(int object, const std::vector<int> &dest, bool base)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    constexpr int max_state = int(EnforcerBlockerType::ExtruderMax);
    EnforcerBlockerStateMap state_map;
    for (int i = 0; i <= max_state; ++i)
        state_map[i] = EnforcerBlockerType(i);
    const int first = int(EnforcerBlockerType::Extruder1);
    bool any = false;
    for (int src = 0; src < int(dest.size()) && src + first <= max_state; ++src) {
        const int dst = dest[src];
        if (dst == src || dst < 0 || dst + first > max_state)
            continue;
        state_map[src + first] = EnforcerBlockerType(dst + first);
        any = true;
    }
    if (!any)
        return scene_json();
    bool pushed = false;
    for (ModelVolume *vol : obj.volumes) {
        const int own = std::max(1, vol->extruder_id()) - 1;
        const bool move_base = base && vol->is_model_part() && own < int(dest.size()) && dest[own] != own && dest[own] >= 0;
        if (vol->mmu_segmentation_facets.empty() && !move_base)
            continue;
        if (!pushed)
            push_undo();
        pushed = true;
        if (!vol->mmu_segmentation_facets.empty())
            vol->mmu_segmentation_facets.remap_states(*vol, state_map);
        if (move_base)
            vol->config.set_key_value("extruder", new ConfigOptionInt(dest[own] + 1));
    }
    if (pushed) {
        write_mesh();
        write_paint_mesh();
    }
    return scene_json();
}

// The paint shop's layer bars: printed surface (mm2, first instance's scale) per filament, unpainted faces counted as
// their part's filament. area[i] = filament i + 1.
json OrcaEngine::paint_coverage(int object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const ModelObject &obj = object_at(object);
    const int filaments = std::max<int>(1, int(m_sel_filaments.size()));
    std::vector<double> area(filaments, 0.);
    const Transform3d inst = obj.instances.empty() ? Transform3d::Identity() : obj.instances.front()->get_matrix();
    for (const ModelVolume *vol : obj.volumes) {
        if (!vol->is_model_part())
            continue;
        const Transform3d trafo = inst * vol->get_matrix();
        auto add = [&](const indexed_triangle_set &its, int filament) {
            if (filament < 1 || filament > filaments)
                return;
            double sum = 0.;
            for (const stl_triangle_vertex_indices &f : its.indices) {
                const Vec3d a = trafo * its.vertices[f[0]].cast<double>();
                sum += (trafo * its.vertices[f[1]].cast<double>() - a).cross(trafo * its.vertices[f[2]].cast<double>() - a).norm() / 2.;
            }
            area[filament - 1] += sum;
        };
        const int base = std::max(1, vol->extruder_id());
        if (vol->mmu_segmentation_facets.empty()) {
            add(vol->mesh().its, base);
            continue;
        }
        TriangleSelector selector(vol->mesh());
        selector.deserialize(vol->mmu_segmentation_facets.get_data(), false);
        add(selector.get_facets(EnforcerBlockerType::NONE), base);
        for (int i = 1; i <= filaments; ++i)
            add(selector.get_facets(EnforcerBlockerType(int(EnforcerBlockerType::Extruder1) + i - 1)), i);
    }
    return {{"area", area}};
}

// --- Variable layer height -----------------------------------------------------------------------

json OrcaEngine::layer_result(ModelObject &obj)
{
    const SlicingParameters params = slicing_params(selection_config(), obj);
    std::vector<coordf_t> profile = obj.layer_height_profile.get();
    if (profile.empty())
        PrintObject::update_layer_height_profile(obj, params, profile);
    json out = commit();
    out["profile"] = profile;
    out["min"]     = params.min_layer_height;
    out["max"]     = params.max_layer_height;
    out["height"]  = params.object_print_z_height();
    return out;
}

json OrcaEngine::layer_profile(int object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return layer_result(object_at(object));
}

json OrcaEngine::layer_adaptive(int object, double quality)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    const SlicingParameters params = slicing_params(selection_config(), obj);
    obj.layer_height_profile.set(layer_height_profile_adaptive(params, obj, float(std::clamp(quality, 0., 1.))));
    return layer_result(obj);
}

json OrcaEngine::layer_smooth(int object, int radius, bool keep_min)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    const SlicingParameters params = slicing_params(selection_config(), obj);
    std::vector<coordf_t> profile = obj.layer_height_profile.get();
    if (profile.empty())
        PrintObject::update_layer_height_profile(obj, params, profile);
    obj.layer_height_profile.set(smooth_height_profile(profile, params, HeightProfileSmoothingParams(unsigned(std::max(1, radius)), keep_min)));
    return layer_result(obj);
}

json OrcaEngine::layer_adjust(int object, double z, double delta, double band)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    const SlicingParameters params = slicing_params(selection_config(), obj);
    std::vector<coordf_t> profile = obj.layer_height_profile.get();
    if (profile.empty())
        PrintObject::update_layer_height_profile(obj, params, profile);
    adjust_layer_height_profile(obj, params, profile, z, std::abs(delta), band,
                                delta >= 0 ? LAYER_HEIGHT_EDIT_ACTION_INCREASE : LAYER_HEIGHT_EDIT_ACTION_DECREASE);
    obj.layer_height_profile.set(profile);
    return layer_result(obj);
}

json OrcaEngine::layer_reset(int object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ModelObject &obj = object_at(object);
    push_undo();
    obj.layer_height_profile.clear();
    return layer_result(obj);
}

// --- Plate extras ----------------------------------------------------------------------------------

json OrcaEngine::set_layer_gcodes(int plate, const json &items)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (plate < 0 || plate >= int(m_plates.size()))
        throw std::runtime_error("Invalid plate");
    push_undo();
    CustomGCode::Info info;
    info.mode = m_sel_filaments.size() > 1 ? CustomGCode::MultiAsSingle : CustomGCode::SingleExtruder;
    for (const json &it : items) {
        CustomGCode::Item item;
        item.print_z  = it.at("z").get<double>();
        const std::string type = it.value("type", "pause");
        // OrcaSlicer no longer emits ColorChange (see ProcessLayer::emit_custom_gcode_per_print_z): a
        // colour change is a change to another filament slot, which runs change_filament_gcode.
        item.type     = type == "color" ? CustomGCode::ToolChange : type == "custom" ? CustomGCode::Custom : CustomGCode::PausePrint;
        item.extruder = it.value("extruder", 1);
        item.color    = it.value("color", std::string());
        item.extra    = it.value("extra", std::string());
        info.gcodes.push_back(item);
    }
    std::sort(info.gcodes.begin(), info.gcodes.end());
    if (info.gcodes.empty())
        m_model->plates_custom_gcodes.erase(plate);
    else
        m_model->plates_custom_gcodes[plate] = info;
    return commit();
}

json OrcaEngine::set_wipe_tower(int plate, double x, double y)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (plate < 0 || plate >= int(m_plates.size()))
        throw std::runtime_error("Invalid plate");
    push_undo();
    PlateInfo &p = m_plates[plate];
    p.has_wipe_tower_pos = std::isfinite(x) && std::isfinite(y);
    p.wipe_tower_x = p.has_wipe_tower_pos ? x : 0.;
    p.wipe_tower_y = p.has_wipe_tower_pos ? y : 0.;
    return commit();
}

std::vector<double> OrcaEngine::auto_flush_matrix(const DynamicPrintConfig &config)
{
    const auto  *colours = config.option<ConfigOptionStrings>("filament_colour");
    const size_t n       = colours ? colours->values.size() : 0;
    int min_volume = 0;
    if (const auto *vol = config.option<ConfigOptionFloatsNullable>("nozzle_volume"); vol && !vol->values.empty())
        min_volume = int(vol->get_at(0));
    int dataset = 0;
    if (const auto *ds = config.option<ConfigOptionIntsNullable>("nozzle_flush_dataset"); ds && !ds->values.empty())
        dataset = ds->values.front();

    std::vector<double> matrix(n * n, 0.);
    FlushVolCalculator  calc(min_volume, g_max_flush_volume, dataset);
    auto byte = [](float v) { return (unsigned char)std::clamp(int(std::lround(v * 255.f)), 0, 255); };
    for (size_t from = 0; from < n; ++from)
        for (size_t to = 0; to < n; ++to) {
            if (from == to)
                continue;
            const auto a = parse_color(colours->values[from]), b = parse_color(colours->values[to]);
            matrix[from * n + to] = calc.calc_flush_vol(255, byte(a[0]), byte(a[1]), byte(a[2]), 255, byte(b[0]), byte(b[1]), byte(b[2]));
        }
    return matrix;
}

json OrcaEngine::flush_matrix()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const std::vector<double> matrix = auto_flush_matrix(selection_config());
    return {{"matrix", matrix}, {"size", size_t(std::lround(std::sqrt(double(matrix.size()))))}};
}

json OrcaEngine::option_states(const json &object)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    DynamicPrintConfig config = selection_config();
    // GUI_ObjectSettings.cpp update_config_values: the object's own settings decide what is greyed out in its editor.
    if (object.is_object())
        for (const auto &[key, value] : object.items())
            if (value.is_string() && config.has(key))
                try { config.set_deserialize_strict(key, value.get<std::string>()); } catch (const std::exception &) {}
    GUI::wxGetApp().preset_bundle = m_bundle.get();
    GUI::ConfigManipulation toggles;
    toggles.is_BBL_Printer = m_bundle->is_bbl_vendor();
    toggles.toggle_print_fff_options(&config, 0, !object.is_object());
    json disabled = json::array(), hidden = json::array();
    for (const auto &[key, enabled] : toggles.field_enabled)
        if (!enabled)
            disabled.push_back(key);
    for (const auto &[key, visible] : toggles.line_visible)
        if (!visible)
            hidden.push_back(key);

    // Printer / filament options the desktop's TabPrinter / TabFilament hide for Bambu Lab printers
    // (and the Bambu-only ones for others). Bambu printers also never see options of other firmware
    // (Klipper, Marlin, pellet and toolchanger printers) or of print hosts: they connect from the
    // Device screen.
    static const char *non_bbl[] = {
        "use_firmware_retraction", "use_relative_e_distances", "support_multi_bed_types", "pellet_modded_printer",
        "bed_mesh_max", "bed_mesh_min", "bed_mesh_probe_distance", "adaptive_bed_mesh_margin", "thumbnails", "thumbnails_format",
        "wipe_tower_type", "enable_filament_ramming", "cooling_tube_retraction", "cooling_tube_length", "parking_pos_retraction",
        "extra_loading_move", "high_current_on_filament_swap", "purge_in_prime_tower", "tool_change_on_wipe_tower",
        "filament_minimal_purge_on_wipe_tower", "filament_loading_speed_start", "filament_loading_speed",
        "filament_unloading_speed_start", "filament_unloading_speed", "filament_toolchange_delay", "filament_cooling_moves",
        "filament_cooling_initial_speed", "filament_cooling_final_speed",
        "gcode_flavor", "accel_to_decel_enable", "accel_to_decel_factor", "exclude_object", "disable_m73",
        "default_junction_deviation", "machine_max_junction_deviation", "pellet_flow_coefficient", "preheat_time", "preheat_steps",
        "host_type", "print_host", "print_host_webui", "printhost_apikey", "printhost_cafile", "printhost_port",
        "printhost_authorization_type", "printhost_user", "printhost_password", "printhost_ssl_ignore_revoke"};
    static const char *bbl_only[] = {"scan_first_layer", "bbl_calib_mark_logo", "bbl_use_printhost"};
    if (toggles.is_BBL_Printer)
        for (const char *key : non_bbl)
            hidden.push_back(key);
    else
        for (const char *key : bbl_only)
            hidden.push_back(key);
    return {{"disabled", disabled}, {"hidden", hidden}};
}


json OrcaEngine::check_mesh(int object, int instance, double thickness)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const ModelObject &obj = object_at(object);
    if (instance < 0 || instance >= int(obj.instances.size()))
        throw std::runtime_error("Invalid instance");
    constexpr size_t max_points = 400;
    json parts = json::array();
    std::vector<float> thin_points, open_points, cross_points, sharp_points;
    size_t thin_total = 0;
    double min_wall = 1e9; // thinnest wall found over all parts (ray distance), and the point midway through it
    Vec3d min_wall_point = Vec3d::Zero();
    for (int v = 0; v < int(obj.volumes.size()); ++v) {
        const ModelVolume *vol = obj.volumes[v];
        if (!vol->is_model_part())
            continue;
        // World frame, so the thickness is in millimetres however the copy is scaled.
        const Transform3d trafo = obj.instances[instance]->get_matrix() * vol->get_matrix();
        indexed_triangle_set its = vol->mesh().its;
        const double side = trafo.linear().determinant() < 0 ? -1. : 1.;
        for (stl_vertex &p : its.vertices)
            p = (trafo * p.cast<double>()).cast<float>();
        json part = {{"volume", v}, {"name", vol->name}, {"faces", its.indices.size()}};
        part["openEdges"] = its_num_open_edges(its);
        part["shells"] = its_number_of_patches(its);
        part["volumeMm3"] = its_volume(its);
        size_t degenerate = 0, thin = 0;
        std::vector<char> skip(its.indices.size(), 0);
        for (size_t f = 0; f < its.indices.size(); ++f) {
            const auto &t = its.indices[f];
            const Vec3f n = (its.vertices[t[1]] - its.vertices[t[0]]).cross(its.vertices[t[2]] - its.vertices[t[0]]);
            if (n.norm() < 1e-9f) { ++degenerate; skip[f] = 1; }
        }
        part["degenerate"] = degenerate;
        // Open edges: an edge used by one face only; its midpoint marks the hole in the view.
        // Sharp edges (print3d_toolbox): two faces folded back on each other (normals more than 160 degrees apart), a knife edge.
        size_t sharp = 0;
        {
            std::map<std::pair<int, int>, std::vector<size_t>> uses;
            for (size_t f = 0; f < its.indices.size(); ++f) {
                const auto &t = its.indices[f];
                for (int k = 0; k < 3; ++k)
                    uses[{std::min(t[k], t[(k + 1) % 3]), std::max(t[k], t[(k + 1) % 3])}].push_back(f);
            }
            auto normal = [&](size_t f) {
                const auto &t = its.indices[f];
                return Vec3f((its.vertices[t[1]] - its.vertices[t[0]]).cross(its.vertices[t[2]] - its.vertices[t[0]])).normalized();
            };
            constexpr float fold_cos = -0.9396926f; // cos(160 degrees)
            for (const auto &[edge, faces] : uses) {
                const Vec3f m = (its.vertices[edge.first] + its.vertices[edge.second]) / 2.f;
                if (faces.size() == 1 && open_points.size() < max_points * 3)
                    open_points.insert(open_points.end(), {m.x(), m.y(), m.z()});
                else if (faces.size() == 2 && !skip[faces[0]] && !skip[faces[1]] && normal(faces[0]).dot(normal(faces[1])) < fold_cos) {
                    ++sharp;
                    if (sharp_points.size() < max_points * 3)
                        sharp_points.insert(sharp_points.end(), {m.x(), m.y(), m.z()});
                }
            }
        }
        part["sharpEdges"] = sharp;
        // Self-intersections: pairs of faces that cut through each other (CGAL); the first face of a pair marks it.
        size_t crossings = 0;
        try {
            using Kernel = CGAL::Exact_predicates_inexact_constructions_kernel;
            CGAL::Surface_mesh<Kernel::Point_3> sm;
            std::vector<CGAL::SM_Vertex_index> vi;
            for (const stl_vertex &p : its.vertices)
                vi.push_back(sm.add_vertex({p.x(), p.y(), p.z()}));
            std::vector<size_t> face_of; // surface-mesh face index -> its face
            for (size_t f = 0; f < its.indices.size(); ++f) {
                if (skip[f])
                    continue;
                const auto &t = its.indices[f];
                if (sm.add_face(vi[t[0]], vi[t[1]], vi[t[2]]) != CGAL::Surface_mesh<Kernel::Point_3>::null_face())
                    face_of.push_back(f);
            }
            std::vector<std::pair<CGAL::SM_Face_index, CGAL::SM_Face_index>> pairs;
            CGAL::Polygon_mesh_processing::self_intersections(sm, std::back_inserter(pairs));
            crossings = pairs.size();
            for (const auto &pr : pairs) {
                if (cross_points.size() >= max_points * 3)
                    break;
                const auto &t = its.indices[face_of[size_t(pr.first)]];
                const Vec3f c = (its.vertices[t[0]] + its.vertices[t[1]] + its.vertices[t[2]]) / 3.f;
                cross_points.insert(cross_points.end(), {c.x(), c.y(), c.z()});
            }
        } catch (const std::exception &e) {
            BOOST_LOG_TRIVIAL(warning) << "check_mesh self-intersections: " << e.what();
        }
        part["intersections"] = crossings;
        // Thin walls: from each face's centre a ray goes just inside; hitting a face of the same mesh within
        // [thickness] means the wall is thinner than that.
        if (thickness > 0. && !its.indices.empty()) {
            const AABBMesh tree(its);
            for (size_t f = 0; f < its.indices.size(); ++f) {
                if (skip[f])
                    continue;
                const auto &t = its.indices[f];
                const Vec3d a = its.vertices[t[0]].cast<double>(), b = its.vertices[t[1]].cast<double>(), c = its.vertices[t[2]].cast<double>();
                const Vec3d n = (b - a).cross(c - a).normalized() * side;
                const Vec3d centre = (a + b + c) / 3.;
                const auto hit = tree.query_ray_hit(centre - n * 1e-3, -n);
                if (hit.is_hit() && hit.distance() > 1e-3 && hit.distance() < min_wall) {
                    min_wall = hit.distance();
                    min_wall_point = centre - n * (hit.distance() / 2. + 1e-3);
                }
                if (hit.is_hit() && hit.distance() < thickness) {
                    ++thin;
                    if (thin_points.size() < max_points * 3)
                        thin_points.insert(thin_points.end(), {float(centre.x()), float(centre.y()), float(centre.z())});
                }
            }
        }
        part["thinFaces"] = thin;
        thin_total += thin;
        parts.push_back(std::move(part));
    }
    return {{"parts", parts}, {"thickness", thickness}, {"thinFaces", thin_total}, {"thinPoints", thin_points},
            {"minWall", min_wall < 1e8 ? min_wall : -1.}, {"minWallPoint", std::vector<float>{float(min_wall_point.x()), float(min_wall_point.y()), float(min_wall_point.z())}},
            {"openPoints", open_points}, {"crossPoints", cross_points}, {"sharpPoints", sharp_points}};
}

} // namespace orca
