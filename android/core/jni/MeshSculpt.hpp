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
    std::vector<std::vector<int>> adj(n);
    for (const auto &f : m.faces) {
        const Vec3 fn = cross(sub(m.vertices[f[1]], m.vertices[f[0]]), sub(m.vertices[f[2]], m.vertices[f[0]])); // 2 * area * normal
        for (int k = 0; k < 3; ++k) {
            const int a = f[k];
            t.normals[a] = add(t.normals[a], fn);
            adj[a].push_back(f[(k + 1) % 3]);
            adj[a].push_back(f[(k + 2) % 3]);
        }
    }
    t.start.assign(n + 1, 0);
    for (size_t i = 0; i < n; ++i) {
        std::sort(adj[i].begin(), adj[i].end());
        adj[i].erase(std::unique(adj[i].begin(), adj[i].end()), adj[i].end());
        t.start[i + 1] = t.start[i] + int(adj[i].size());
        t.normals[i] = unit(t.normals[i]);
    }
    t.nbr.reserve(size_t(t.start[n]));
    for (size_t i = 0; i < n; ++i)
        t.nbr.insert(t.nbr.end(), adj[i].begin(), adj[i].end());
    return t;
}

struct Stroke {
    Brush brush = Brush::Draw;
    Vec3 center{0, 0, 0};   // the surface point under the brush
    double radius = 1.;     // same units as the mesh
    double strength = 0.5;  // 0..1; draw/inflate/crease move up to strength * radius * 0.1 per dab
    bool invert = false;    // draw/inflate/crease dent instead of bulge; flatten/pinch reversed
    Vec3 delta{0, 0, 0};    // grab: how far the centre moved since the previous dab
};

// Applies one brush dab. Returns how many vertices moved. Vertices are read from a snapshot, so a dab does not depend on
// the order they are visited in. [t] is the topology of [m] (normals are the dab's direction for draw / inflate).
inline size_t dab(Mesh &m, const Topology &t, const Stroke &s)
{
    using namespace detail;
    const size_t n = m.vertices.size();
    std::vector<int> hit;
    std::vector<double> w;
    Vec3 avg_normal{0, 0, 0}, avg_pos{0, 0, 0};
    double wsum = 0.;
    for (size_t i = 0; i < n; ++i) {
        const double d = len(sub(m.vertices[i], s.center));
        if (d >= s.radius)
            continue;
        const double f = falloff(d, s.radius);
        hit.push_back(int(i));
        w.push_back(f);
        avg_normal = add(avg_normal, mul(t.normals[i], f));
        avg_pos = add(avg_pos, mul(m.vertices[i], f));
        wsum += f;
    }
    if (hit.empty() || wsum <= 0.)
        return 0;
    avg_normal = unit(avg_normal);
    avg_pos = mul(avg_pos, 1. / wsum);
    const double sign = s.invert ? -1. : 1.;
    const double step = s.strength * s.radius * 0.1;

    std::vector<Vec3> moved(hit.size());
    for (size_t k = 0; k < hit.size(); ++k) {
        const int i = hit[k];
        const Vec3 &p = m.vertices[i];
        const double f = w[k];
        Vec3 np = p;
        switch (s.brush) {
        case Brush::Draw: // along the dab's average normal, so a stroke on a curve stays one smooth ridge
            np = add(p, mul(avg_normal, sign * step * f));
            break;
        case Brush::Inflate: // along each vertex's own normal
            np = add(p, mul(t.normals[i], sign * step * f));
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
            np = add(add(p, mul(avg_normal, -sign * step * f)), mul(toward, std::min(1., s.strength * f * 0.3)));
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
    return hit.size();
}

} // namespace meshsculpt
