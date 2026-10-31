#pragma once

// The live mesh session shared by sculpting (OrcaSculpt.cpp) and mesh edit mode (OrcaMeshEdit.cpp): a part's mesh copy,
// its topology and a hit tree kept between calls, and where the part's copies sit in the scene mesh (for patches).

#include "OrcaEngine.hpp"
#include "MeshSculpt.hpp"

#include <limits>
#include <optional>

#include "libslic3r/AABBTreeIndirect.hpp"
#include "libslic3r/TriangleMesh.hpp"

namespace orca {

struct OrcaEngine::SculptSession {
    int object = -1, instance = -1, volume = -1;
    // The part's mesh this session matches (set when built and at each flush): another mesh there = stale session.
    std::shared_ptr<const Slic3r::TriangleMesh> current;
    meshdeform::Mesh mesh;
    meshsculpt::Topology topo;
    bool dirty = false; // vertices moved since the last flush
    // The edge length the part was refined to for the brush (strokes with a brush as big skip the check: sculpted
    // edges stretch past it, and refining them every stroke grew the mesh and rebuilt the session each time).
    double refined_to = std::numeric_limits<double>::max();
    // Hits: a tree over [base] (the mesh when the session was built); faces moved since then are tested one by one.
    std::shared_ptr<const Slic3r::TriangleMesh> base;
    Slic3r::AABBTreeIndirect::Tree<3, float> tree;
    double eps = 1e-6;
    std::vector<char> touched;
    std::vector<int> touched_list;
    // The part's copies in the scene mesh, in write_mesh() order: first triangle, drawn matrix, ids. Valid for [copies_version].
    struct Copy {
        size_t first;
        Slic3r::Transform3d frame;
        bool flip;
        float id, type;
    };
    std::vector<Copy> copies;
    int copies_version = -1;
    Slic3r::Transform3d frame = Slic3r::Transform3d::Identity(); // volume -> world of the sculpted copy
    // Grab: the vertices under the brush at the stroke's first hit (and its mirror images), their start places and per-axis
    // weights (falloff times the mirror's signs, summed where images overlap); the drag plane (local).
    bool grabbing = false;
    std::vector<int> grab_verts;
    std::vector<Slic3r::Vec3d> grab_w;
    std::vector<meshdeform::Vec3> grab_orig;
    Slic3r::Vec3d grab_anchor = Slic3r::Vec3d::Zero(), grab_normal = Slic3r::Vec3d::UnitZ();
    meshsculpt::StrokeState layer;
    // Symmetry planes' crossing (local): the part's middle when it was first sculpted with symmetry, kept across rebuilds of
    // the same part, so an uneven stroke doesn't shift the planes.
    std::optional<Slic3r::Vec3d> mirror_center;
    // Per-call dedupe of moved vertices / changed faces.
    std::vector<uint32_t> vstamp, fstamp;
    uint32_t stamp = 0;

    // Mesh edit mode: the selected vertices (sorted), the face regions for [edit_angle] (cleared when vertices move), and a
    // drag's start: positions of the selection and the pivot (local).
    std::vector<int> edit_sel;
    std::vector<int> edit_nb, edit_region;
    double edit_angle = std::numeric_limits<double>::quiet_NaN();
    bool edit_moving = false;
    std::vector<meshdeform::Vec3> edit_orig;
    Slic3r::Vec3d edit_pivot = Slic3r::Vec3d::Zero();
    // An extrude drag in progress: the selection before it (a drag let go at zero height is taken back).
    bool edit_extruded = false;
    std::vector<int> edit_before;
    // The last cut's ring (sorted): while it is the selection, the overlay draws its mesh edges (they are rarely creases).
    std::vector<int> edit_ring;

    // The surface under a ray now (local): tree hits on faces that haven't moved, moved faces one by one. The hit point,
    // its face and that face's vertex nearest to it.
    struct Hit {
        Slic3r::Vec3d point;
        int seed;
        int face;
    };
    std::optional<Hit> hit(const Slic3r::Vec3d &o, const Slic3r::Vec3d &d) const;
    // Marks the faces round [verts] as moved (hit tests test them one by one from then on).
    void touch(const std::vector<int> &verts);
};

meshdeform::Mesh sculpt_to_mesh(const indexed_triangle_set &src);
indexed_triangle_set sculpt_to_its(const meshdeform::Mesh &m);

} // namespace orca
