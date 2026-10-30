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
    std::puts("mesh_edit_test: ok");
    return 0;
}
