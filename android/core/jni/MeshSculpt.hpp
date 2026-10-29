#pragma once

// Sculpt brushes for triangle meshes (draw, inflate, smooth, flatten, pinch, crease, grab), ported in spirit from
// SculptGL (MIT): every vertex within the brush radius moves by a falloff-weighted step. Pure geometry (no
// libslic3r), so it compiles alone for tests. Used by OrcaSculpt.cpp.

#include "MeshDeform.hpp"

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

namespace meshsculpt {

using meshdeform::Mesh;
using meshdeform::Vec3;

enum class Brush { Draw, Inflate, Smooth, Flatten, Pinch, Crease, Grab };

inline Brush brush_of(const std::string &name)
{
    if (name == "draw") return Brush::Draw;
    if (name == "inflate") return Brush::Inflate;
    if (name == "smooth") return Brush::Smooth;
    if (name == "flatten") return Brush::Flatten;
    if (name == "pinch") return Brush::Pinch;
    if (name == "crease") return Brush::Crease;
    if (name == "grab") return Brush::Grab;
    throw std::invalid_argument("Unknown sculpt brush: " + name);
}

// Vertex neighbours and area-weighted vertex normals; rebuilt when the faces change (the brushes only move vertices).
struct Topology {
    std::vector<int> start;     // CSR: neighbours of vertex i are nbr[start[i] .. start[i + 1])
    std::vector<int> nbr;
    std::vector<Vec3> normals;
    std::vector<int> fstart;    // CSR: faces round vertex i are faces[fstart[i] .. fstart[i + 1])
    std::vector<int> faces;
};

namespace detail {

inline Vec3 add(const Vec3 &a, const Vec3 &b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline Vec3 sub(const Vec3 &a, const Vec3 &b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline Vec3 mul(const Vec3 &a, double s) { return {a.x * s, a.y * s, a.z * s}; }
inline double dot(const Vec3 &a, const Vec3 &b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline double len(const Vec3 &a) { return std::sqrt(dot(a, a)); }
inline Vec3 cross(const Vec3 &a, const Vec3 &b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
inline Vec3 unit(const Vec3 &a)
{
    const double l = len(a);
    return l > 1e-12 ? mul(a, 1. / l) : Vec3{0, 0, 0};
}

// SculptGL's smooth falloff: 1 at the centre, 0 at the radius, flat at both ends.
inline double falloff(double d, double radius)
{
    if (d >= radius)
        return 0.;
    const double t = 1. - (d * d) / (radius * radius);
    return t * t;
}

} // namespace detail

inline Topology build_topology(const Mesh &m)
{
    using namespace detail;
    Topology t;
    const size_t n = m.vertices.size();
    t.normals.assign(n, {0, 0, 0});
    // Faces round each vertex (CSR), and area-weighted normals in the same pass.
    t.fstart.assign(n + 1, 0);
    for (const auto &f : m.faces) {
        const Vec3 fn = cross(sub(m.vertices[f[1]], m.vertices[f[0]]), sub(m.vertices[f[2]], m.vertices[f[0]])); // 2 * area * normal
        for (int k = 0; k < 3; ++k) {
            ++t.fstart[f[k] + 1];
            t.normals[f[k]] = add(t.normals[f[k]], fn);
        }
    }
    for (size_t i = 0; i < n; ++i) {
        t.fstart[i + 1] += t.fstart[i];
        t.normals[i] = unit(t.normals[i]);
    }
    t.faces.resize(size_t(t.fstart[n]));
    std::vector<int> fill(t.fstart.begin(), t.fstart.end() - 1);
    for (size_t fi = 0; fi < m.faces.size(); ++fi)
        for (int k = 0; k < 3; ++k)
            t.faces[size_t(fill[m.faces[fi][k]]++)] = int(fi);
    // Neighbours: the other corners of those faces, without repeats.
    t.start.assign(n + 1, 0);
    t.nbr.reserve(size_t(t.fstart[n]));
    std::vector<int> buf;
    for (size_t i = 0; i < n; ++i) {
        buf.clear();
        for (int j = t.fstart[i]; j < t.fstart[i + 1]; ++j)
            for (int v : m.faces[size_t(t.faces[size_t(j)])])
                if (v != int(i))
                    buf.push_back(v);
        std::sort(buf.begin(), buf.end());
        buf.erase(std::unique(buf.begin(), buf.end()), buf.end());
        t.nbr.insert(t.nbr.end(), buf.begin(), buf.end());
        t.start[i + 1] = int(t.nbr.size());
    }
    return t;
}

// Recomputes the normals of [verts] from their faces (after a dab moved them), so the next dab pushes along the new surface.
inline void update_normals(const Mesh &m, Topology &t, const std::vector<int> &verts)
{
    using namespace detail;
    for (int i : verts) {
        Vec3 nsum{0, 0, 0};
        for (int j = t.fstart[i]; j < t.fstart[i + 1]; ++j) {
            const auto &f = m.faces[size_t(t.faces[j])];
            nsum = add(nsum, cross(sub(m.vertices[f[1]], m.vertices[f[0]]), sub(m.vertices[f[2]], m.vertices[f[0]])));
        }
        t.normals[i] = unit(nsum);
    }
}

// Per-stroke heights (Blender's "accumulate" off): draw / inflate / crease lift a vertex to the highest any of the
// stroke's dabs asked for, along the direction it was first pushed, instead of adding dab on dab. Stacked dabs climbed
// the bump the previous ones made and left beads with forward-leaning tips (WSA 2026-10-26). Start a stroke with begin().
struct StrokeState {
    std::vector<uint32_t> stamp;
    std::vector<double> h;
    std::vector<Vec3> dir;
    uint32_t id = 0;
    void begin(size_t vertices)
    {
        stamp.resize(vertices, 0);
        h.resize(vertices, 0.);
        dir.resize(vertices, {0, 0, 0});
        if (++id == 0) {
            std::fill(stamp.begin(), stamp.end(), 0);
            id = 1;
        }
    }
};

struct Stroke {
    Brush brush = Brush::Draw;
    Vec3 center{0, 0, 0};   // the surface point under the brush
    double radius = 1.;     // same units as the mesh
    double strength = 0.5;  // 0..1; draw/inflate/crease move up to strength * radius * 0.1 per dab
    bool invert = false;    // draw/inflate/crease dent instead of bulge; flatten/pinch reversed
    Vec3 delta{0, 0, 0};    // grab: how far the centre moved since the previous dab
    StrokeState *layer = nullptr; // set: draw / inflate / crease heights don't build up within the stroke (up to
                                  // strength * radius / 2), see StrokeState
    int seed = -1;          // a vertex at the hit: the brush then takes only vertices joined to it within the radius
                            // (along the surface), so a thin wall's far side stays put; -1 = every vertex within the radius
};

// Applies one brush dab. Returns how many vertices moved. Vertices are read from a snapshot, so a dab does not depend on
// the order they are visited in. [t] is the topology of [m] (normals are the dab's direction for draw / inflate).
// [moved_out], when given, gets the moved vertices appended.
inline size_t dab(Mesh &m, const Topology &t, const Stroke &s, std::vector<int> *moved_out = nullptr)
{
    using namespace detail;
    const size_t n = m.vertices.size();
    std::vector<int> hit;
    std::vector<double> w;
    Vec3 avg_normal{0, 0, 0}, avg_pos{0, 0, 0};
    double wsum = 0.;
    const double r2 = s.radius * s.radius;
    auto take = [&](size_t i) {
        const Vec3 off = sub(m.vertices[i], s.center);
        const double d2 = dot(off, off);
        if (d2 >= r2)
            return false;
        const double f = falloff(std::sqrt(d2), s.radius);
        hit.push_back(int(i));
        w.push_back(f);
        avg_normal = add(avg_normal, mul(t.normals[i], f));
        avg_pos = add(avg_pos, mul(m.vertices[i], f));
        wsum += f;
        return true;
    };
    if (s.seed >= 0 && size_t(s.seed) < n) {
        // Flood along the edges from the seed, through vertices inside the radius.
        std::vector<char> seen(n, 0);
        std::vector<int> todo{s.seed};
        seen[size_t(s.seed)] = 1;
        while (!todo.empty()) {
            const int i = todo.back();
            todo.pop_back();
            if (!take(size_t(i)) && i != s.seed)
                continue;
            for (int j = t.start[i]; j < t.start[i + 1]; ++j) {
                const int k = t.nbr[size_t(j)];
                if (!seen[size_t(k)]) {
                    seen[size_t(k)] = 1;
                    todo.push_back(k);
                }
            }
        }
    } else {
        for (size_t i = 0; i < n; ++i)
            take(i);
    }
    if (hit.empty() || wsum <= 0.)
        return 0;
    avg_normal = unit(avg_normal);
    avg_pos = mul(avg_pos, 1. / wsum);
    const double sign = s.invert ? -1. : 1.;
    const double step = s.strength * s.radius * 0.1;
    // Layered: the stroke's height at this vertex, raised to [want] along its first direction [d]; the step to move now.
    auto layered = [&](int i, const Vec3 &d, double want) {
        StrokeState &L = *s.layer;
        if (L.stamp[size_t(i)] != L.id) {
            L.stamp[size_t(i)] = L.id;
            L.h[size_t(i)] = 0.;
            L.dir[size_t(i)] = d;
        }
        const double rise = std::abs(want) > std::abs(L.h[size_t(i)]) ? want - L.h[size_t(i)] : 0.;
        L.h[size_t(i)] += rise;
        return mul(L.dir[size_t(i)], rise);
    };
    const bool layer = s.layer && s.layer->stamp.size() >= n;
    const double height = s.strength * s.radius * 0.5;

    std::vector<Vec3> moved(hit.size());
    for (size_t k = 0; k < hit.size(); ++k) {
        const int i = hit[k];
        const Vec3 &p = m.vertices[i];
        const double f = w[k];
        Vec3 np = p;
        switch (s.brush) {
        case Brush::Draw: // along the dab's average normal, so a stroke on a curve stays one smooth ridge
            np = layer ? add(p, layered(i, avg_normal, sign * height * f)) : add(p, mul(avg_normal, sign * step * f));
            break;
        case Brush::Inflate: // along each vertex's own normal
            np = layer ? add(p, layered(i, t.normals[i], sign * height * f)) : add(p, mul(t.normals[i], sign * step * f));
            break;
        case Brush::Smooth: { // toward the mean of the neighbours
            const int a = t.start[i], b = t.start[i + 1];
            if (b > a) {
                Vec3 mean{0, 0, 0};
                for (int j = a; j < b; ++j)
                    mean = add(mean, m.vertices[t.nbr[j]]);
                mean = mul(mean, 1. / double(b - a));
                np = add(p, mul(sub(mean, p), std::min(1., s.strength * f)));
            }
            break;
        }
        case Brush::Flatten: { // onto the dab's average plane; inverted = away from it (exaggerates)
            const double off = dot(sub(p, avg_pos), avg_normal);
            np = sub(p, mul(avg_normal, sign * off * std::min(1., s.strength * f)));
            break;
        }
        case Brush::Pinch: { // toward the centre within the surface, so ridges sharpen
            Vec3 toward = sub(s.center, p);
            toward = sub(toward, mul(t.normals[i], dot(toward, t.normals[i])));
            np = add(p, mul(toward, sign * std::min(1., s.strength * f * 0.5)));
            break;
        }
        case Brush::Crease: { // a groove: dent along the normal and pinch the walls in
            Vec3 toward = sub(s.center, p);
            toward = sub(toward, mul(t.normals[i], dot(toward, t.normals[i])));
            const Vec3 dent = layer ? layered(i, avg_normal, -sign * height * f) : mul(avg_normal, -sign * step * f);
            np = add(add(p, dent), mul(toward, std::min(1., s.strength * f * 0.3)));
            break;
        }
        case Brush::Grab:
            np = add(p, mul(s.delta, f));
            break;
        }
        moved[k] = np;
    }
    for (size_t k = 0; k < hit.size(); ++k)
        m.vertices[hit[k]] = moved[k];
    if (moved_out)
        moved_out->insert(moved_out->end(), hit.begin(), hit.end());
    return hit.size();
}

} // namespace meshsculpt
