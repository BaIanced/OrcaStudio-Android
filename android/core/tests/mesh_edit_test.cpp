// Standalone checks of MeshEdit.hpp (no libslic3r): g++ -std=c++17 -I../jni mesh_edit_test.cpp && ./a.out
#include "MeshEdit.hpp"

#include <cassert>
#include <cstdio>

using namespace meshedit;

// Unit cube, two triangles per side (CCW = outward), each side split once more along a midpoint fan so sides have
// inner vertices: 6 sides x 4 triangles.
static Mesh cube()
{
    Mesh m;
    for (int i = 0; i < 8; ++i)
        m.vertices.push_back({double(i & 1), double((i >> 1) & 1), double((i >> 2) & 1)});
    const int quads[6][4] = {{0, 2, 3, 1}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 4, 6, 2}, {1, 3, 7, 5}};
    for (const auto &q : quads) {
        Vec3 c{0, 0, 0};
        for (int k = 0; k < 4; ++k)
            c = meshsculpt::detail::add(c, m.vertices[size_t(q[k])]);
        m.vertices.push_back(meshsculpt::detail::mul(c, 0.25));
        const int mid = int(m.vertices.size()) - 1;
        for (int k = 0; k < 4; ++k)
            m.faces.push_back({q[k], q[(k + 1) % 4], mid});
    }
    return m;
}

int main()
{
    const Mesh m = cube();
    const auto nb = face_neighbors(m);
    for (int s : nb)
        assert(s >= 0); // closed
    const auto reg = face_regions(m, nb, 30.);
    assert(*std::max_element(reg.begin(), reg.end()) == 5); // six sides
    const auto tri = face_regions(m, nb, 0.);
    assert(tri[23] == 23);
    // Each cube edge is one mesh edge here.
    assert(creases(m, nb, reg).size() == 12);

    // Face: tap the bottom (z = 0, quad 0 -> faces 0..3): its 4 corners + middle.
    std::vector<std::array<int, 2>> edges;
    auto f = pick(m, nb, reg, Kind::Face, 1, {0.5, 0.6, 0.}, 0.1, &edges);
    assert(f.size() == 5 && edges.size() == 4);
    const Vec3 n = selection_normal(m, f);
    assert(std::abs(n.z + 1.) < 1e-9);
    const Vec3 c = centroid(m, f);
    assert(std::abs(c.x - 0.5) < 1e-9 && std::abs(c.z) < 1e-9);

    // Edge: near the bottom side's x = 0 edge (vertices 0 and 2).
    edges.clear();
    auto e = pick(m, nb, reg, Kind::Edge, 0, {0.05, 0.5, 0.}, 0.1, &edges);
    assert(e.size() == 2 && e[0] == 0 && e[1] == 2 && edges.size() == 1);

    // Vertex: corner 0 within reach; out of reach = the face's nearest vertex (the middle, 8).
    auto v = pick(m, nb, reg, Kind::Vertex, 0, {0.05, 0.05, 0.}, 0.2);
    assert(v.size() == 1 && v[0] == 0);
    v = pick(m, nb, reg, Kind::Vertex, 0, {0.45, 0.5, 0.}, 0.2);
    assert(v.size() == 1 && v[0] == 8);

    // Toggle: add, then remove the same pick.
    std::vector<int> sel;
    toggle(sel, f);
    toggle(sel, e);
    assert(sel.size() == 3); // the edge was all selected (inside the face): tapping it deselects it
    toggle(sel, e);
    assert(sel.size() == 5);

    // Grow from corner 0: its neighbours 1, 2, 4 and the three side middles.
    std::vector<int> g{0};
    grow(g, meshsculpt::build_topology(m));
    assert(g.size() == 7);

    // Extrude the top (z = 1: corners 4..7, middle 9) by 0.5: 4 border copies, 4 wall quads, still closed.
    auto volume = [](const Mesh &x) {
        double vol = 0.;
        for (const auto &t : x.faces) {
            const Vec3 &a = x.vertices[size_t(t[0])], &b = x.vertices[size_t(t[1])], &c = x.vertices[size_t(t[2])];
            vol += meshsculpt::detail::dot(a, meshsculpt::detail::cross(b, c)) / 6.;
        }
        return vol;
    };
    auto closed = [](const Mesh &x) {
        for (int s : face_neighbors(x))
            if (s < 0)
                return false;
        return true;
    };
    Mesh ex = cube();
    const std::vector<int> top{4, 5, 6, 7, 9};
    CapSplit cs = split_cap(ex, top);
    assert(cs.copies.size() == 4 && ex.vertices.size() == 18 && ex.faces.size() == 32 && cs.cap.size() == 5 && closed(ex));
    for (int v : cs.cap)
        ex.vertices[size_t(v)].z += 0.5;
    assert(std::abs(volume(ex) - 1.5) < 1e-9);
    // Pocket: the same split moved down.
    Mesh po = cube();
    cs = split_cap(po, top);
    for (int v : cs.cap)
        po.vertices[size_t(v)].z -= 0.25;
    assert(std::abs(volume(po) - 0.75) < 1e-9 && closed(po));
    // Inset 0.1: the copies end 0.1 inside both border edges (corners on the diagonal), the cap stays at z = 1.
    Mesh in = cube();
    cs = split_cap(in, top);
    for (size_t k = 0; k < cs.copies.size(); ++k) {
        Vec3 &p = in.vertices[size_t(cs.copies[k])];
        p = meshsculpt::detail::add(p, meshsculpt::detail::mul(cs.inset[k], 0.1));
        assert(std::abs(std::min(p.x, 1. - p.x) - 0.1) < 1e-9 && std::abs(std::min(p.y, 1. - p.y) - 0.1) < 1e-9 && p.z == 1.);
    }
    assert(std::abs(volume(in) - 1.) < 1e-9 && closed(in));
    // Extrude with the slide direction: the cube's sides run along +Z, so no copies, no walls; pushing the top down just
    // shortens the cube (the walls used to lie inside the sides: self-intersections).
    Mesh sl = cube();
    cs = split_cap(sl, top, {0, 0, 1});
    assert(cs.copies.empty() && sl.faces.size() == 24 && sl.vertices.size() == 14);
    for (int v : cs.cap)
        sl.vertices[size_t(v)].z -= 0.25;
    assert(std::abs(volume(sl) - 0.75) < 1e-9 && closed(sl));
    // After an inset the ring is flat (across the slide direction): its copies and walls stay.
    Mesh ri = cube();
    cs = split_cap(ri, top);
    for (size_t k = 0; k < cs.copies.size(); ++k)
        ri.vertices[size_t(cs.copies[k])] = meshsculpt::detail::add(ri.vertices[size_t(cs.copies[k])], meshsculpt::detail::mul(cs.inset[k], 0.1));
    std::vector<int> inner;
    for (int v : cs.cap)
        if (ri.vertices[size_t(v)].x > 0.05 && ri.vertices[size_t(v)].x < 0.95)
            inner.push_back(v);
    const size_t nf = ri.faces.size();
    cs = split_cap(ri, inner, {0, 0, 1});
    assert(cs.copies.size() == 4 && ri.faces.size() == nf + 8 && closed(ri));
    for (int v : cs.cap)
        ri.vertices[size_t(v)].z -= 0.5;
    assert(std::abs(volume(ri) - (1. - 0.64 * 0.5)) < 1e-9);
    // No face fully selected: nothing to split.
    Mesh none = cube();
    assert(split_cap(none, {4, 5}).cap.empty() && none.faces.size() == 24);
    // Cut ring at z = 0.3 from a side face (face 8 = side 2's first triangle): 4 vertical edges + 8 corner-to-middle edges
    // split, three crossing triangles per side each become three; closed, same volume, the ring all at z = 0.3.
    Mesh cr = cube();
    std::vector<int> ring = cut_ring(cr, 8, {0.5, 0, 0.3}, {0, 0, 1}, 1e-9);
    assert(ring.size() == 12 && cr.faces.size() == 48 && closed(cr) && std::abs(volume(cr) - 1.) < 1e-9);
    for (int v : ring)
        assert(std::abs(cr.vertices[size_t(v)].z - 0.3) < 1e-12);
    // Waist: the ring scaled to half about the axis leaves a closed, smaller part.
    for (int v : ring) {
        Vec3 &p = cr.vertices[size_t(v)];
        p.x = 0.5 + (p.x - 0.5) * 0.5;
        p.y = 0.5 + (p.y - 0.5) * 0.5;
    }
    assert(closed(cr) && volume(cr) < 1.);
    // Starting on the top (the plane misses it): the band is found through a shared corner.
    Mesh ct = cube();
    assert(cut_ring(ct, 4, {0.5, 0.5, 0.3}, {0, 0, 1}, 1e-9).size() == 12);
    // Through the side middles (z = 0.5): those vertices are on the plane, only the vertical edges split.
    Mesh cm = cube();
    ring = cut_ring(cm, 8, {0.5, 0, 0.5}, {0, 0, 1}, 1e-9);
    assert(ring.size() == 8 && cm.faces.size() == 32 && closed(cm) && std::abs(volume(cm) - 1.) < 1e-9);
    // A plane past the part crosses nothing.
    Mesh co = cube();
    assert(cut_ring(co, 8, {0.5, 0, 2}, {0, 0, 1}, 1e-9).empty() && co.faces.size() == 24);
    std::puts("mesh_edit_test: ok");
    return 0;
}
