#pragma once

// OrcaMesh edit mode kernels (docs/handoff/NOTES-orcamesh-edit.md): feature-level picking on triangle meshes. CAD STLs
// split each flat face into many triangles, so a "face" here is a crease-bounded region (flood fill while the dihedral
// angle stays under a limit), an "edge" is the crease polyline between two regions and a "vertex" is a feature corner
// (where three or more regions meet). Triangles mode picks raw triangles / mesh edges / mesh vertices instead.
// Pure geometry (no libslic3r), so it compiles alone for tests. Used by OrcaMeshEdit.cpp.

#include "MeshSculpt.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <map>
#include <utility>
#include <vector>

namespace meshedit {

using meshdeform::Mesh;
using meshdeform::Vec3;

enum class Kind { Vertex, Edge, Face };

// Across-edge neighbours: nb[3 * f + k] = the face across edge (f[k], f[k + 1]), -1 on a border or non-manifold edge.
inline std::vector<int> face_neighbors(const Mesh &m)
{
    std::map<std::pair<int, int>, std::vector<int>> edges;
    for (size_t f = 0; f < m.faces.size(); ++f)
        for (int k = 0; k < 3; ++k) {
            const int a = m.faces[f][k], b = m.faces[f][(k + 1) % 3];
            edges[{std::min(a, b), std::max(a, b)}].push_back(int(3 * f + k));
        }
    std::vector<int> nb(3 * m.faces.size(), -1);
    for (const auto &[key, slots] : edges)
        if (slots.size() == 2) {
            nb[size_t(slots[0])] = slots[1] / 3;
            nb[size_t(slots[1])] = slots[0] / 3;
        }
    return nb;
}

inline Vec3 face_normal(const Mesh &m, int f)
{
    using namespace meshsculpt::detail;
    const auto &t = m.faces[size_t(f)];
    return unit(cross(sub(m.vertices[t[1]], m.vertices[t[0]]), sub(m.vertices[t[2]], m.vertices[t[0]])));
}

// Region id per face: faces joined across edges whose normals differ by less than [angle_deg] share an id. 180 or more
// joins each connected piece; 0 or less makes every triangle its own region (Triangles mode).
inline std::vector<int> face_regions(const Mesh &m, const std::vector<int> &nb, double angle_deg)
{
    using namespace meshsculpt::detail;
    const size_t n = m.faces.size();
    std::vector<int> id(n, -1);
    if (angle_deg <= 0.) {
        for (size_t f = 0; f < n; ++f)
            id[f] = int(f);
        return id;
    }
    std::vector<Vec3> normals(n);
    for (size_t f = 0; f < n; ++f)
        normals[f] = face_normal(m, int(f));
    const double limit = std::cos(std::min(angle_deg, 180.) * M_PI / 180.) - 1e-9;
    int next = 0;
    std::vector<int> todo;
    for (size_t s = 0; s < n; ++s) {
        if (id[s] >= 0)
            continue;
        id[s] = next;
        todo.assign(1, int(s));
        while (!todo.empty()) {
            const int f = todo.back();
            todo.pop_back();
            for (int k = 0; k < 3; ++k) {
                const int g = nb[size_t(3 * f + k)];
                if (g >= 0 && id[size_t(g)] < 0 && dot(normals[size_t(f)], normals[size_t(g)]) >= limit) {
                    id[size_t(g)] = next;
                    todo.push_back(g);
                }
            }
        }
        ++next;
    }
    return id;
}

// A mesh edge on a region border: its vertices and the regions on either side (b = -1 on an open border).
struct Crease {
    int v0, v1, a, b;
};

// Every edge where two regions meet (or the mesh is open), once each.
inline std::vector<Crease> creases(const Mesh &m, const std::vector<int> &nb, const std::vector<int> &region)
{
    std::vector<Crease> out;
    for (size_t f = 0; f < m.faces.size(); ++f)
        for (int k = 0; k < 3; ++k) {
            const int g = nb[3 * f + k];
            if (g >= 0 && (region[size_t(g)] == region[f] || size_t(g) < f))
                continue; // inside a region, or the other face lists it
            const int ra = region[f], rb = g >= 0 ? region[size_t(g)] : -1;
            out.push_back({m.faces[f][k], m.faces[f][(k + 1) % 3], std::min(ra, rb < 0 ? ra : rb), rb < 0 ? -1 : std::max(ra, rb)});
        }
    return out;
}

// Squared distance from [p] to the segment [a, b].
inline double segment_dist2(const Vec3 &p, const Vec3 &a, const Vec3 &b)
{
    using namespace meshsculpt::detail;
    const Vec3 ab = sub(b, a);
    const double l2 = dot(ab, ab);
    const double t = l2 > 0. ? std::clamp(dot(sub(p, a), ab) / l2, 0., 1.) : 0.;
    const Vec3 d = sub(p, add(a, mul(ab, t)));
    return dot(d, d);
}

// The vertices a tap picks. [hit_face] / [hit] are the ray's hit (mesh coordinates); [region] as face_regions() made it;
// [corner_reach] = how far (mesh units) a feature corner may be from the hit to win over the nearest vertex.
//  Face: every vertex of the hit face's region.
//  Edge: the crease nearest the hit among the hit region's borders, then every crease between the same two regions
//        (the whole crease polyline); in Triangles mode (each face its own region) that is the one mesh edge.
//  Vertex: the nearest corner (a vertex on three or more regions) within reach, else the hit face's nearest vertex.
// Also fills [edges_out] (pairs of vertices) with the picked edges for the overlay (Edge mode; Face mode: the region's border).
inline std::vector<int> pick(const Mesh &m, const std::vector<int> &nb, const std::vector<int> &region, Kind kind, int hit_face,
                             const Vec3 &hit, double corner_reach, std::vector<std::array<int, 2>> *edges_out = nullptr)
{
    using namespace meshsculpt::detail;
    std::vector<int> verts;
    if (hit_face < 0 || size_t(hit_face) >= m.faces.size())
        return verts;
    const int r = region[size_t(hit_face)];
    auto unique = [&verts] {
        std::sort(verts.begin(), verts.end());
        verts.erase(std::unique(verts.begin(), verts.end()), verts.end());
    };
    if (kind == Kind::Face) {
        for (size_t f = 0; f < m.faces.size(); ++f)
            if (region[f] == r)
                verts.insert(verts.end(), m.faces[f].begin(), m.faces[f].end());
        if (edges_out)
            for (const Crease &c : creases(m, nb, region))
                if (c.a == r || c.b == r)
                    edges_out->push_back({c.v0, c.v1});
        unique();
        return verts;
    }
    if (kind == Kind::Edge) {
        const std::vector<Crease> all = creases(m, nb, region);
        int best = -1;
        double near = std::numeric_limits<double>::max();
        for (size_t i = 0; i < all.size(); ++i)
            if (all[i].a == r || all[i].b == r) {
                const double d2 = segment_dist2(hit, m.vertices[size_t(all[i].v0)], m.vertices[size_t(all[i].v1)]);
                if (d2 < near) {
                    near = d2;
                    best = int(i);
                }
            }
        if (best < 0)
            return verts;
        const Crease pickc = all[size_t(best)];
        for (const Crease &c : all)
            if (c.a == pickc.a && c.b == pickc.b) {
                verts.push_back(c.v0);
                verts.push_back(c.v1);
                if (edges_out)
                    edges_out->push_back({c.v0, c.v1});
            }
        unique();
        return verts;
    }
    // Vertex: regions round each vertex (a few per vertex, so a small sorted list each).
    std::vector<std::vector<int>> around(m.vertices.size());
    for (size_t f = 0; f < m.faces.size(); ++f)
        for (int v : m.faces[f]) {
            auto &l = around[size_t(v)];
            if (std::find(l.begin(), l.end(), region[f]) == l.end())
                l.push_back(region[f]);
        }
    int best = -1;
    double near = corner_reach * corner_reach;
    for (size_t v = 0; v < m.vertices.size(); ++v)
        if (around[v].size() >= 3) {
            const Vec3 d = sub(m.vertices[v], hit);
            const double d2 = dot(d, d);
            if (d2 <= near) {
                near = d2;
                best = int(v);
            }
        }
    if (best < 0) {
        near = std::numeric_limits<double>::max();
        for (int v : m.faces[size_t(hit_face)]) {
            const Vec3 d = sub(m.vertices[size_t(v)], hit);
            if (dot(d, d) < near) {
                near = dot(d, d);
                best = v;
            }
        }
    }
    verts.push_back(best);
    return verts;
}

// Toggles [picked] in [selection] (both sorted, unique): all of it already selected = removed, else added.
inline void toggle(std::vector<int> &selection, const std::vector<int> &picked)
{
    const bool all = !picked.empty() && std::includes(selection.begin(), selection.end(), picked.begin(), picked.end());
    std::vector<int> out;
    if (all)
        std::set_difference(selection.begin(), selection.end(), picked.begin(), picked.end(), std::back_inserter(out));
    else
        std::set_union(selection.begin(), selection.end(), picked.begin(), picked.end(), std::back_inserter(out));
    selection = std::move(out);
}

// The selection grown by one ring: every vertex sharing an edge with a selected one ([t] from build_topology).
inline void grow(std::vector<int> &selection, const meshsculpt::Topology &t)
{
    std::vector<int> out = selection;
    for (int v : selection)
        out.insert(out.end(), t.nbr.begin() + t.start[size_t(v)], t.nbr.begin() + t.start[size_t(v) + 1]);
    std::sort(out.begin(), out.end());
    out.erase(std::unique(out.begin(), out.end()), out.end());
    selection = std::move(out);
}

// Mean of the selected vertices (the gizmo's pivot).
inline Vec3 centroid(const Mesh &m, const std::vector<int> &selection)
{
    using namespace meshsculpt::detail;
    Vec3 c{0, 0, 0};
    for (int v : selection)
        c = add(c, m.vertices[size_t(v)]);
    return selection.empty() ? c : mul(c, 1. / double(selection.size()));
}

// Area-weighted normal of the faces whose three vertices are all selected (a face selection's push direction), else
// of every face touching the selection.
inline Vec3 selection_normal(const Mesh &m, const std::vector<int> &selection)
{
    using namespace meshsculpt::detail;
    std::vector<char> in(m.vertices.size(), 0);
    for (int v : selection)
        in[size_t(v)] = 1;
    Vec3 all{0, 0, 0}, touch{0, 0, 0};
    for (const auto &f : m.faces) {
        const int k = in[size_t(f[0])] + in[size_t(f[1])] + in[size_t(f[2])];
        if (k == 0)
            continue;
        const Vec3 n = cross(sub(m.vertices[f[1]], m.vertices[f[0]]), sub(m.vertices[f[2]], m.vertices[f[0]]));
        touch = add(touch, n);
        if (k == 3)
            all = add(all, n);
    }
    return unit(len(all) > 0. ? all : touch);
}

// Extrude / inset topology (M2). The cap = the faces with all three vertices selected. Every vertex on the cap's border
// (an edge between a cap face and any other face, or an open edge) gets a copy that the cap's faces use instead, and each
// border edge gets a wall quad from the old border to the copies, so a closed part stays closed. Nothing moves: the walls
// are flat until the cap moves (extrude, either way: a pocket has the same topology) or the copies are pulled in (inset).
struct CapSplit {
    std::vector<int> cap;    // the cap's vertices after the split, sorted (the new selection)
    std::vector<int> copies; // the border copies
    std::vector<Vec3> inset; // per copy: the in-plane move that puts it 1 unit inside each of its border edges
};

inline CapSplit split_cap(Mesh &m, const std::vector<int> &selection)
{
    using namespace meshsculpt::detail;
    CapSplit out;
    std::vector<char> in(m.vertices.size(), 0);
    for (int v : selection)
        in[size_t(v)] = 1;
    std::vector<char> capf(m.faces.size(), 0);
    bool any = false;
    for (size_t f = 0; f < m.faces.size(); ++f) {
        const auto &t = m.faces[f];
        capf[f] = in[size_t(t[0])] && in[size_t(t[1])] && in[size_t(t[2])];
        any = any || capf[f];
    }
    if (!any)
        return out;
    // Border edges in the cap's winding (the cap is on their left, seen from outside), with their inward direction.
    const std::vector<int> nb = face_neighbors(m);
    std::vector<std::array<int, 2>> border;
    std::vector<Vec3> inward;
    for (size_t f = 0; f < m.faces.size(); ++f)
        if (capf[f])
            for (int k = 0; k < 3; ++k) {
                const int g = nb[3 * f + k];
                if (g >= 0 && capf[size_t(g)])
                    continue;
                const int a = m.faces[f][k], b = m.faces[f][(k + 1) % 3];
                border.push_back({a, b});
                inward.push_back(unit(cross(face_normal(m, int(f)), unit(sub(m.vertices[size_t(b)], m.vertices[size_t(a)])))));
            }
    std::vector<int> copy(m.vertices.size(), -1);
    std::vector<std::vector<Vec3>> dirs;
    for (size_t i = 0; i < border.size(); ++i)
        for (int v : border[i]) {
            if (copy[size_t(v)] < 0) {
                copy[size_t(v)] = int(m.vertices.size());
                m.vertices.push_back(m.vertices[size_t(v)]);
                out.copies.push_back(copy[size_t(v)]);
                dirs.emplace_back();
            }
            dirs[size_t(copy[size_t(v)]) - copy.size()].push_back(inward[i]);
        }
    for (size_t f = 0; f < m.faces.size(); ++f)
        if (capf[f])
            for (int &v : m.faces[f])
                if (copy[size_t(v)] >= 0)
                    v = copy[size_t(v)];
    for (const auto &[a, b] : border) {
        const int ca = copy[size_t(a)], cb = copy[size_t(b)];
        m.faces.push_back({a, b, cb});
        m.faces.push_back({a, cb, ca});
    }
    // Inset: along the mean of the border edges' inward directions, stretched so each edge is 1 unit away (capped at a
    // sharp corner, where the exact point runs off far).
    for (const auto &d : dirs) {
        Vec3 sum{0, 0, 0};
        for (const Vec3 &e : d)
            sum = add(sum, e);
        const Vec3 u = len(sum) > 1e-12 ? unit(sum) : Vec3{0, 0, 0};
        double s = 1.;
        for (const Vec3 &e : d)
            s = std::min(s, dot(u, e));
        out.inset.push_back(mul(u, 1. / std::max(s, 0.25)));
    }
    for (size_t f = 0; f < capf.size(); ++f)
        if (capf[f])
            out.cap.insert(out.cap.end(), m.faces[f].begin(), m.faces[f].end());
    std::sort(out.cap.begin(), out.cap.end());
    out.cap.erase(std::unique(out.cap.begin(), out.cap.end()), out.cap.end());
    return out;
}

} // namespace meshedit
