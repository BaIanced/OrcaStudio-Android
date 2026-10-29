#pragma once

// Edits on a region of a triangle mesh (one flag per face): Taubin smoothing with the region's border pinned, and a closed
// solid hugging the region (the region offset outward and inward, joined by walls along its border). Used by OrcaRegion.cpp.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace meshregion {

struct Vec3 {
    double x, y, z;
};

struct Mesh {
    std::vector<Vec3> vertices;
    std::vector<std::array<int, 3>> faces; // CCW = outward; vertices shared between faces
};

namespace detail {

inline Vec3 sub(const Vec3 &a, const Vec3 &b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline Vec3 cross(const Vec3 &a, const Vec3 &b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
inline double dot(const Vec3 &a, const Vec3 &b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline double norm(const Vec3 &a) { return std::sqrt(dot(a, a)); }
inline bool finite(const Vec3 &v) { return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z); }
inline uint64_t key(int a, int b) {
    const auto lo = uint32_t(std::min(a, b)), hi = uint32_t(std::max(a, b));
    return uint64_t(lo) << 32 | hi;
}

inline void validate(const Mesh &m, const std::vector<uint8_t> &region) {
    if (region.size() != m.faces.size())
        throw std::invalid_argument("region size does not match the face count");
    const int n = int(m.vertices.size());
    for (const auto &f : m.faces) {
        for (int k = 0; k < 3; ++k)
            if (f[k] < 0 || f[k] >= n)
                throw std::invalid_argument("face index out of range");
        if (f[0] == f[1] || f[1] == f[2] || f[0] == f[2])
            throw std::invalid_argument("face with a repeated vertex");
    }
    for (const Vec3 &v : m.vertices)
        if (!finite(v))
            throw std::invalid_argument("non-finite vertex");
}

// Per undirected edge: faces using it, region faces using it (and how many of them run low -> high index), and the last
// region face's direction (a -> b).
struct EdgeInfo {
    int faces = 0, region = 0, forward = 0, a = -1, b = -1;
};

inline std::unordered_map<uint64_t, EdgeInfo> edges(const Mesh &m, const std::vector<uint8_t> &region) {
    std::unordered_map<uint64_t, EdgeInfo> e;
    e.reserve(m.faces.size() * 2);
    for (size_t i = 0; i < m.faces.size(); ++i)
        for (int k = 0; k < 3; ++k) {
            const int a = m.faces[i][k], b = m.faces[i][(k + 1) % 3];
            EdgeInfo &info = e[key(a, b)];
            ++info.faces;
            if (region[i]) {
                ++info.region;
                info.forward += a < b;
                info.a = a;
                info.b = b;
            }
        }
    return e;
}

} // namespace detail

inline double signed_volume(const Mesh &m) {
    double v = 0.;
    for (const auto &f : m.faces)
        v += detail::dot(m.vertices[f[0]], detail::cross(m.vertices[f[1]], m.vertices[f[2]])) / 6.;
    return v;
}

// Taubin smoothing (lambda step, then mu step, per iteration; umbrella Laplacian over edge neighbours) of the FREE vertices:
// those whose faces are all in the region and that have no open edge. The others keep their exact coordinates; faces never
// change. Returns the number of free vertices (0 when iterations == 0; nothing moves then).
inline size_t smooth_region(Mesh &m, const std::vector<uint8_t> &region, int iterations, double lambda = 0.5, double mu = -0.53) {
    using namespace detail;
    validate(m, region);
    if (iterations < 0 || !std::isfinite(lambda) || !std::isfinite(mu) || lambda <= 0. || mu >= -lambda)
        throw std::invalid_argument("bad smoothing parameters");
    if (iterations == 0)
        return 0;
    const size_t n = m.vertices.size();
    std::vector<uint8_t> used(n, 0), pinned(n, 0);
    for (size_t i = 0; i < m.faces.size(); ++i)
        for (int v : m.faces[i]) {
            used[v] = 1;
            if (!region[i])
                pinned[v] = 1;
        }
    const auto e = edges(m, region);
    // Neighbour lists (CSR) from the unique edges; open edges pin both ends.
    std::vector<int> start(n + 1, 0);
    for (const auto &kv : e) {
        const int a = int(kv.first >> 32), b = int(kv.first & 0xffffffffu);
        ++start[a + 1];
        ++start[b + 1];
        if (kv.second.faces == 1)
            pinned[a] = pinned[b] = 1;
    }
    for (size_t i = 0; i < n; ++i)
        start[i + 1] += start[i];
    std::vector<int> nbr(start[n]), fill(start.begin(), start.end() - 1);
    for (const auto &kv : e) {
        const int a = int(kv.first >> 32), b = int(kv.first & 0xffffffffu);
        nbr[fill[a]++] = b;
        nbr[fill[b]++] = a;
    }
    std::vector<int> free_list;
    for (size_t i = 0; i < n; ++i)
        if (used[i] && !pinned[i])
            free_list.push_back(int(i));
    std::vector<Vec3> lap(free_list.size());
    auto step = [&](double w) {
        for (size_t j = 0; j < free_list.size(); ++j) {
            const int v = free_list[j];
            Vec3 s{0., 0., 0.};
            for (int k = start[v]; k < start[v + 1]; ++k) {
                const Vec3 &p = m.vertices[nbr[k]];
                s.x += p.x;
                s.y += p.y;
                s.z += p.z;
            }
            const double inv = 1. / double(start[v + 1] - start[v]);
            const Vec3 &p = m.vertices[v];
            lap[j] = {s.x * inv - p.x, s.y * inv - p.y, s.z * inv - p.z};
        }
        for (size_t j = 0; j < free_list.size(); ++j) {
            Vec3 &p = m.vertices[free_list[j]];
            p.x += w * lap[j].x;
            p.y += w * lap[j].y;
            p.z += w * lap[j].z;
        }
    };
    for (int it = 0; it < iterations; ++it) {
        step(lambda);
        step(mu);
    }
    return free_list.size();
}

// Closed solid hugging the region: its faces offset by `out` along the outward vertex normals and by `in` inward (mitred, so
// flat parts and creases keep the distance), the inner copy reversed, and a wall quad along every border edge. Outward
// oriented; uses only its own vertices (2 per region vertex).
inline Mesh region_shell(const Mesh &m, const std::vector<uint8_t> &region, double out, double in) {
    using namespace detail;
    validate(m, region);
    if (!std::isfinite(out) || !std::isfinite(in) || out < 0. || in < 0. || out + in <= 0.)
        throw std::invalid_argument("bad shell thickness");
    const auto e = edges(m, region);
    const size_t n = m.vertices.size();
    std::vector<Vec3> nsum(n, Vec3{0., 0., 0.});
    std::vector<int> id(n, -1);
    int count = 0;
    std::vector<Vec3> fnormal(m.faces.size());
    for (size_t i = 0; i < m.faces.size(); ++i) {
        if (!region[i])
            continue;
        const auto &f = m.faces[i];
        for (int k = 0; k < 3; ++k) {
            const EdgeInfo &info = e.at(key(f[k], f[(k + 1) % 3]));
            if (info.faces > 2)
                throw std::invalid_argument("non-manifold edge in the region");
            if (info.region == 2 && info.forward != 1)
                throw std::invalid_argument("region faces with inconsistent winding");
        }
        const Vec3 c = cross(sub(m.vertices[f[1]], m.vertices[f[0]]), sub(m.vertices[f[2]], m.vertices[f[0]]));
        const double len = norm(c);
        if (!(len > 0.))
            throw std::invalid_argument("zero-area region face");
        const Vec3 nf{c.x / len, c.y / len, c.z / len};
        fnormal[i] = nf;
        for (int k = 0; k < 3; ++k) {
            const Vec3 &p = m.vertices[f[k]];
            const Vec3 u = sub(m.vertices[f[(k + 1) % 3]], p), w = sub(m.vertices[f[(k + 2) % 3]], p);
            const double angle = std::atan2(norm(cross(u, w)), dot(u, w));
            nsum[f[k]].x += angle * nf.x;
            nsum[f[k]].y += angle * nf.y;
            nsum[f[k]].z += angle * nf.z;
            if (id[f[k]] < 0)
                id[f[k]] = count++;
        }
    }
    if (count == 0)
        throw std::invalid_argument("empty region");
    std::vector<Vec3> dir(n, Vec3{0., 0., 0.});
    std::vector<double> mitre(n, 1.);
    for (size_t v = 0; v < n; ++v)
        if (id[v] >= 0) {
            const double len = norm(nsum[v]);
            if (!(len > 1e-12))
                throw std::invalid_argument("region vertex without a normal");
            dir[v] = {nsum[v].x / len, nsum[v].y / len, nsum[v].z / len};
        }
    for (size_t i = 0; i < m.faces.size(); ++i)
        if (region[i])
            for (int v : m.faces[i])
                mitre[v] = std::min(mitre[v], dot(dir[v], fnormal[i]));
    Mesh r;
    r.vertices.resize(size_t(count) * 2);
    for (size_t v = 0; v < n; ++v)
        if (id[v] >= 0) {
            const double s = 1. / std::max(0.5, mitre[v]);
            const Vec3 &p = m.vertices[v], &d = dir[v];
            r.vertices[2 * id[v]] = {p.x + d.x * out * s, p.y + d.y * out * s, p.z + d.z * out * s};
            r.vertices[2 * id[v] + 1] = {p.x - d.x * in * s, p.y - d.y * in * s, p.z - d.z * in * s};
        }
    auto O = [&](int v) { return 2 * id[v]; };
    auto I = [&](int v) { return 2 * id[v] + 1; };
    for (size_t i = 0; i < m.faces.size(); ++i)
        if (region[i]) {
            const auto &f = m.faces[i];
            r.faces.push_back({O(f[0]), O(f[1]), O(f[2])});
            r.faces.push_back({I(f[0]), I(f[2]), I(f[1])});
        }
    for (const auto &kv : e)
        if (kv.second.region == 1) {
            // Border edge a -> b as in its region face: outer has a -> b, inner b -> a; the wall takes the reverses.
            const int a = kv.second.a, b = kv.second.b;
            r.faces.push_back({O(b), O(a), I(a)});
            r.faces.push_back({O(b), I(a), I(b)});
        }
    return r;
}

} // namespace meshregion
