#pragma once

// Bend / twist / taper for closed triangle meshes, refined first so the warp has vertices to move. Used by
// OrcaDeform.cpp (which warps several parts with one box).

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace meshdeform {

struct Vec3 {
    double x, y, z;
};

struct Mesh {
    std::vector<Vec3> vertices;
    std::vector<std::array<int, 3>> faces; // CCW = outward
};

enum class Kind { Bend, Twist, Taper };

struct Params {
    Kind kind;
    int axis;          // 0 = X, 1 = Y, 2 = Z
    double amount;
    double max_edge;   // <= 0: auto = bbox diagonal / 60
    size_t max_faces;  // 0: 2,000,000
};

namespace detail {

constexpr double kPi = 3.14159265358979323846;

inline double dot(const Vec3 &a, const Vec3 &b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

inline Vec3 cross(const Vec3 &a, const Vec3 &b) {
    return Vec3{a.y * b.z - a.z * b.y,
                a.z * b.x - a.x * b.z,
                a.x * b.y - a.y * b.x};
}

inline Vec3 sub(const Vec3 &a, const Vec3 &b) {
    return Vec3{a.x - b.x, a.y - b.y, a.z - b.z};
}

inline double comp(const Vec3 &v, int axis) {
    return axis == 0 ? v.x : (axis == 1 ? v.y : v.z);
}

inline void set_comp(Vec3 &v, int axis, double value) {
    if (axis == 0) v.x = value;
    else if (axis == 1) v.y = value;
    else v.z = value;
}

inline uint64_t edge_key(int a, int b) {
    if (a > b) std::swap(a, b);
    return (static_cast<uint64_t>(static_cast<uint32_t>(a)) << 32) |
           static_cast<uint32_t>(b);
}

inline int key_a(uint64_t key) { return static_cast<int>(key >> 32); }
inline int key_b(uint64_t key) {
    return static_cast<int>(key & 0xffffffffu);
}

inline void bounding_box(const Mesh &m, Vec3 &lo, Vec3 &hi) {
    lo = hi = m.vertices[0];
    for (const Vec3 &v : m.vertices) {
        lo.x = std::min(lo.x, v.x);
        lo.y = std::min(lo.y, v.y);
        lo.z = std::min(lo.z, v.z);
        hi.x = std::max(hi.x, v.x);
        hi.y = std::max(hi.y, v.y);
        hi.z = std::max(hi.z, v.z);
    }
}

inline double bbox_diagonal(const Mesh &m) {
    if (m.vertices.empty()) return 0.0;
    Vec3 lo, hi;
    bounding_box(m, lo, hi);
    const Vec3 d = sub(hi, lo);
    return std::sqrt(dot(d, d));
}

// Split one triangle by the currently marked edge (a,b).  The two children
// follow the parent's winding because x->mid->y is a subdivision of x->y.
inline void split_triangle(std::array<int, 3> tri, int a, int b, int mid,
                           std::array<std::array<int, 3>, 4> &out,
                           int &out_count) {
    for (int k = 0; k < 3; ++k) {
        const int x = tri[k];
        const int y = tri[(k + 1) % 3];
        const int z = tri[(k + 2) % 3];
        if ((x == a && y == b) || (x == b && y == a)) {
            out[out_count++] = std::array<int, 3>{x, mid, z};
            out[out_count++] = std::array<int, 3>{mid, y, z};
            return;
        }
    }
    out[out_count++] = tri;
}

} // namespace detail

inline double signed_volume(const Mesh &m) {
    double six_v = 0.0;
    for (const auto &f : m.faces) {
        const Vec3 &a = m.vertices[f[0]];
        const Vec3 &b = m.vertices[f[1]];
        const Vec3 &c = m.vertices[f[2]];
        six_v += detail::dot(a, detail::cross(b, c));
    }
    return six_v / 6.0;
}

inline size_t refine_to_length(Mesh &m, double max_edge, size_t max_faces) {
    size_t face_count = m.faces.size();
    if (face_count == 0 || m.vertices.empty()) return face_count;
    if (max_faces == 0) max_faces = 2000000;

    if (max_edge <= 0.0) {
        const double diag = detail::bbox_diagonal(m);
        max_edge = diag / 60.0;
        if (max_edge <= 0.0) return face_count;
    }

    const double max_edge_sq = max_edge * max_edge;

    while (true) {
        std::unordered_map<uint64_t, int> midpoints;
        midpoints.reserve(face_count * 2);
        size_t needed = face_count; // sum of (1 + marked_edges) per face
        bool over_budget = false;

        // Mark all long edges and, at the same time, verify the budget for
        // the whole pass before creating any new vertex or face.
        for (const auto &f : m.faces) {
            int marked = 0;
            for (int e = 0; e < 3; ++e) {
                const int a = f[e];
                const int b = f[(e + 1) % 3];
                const Vec3 &va = m.vertices[a];
                const Vec3 &vb = m.vertices[b];
                const Vec3 d = detail::sub(vb, va);
                if (detail::dot(d, d) > max_edge_sq) {
                    midpoints.emplace(detail::edge_key(a, b), -1);
                    ++marked;
                }
            }
            needed += static_cast<size_t>(marked);
            if (needed > max_faces) {
                over_budget = true;
                break;
            }
        }

        if (over_budget || midpoints.empty()) break;

        // Each edge has exactly one midpoint, shared by every incident face.
        for (auto &kv : midpoints) {
            const int a = detail::key_a(kv.first);
            const int b = detail::key_b(kv.first);
            const Vec3 &va = m.vertices[a];
            const Vec3 &vb = m.vertices[b];
            kv.second = static_cast<int>(m.vertices.size());
            m.vertices.push_back(Vec3{0.5 * (va.x + vb.x),
                                      0.5 * (va.y + vb.y),
                                      0.5 * (va.z + vb.z)});
        }

        std::vector<std::array<int, 3>> new_faces;
        new_faces.reserve(needed);

        for (const auto &f : m.faces) {
            int ids[3] = {-1, -1, -1};
            int marked = 0;
            for (int e = 0; e < 3; ++e) {
                const int a = f[e];
                const int b = f[(e + 1) % 3];
                auto it = midpoints.find(detail::edge_key(a, b));
                if (it != midpoints.end()) {
                    ids[e] = it->second;
                    ++marked;
                }
            }

            if (marked == 0) {
                new_faces.push_back(f);
                continue;
            }

            // Split marked edges one at a time.  Processing the longest
            // marked edge first gives the less slivery choice for 2 marks.
            double len[3] = {-1.0, -1.0, -1.0};
            for (int e = 0; e < 3; ++e) {
                if (ids[e] >= 0) {
                    const Vec3 d =
                        detail::sub(m.vertices[f[(e + 1) % 3]],
                                    m.vertices[f[e]]);
                    len[e] = detail::dot(d, d);
                }
            }
            int order[3] = {0, 1, 2};
            for (int i = 0; i < 3; ++i) {
                for (int j = i + 1; j < 3; ++j) {
                    if (len[order[j]] > len[order[i]])
                        std::swap(order[i], order[j]);
                }
            }

            std::array<std::array<int, 3>, 4> cur;
            int cur_count = 1;
            cur[0] = f;

            for (int oi = 0; oi < 3; ++oi) {
                const int e = order[oi];
                if (ids[e] < 0) continue;
                const int a = f[e];
                const int b = f[(e + 1) % 3];
                const int mid = ids[e];

                std::array<std::array<int, 3>, 4> next;
                int next_count = 0;
                for (int t = 0; t < cur_count; ++t) {
                    bool has_edge = false;
                    for (int k = 0; k < 3; ++k) {
                        const int x = cur[t][k];
                        const int y = cur[t][(k + 1) % 3];
                        if ((x == a && y == b) || (x == b && y == a)) {
                            has_edge = true;
                            break;
                        }
                    }
                    if (has_edge) {
                        detail::split_triangle(cur[t], a, b, mid, next, next_count);
                    } else {
                        next[next_count++] = cur[t];
                    }
                }
                cur = next;
                cur_count = next_count;
            }

            for (int t = 0; t < cur_count; ++t) new_faces.push_back(cur[t]);
        }

        m.faces.swap(new_faces);
        face_count = m.faces.size();
    }

    return m.faces.size();
}

namespace detail {

// Throws std::invalid_argument for a bad axis, taper amount or a box with no length along the axis.
inline double checked_length(const Params &p, const Vec3 &lo, const Vec3 &hi) {
    if (p.axis < 0 || p.axis > 2)
        throw std::invalid_argument("axis out of range");
    if (p.kind == Kind::Taper && p.amount <= 0.0)
        throw std::invalid_argument("taper amount must be > 0");
    const double L = comp(hi, p.axis) - comp(lo, p.axis);
    if (!(L > 0.0))
        throw std::invalid_argument("zero bounding-box length along axis");
    return L;
}

} // namespace detail

// Moves every vertex for the box [lo, hi], which may be larger than the mesh (several parts warped as one); no
// refinement. p.max_edge / p.max_faces are not used.
inline void warp(Mesh &m, const Params &p, const Vec3 &lo, const Vec3 &hi) {
    const double L = detail::checked_length(p, lo, hi);
    const int axis = p.axis;
    const Vec3 c{0.5 * (lo.x + hi.x), 0.5 * (lo.y + hi.y),
                 0.5 * (lo.z + hi.z)};

    if (p.kind == Kind::Twist) {
        int p1, p2;
        if (axis == 0) { p1 = 1; p2 = 2; }
        else if (axis == 1) { p1 = 2; p2 = 0; }
        else { p1 = 0; p2 = 1; }

        const double total = p.amount * (detail::kPi / 180.0);
        for (Vec3 &v : m.vertices) {
            const double t = (detail::comp(v, axis) - detail::comp(lo, axis)) / L;
            const double a = total * t;
            const double ca = std::cos(a);
            const double sa = std::sin(a);
            const double x = detail::comp(v, p1) - detail::comp(c, p1);
            const double y = detail::comp(v, p2) - detail::comp(c, p2);
            detail::set_comp(v, p1, detail::comp(c, p1) + x * ca - y * sa);
            detail::set_comp(v, p2, detail::comp(c, p2) + x * sa + y * ca);
        }
    } else if (p.kind == Kind::Taper) {
        int p1, p2;
        if (axis == 0) { p1 = 1; p2 = 2; }
        else if (axis == 1) { p1 = 2; p2 = 0; }
        else { p1 = 0; p2 = 1; }

        for (Vec3 &v : m.vertices) {
            const double t = (detail::comp(v, axis) - detail::comp(lo, axis)) / L;
            const double s = 1.0 + (p.amount - 1.0) * t;
            detail::set_comp(v, p1,
                             detail::comp(c, p1) +
                                 (detail::comp(v, p1) - detail::comp(c, p1)) * s);
            detail::set_comp(v, p2,
                             detail::comp(c, p2) +
                                 (detail::comp(v, p2) - detail::comp(c, p2)) * s);
        }
    } else { // Bend
        const double theta = p.amount * (detail::kPi / 180.0);
        if (std::abs(theta) < 1e-9) return;

        const int d = (axis == 2) ? 0 : 2;
        const double R = L / theta; // signed bend radius
        const double lo_a = detail::comp(lo, axis);
        const double c_d = detail::comp(c, d);
        // The third axis is the one that is neither the bend axis nor d.
        const int third = 3 - axis - d;

        for (Vec3 &v : m.vertices) {
            const double u = detail::comp(v, axis) - lo_a;
            const double w = detail::comp(v, d) - c_d;
            const double phi = u / R;
            const double sp = std::sin(phi);
            const double cp = std::cos(phi);
            const double old_third = detail::comp(v, third);
            detail::set_comp(v, axis, lo_a + (R - w) * sp);
            detail::set_comp(v, d, c_d + R - (R - w) * cp);
            detail::set_comp(v, third, old_third);
        }
    }
}

inline size_t deform(Mesh &m, const Params &p) {
    if (m.vertices.empty() || m.faces.empty())
        throw std::invalid_argument("mesh is empty");
    Vec3 lo, hi;
    detail::bounding_box(m, lo, hi);
    detail::checked_length(p, lo, hi); // before refining, so a bad call leaves m alone

    double max_edge = p.max_edge;
    if (max_edge <= 0.0) {
        const double diag = detail::bbox_diagonal(m);
        max_edge = diag / 60.0;
    }
    const size_t budget = p.max_faces == 0 ? 2000000 : p.max_faces;
    const size_t result_faces = refine_to_length(m, max_edge, budget);
    warp(m, p, lo, hi);
    return result_faces;
}

} // namespace meshdeform
