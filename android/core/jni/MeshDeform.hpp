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

// Refines m by repeatedly splitting every edge for which marked(a, b) returns
// true.  Faces using a marked edge are all split with the same new midpoint
// vertex, so the result stays free of T-junctions and keeps its winding.
template <typename EdgeMarked>
inline size_t refine_with_predicate(Mesh &m, size_t max_faces,
                                    EdgeMarked marked) {
    size_t face_count = m.faces.size();
    if (max_faces == 0) max_faces = 2000000;

    while (true) {
        std::unordered_map<uint64_t, int> midpoints;
        midpoints.reserve(face_count * 2);
        size_t needed = face_count; // sum of (1 + marked_edges) per face
        bool over_budget = false;

        // Mark all split edges and, at the same time, verify the budget for
        // the whole pass before creating any new vertex or face.
        for (const auto &f : m.faces) {
            int marked_count = 0;
            for (int e = 0; e < 3; ++e) {
                const int a = f[e];
                const int b = f[(e + 1) % 3];
                if (marked(a, b)) {
                    midpoints.emplace(edge_key(a, b), -1);
                    ++marked_count;
                }
            }
            needed += static_cast<size_t>(marked_count);
            if (needed > max_faces) {
                over_budget = true;
                break;
            }
        }

        if (over_budget || midpoints.empty()) break;

        // Each edge has exactly one midpoint, shared by every incident face.
        for (auto &kv : midpoints) {
            const int a = key_a(kv.first);
            const int b = key_b(kv.first);
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
            int marked_count = 0;
            for (int e = 0; e < 3; ++e) {
                const int a = f[e];
                const int b = f[(e + 1) % 3];
                auto it = midpoints.find(edge_key(a, b));
                if (it != midpoints.end()) {
                    ids[e] = it->second;
                    ++marked_count;
                }
            }

            if (marked_count == 0) {
                new_faces.push_back(f);
                continue;
            }

            // Split marked edges one at a time.  Processing the longest
            // marked edge first gives the less slivery choice for 2 marks.
            double len[3] = {-1.0, -1.0, -1.0};
            for (int e = 0; e < 3; ++e) {
                if (ids[e] >= 0) {
                    const Vec3 d =
                        sub(m.vertices[f[(e + 1) % 3]], m.vertices[f[e]]);
                    len[e] = dot(d, d);
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
                        split_triangle(cur[t], a, b, mid, next, next_count);
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

    if (max_edge <= 0.0) {
        const double diag = detail::bbox_diagonal(m);
        max_edge = diag / 60.0;
        if (max_edge <= 0.0) return face_count;
    }

    const double max_edge_sq = max_edge * max_edge;
    return detail::refine_with_predicate(
        m, max_faces, [&m, max_edge_sq](int a, int b) {
            const Vec3 d = detail::sub(m.vertices[b], m.vertices[a]);
            return detail::dot(d, d) > max_edge_sq;
        });
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

// The warp applied to a single point; warp() maps every vertex through this so
// refinement and warping cannot disagree.
inline Vec3 warp_point(const Vec3 &v, const Params &p, const Vec3 &lo,
                       const Vec3 & /*hi*/, double L, const Vec3 &c) {
    const int axis = p.axis;

    if (p.kind == Kind::Twist) {
        int p1, p2;
        if (axis == 0) { p1 = 1; p2 = 2; }
        else if (axis == 1) { p1 = 2; p2 = 0; }
        else { p1 = 0; p2 = 1; }

        const double total = p.amount * (kPi / 180.0);
        const double t = (comp(v, axis) - comp(lo, axis)) / L;
        const double a = total * t;
        const double ca = std::cos(a);
        const double sa = std::sin(a);
        const double x = comp(v, p1) - comp(c, p1);
        const double y = comp(v, p2) - comp(c, p2);

        Vec3 out = v;
        set_comp(out, p1, comp(c, p1) + x * ca - y * sa);
        set_comp(out, p2, comp(c, p2) + x * sa + y * ca);
        return out;
    }

    if (p.kind == Kind::Taper) {
        int p1, p2;
        if (axis == 0) { p1 = 1; p2 = 2; }
        else if (axis == 1) { p1 = 2; p2 = 0; }
        else { p1 = 0; p2 = 1; }

        const double t = (comp(v, axis) - comp(lo, axis)) / L;
        const double s = 1.0 + (p.amount - 1.0) * t;

        Vec3 out = v;
        set_comp(out, p1, comp(c, p1) + (comp(v, p1) - comp(c, p1)) * s);
        set_comp(out, p2, comp(c, p2) + (comp(v, p2) - comp(c, p2)) * s);
        return out;
    }

    // Bend
    const double theta = p.amount * (kPi / 180.0);
    if (std::abs(theta) < 1e-9) return v;

    const int d = (axis == 2) ? 0 : 2;
    const double R = L / theta; // signed bend radius
    const double lo_a = comp(lo, axis);
    const double c_d = comp(c, d);
    // The third axis is the one that is neither the bend axis nor d.
    const int third = 3 - axis - d;

    const double u = comp(v, axis) - lo_a;
    const double w = comp(v, d) - c_d;
    const double phi = u / R;
    const double sp = std::sin(phi);
    const double cp = std::cos(phi);
    const double old_third = comp(v, third);

    Vec3 out = v;
    set_comp(out, axis, lo_a + (R - w) * sp);
    set_comp(out, d, c_d + R - (R - w) * cp);
    set_comp(out, third, old_third);
    return out;
}

} // namespace detail

// Moves every vertex for the box [lo, hi], which may be larger than the mesh (several parts warped as one); no
// refinement. p.max_edge / p.max_faces are not used.
inline void warp(Mesh &m, const Params &p, const Vec3 &lo, const Vec3 &hi) {
    const double L = detail::checked_length(p, lo, hi);
    const Vec3 c{0.5 * (lo.x + hi.x), 0.5 * (lo.y + hi.y),
                 0.5 * (lo.z + hi.z)};
    for (Vec3 &v : m.vertices) v = detail::warp_point(v, p, lo, hi, L, c);
}

// Refines m so that after warp(m, p, lo, hi) every edge is no longer than max_edge, measured BEFORE the warp,
// and no edge's warped midpoint is farther than tol from the midpoint of its two warped end points.  Does not
// warp.  Returns the face count.
inline size_t refine_for_warp(Mesh &m, const Params &p, const Vec3 &lo,
                              const Vec3 &hi, double max_edge, double tol,
                              size_t max_faces) {
    const double L = detail::checked_length(p, lo, hi); // throws before touching m
    const size_t original_faces = m.faces.size();
    if (original_faces == 0 || m.vertices.empty()) return original_faces;

    if (max_edge <= 0.0) {
        const double diag = detail::bbox_diagonal(m);
        max_edge = diag / 60.0;
        if (max_edge <= 0.0) return original_faces;
    }

    const Vec3 c{0.5 * (lo.x + hi.x), 0.5 * (lo.y + hi.y),
                 0.5 * (lo.z + hi.z)};
    const double max_edge_sq = max_edge * max_edge;
    const double tol_sq = tol * tol;

    return detail::refine_with_predicate(
        m, max_faces, [&](int a, int b) {
            const Vec3 &va = m.vertices[a];
            const Vec3 &vb = m.vertices[b];
            const Vec3 d = detail::sub(vb, va);
            if (detail::dot(d, d) > max_edge_sq) return true;

            const Vec3 mid{0.5 * (va.x + vb.x), 0.5 * (va.y + vb.y),
                           0.5 * (va.z + vb.z)};
            const Vec3 wm = detail::warp_point(mid, p, lo, hi, L, c);
            const Vec3 wa = detail::warp_point(va, p, lo, hi, L, c);
            const Vec3 wb = detail::warp_point(vb, p, lo, hi, L, c);
            const Vec3 chord{0.5 * (wa.x + wb.x), 0.5 * (wa.y + wb.y),
                             0.5 * (wa.z + wb.z)};
            const Vec3 err = detail::sub(wm, chord);
            return detail::dot(err, err) > tol_sq;
        });
}

inline size_t deform(Mesh &m, const Params &p) {
    if (m.vertices.empty() || m.faces.empty())
        throw std::invalid_argument("mesh is empty");
    Vec3 lo, hi;
    detail::bounding_box(m, lo, hi);
    detail::checked_length(p, lo, hi); // before refining, so a bad call leaves m alone

    const double diag = detail::bbox_diagonal(m);
    double max_edge = p.max_edge;
    if (max_edge <= 0.0) max_edge = diag / 60.0;
    const double tol = diag / 4000.0;
    const size_t budget = p.max_faces == 0 ? 2000000 : p.max_faces;
    const size_t result_faces =
        refine_for_warp(m, p, lo, hi, max_edge, tol, budget);
    warp(m, p, lo, hi);
    return result_faces;
}

} // namespace meshdeform
