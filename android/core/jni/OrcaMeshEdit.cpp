// OrcaEngine: mesh edit mode (docs/handoff/NOTES-orcamesh-edit.md), the vertex / edge / face half of OrcaMesh beside
// sculpting. Runs on the sculpt session (SculptSession.hpp): the same mesh copy, topology, hit tree and patch path, so a
// drag only sends the triangles round the selection. Picking is feature level by default (MeshEdit.hpp). M1: select and
// move / scale / rotate the selection (vertices only move). M2: extrude / inset faces (new topology, session rebuilt).

#include "SculptSession.hpp"
#include "MeshEdit.hpp"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <stdexcept>

#include "libslic3r/Model.hpp"

namespace orca {

using namespace Slic3r;

json OrcaEngine::mesh_edit(const std::array<int, 3> &target, const std::string &op, const std::optional<std::array<double, 6>> &ray,
                           const std::string &kind_name, double angle, bool toggle, const std::optional<std::array<double, 16>> &matrix,
                           double reach, double amount)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    sculpt_hulls_locked(false);
    const auto [oi, ii, vi] = target;
    ModelObject &obj = object_at(oi);
    if (ii < 0 || ii >= int(obj.instances.size()) || vi < 0 || vi >= int(obj.volumes.size()))
        throw std::runtime_error("Invalid mesh edit target");
    ModelVolume *vol = obj.volumes[vi];
    if (!vol->is_model_part())
        throw std::runtime_error("Only model parts can be edited");
    meshedit::Kind kind = meshedit::Kind::Face;
    if (kind_name == "vertex")
        kind = meshedit::Kind::Vertex;
    else if (kind_name == "edge")
        kind = meshedit::Kind::Edge;
    else if (kind_name != "face")
        throw std::runtime_error("Unknown selection kind: " + kind_name);

    // The session: another part's unflushed edit goes into its mesh first; a session that no longer matches this part's
    // mesh (undo, another edit, the first call) is rebuilt, which also drops the selection.
    if (m_sculpt && (m_sculpt->object != oi || m_sculpt->volume != vi))
        sculpt_flush_locked();
    bool committed = false;
    if (!(m_sculpt && m_sculpt->object == oi && m_sculpt->volume == vi && m_sculpt->current == vol->mesh_ptr())) {
        sculpt_build(oi, ii, vi);
        // The scene shows this part cut by a negative volume: redraw it whole, the patches index its own triangles.
        if (m_cut_previews.count(vol)) {
            write_mesh();
            committed = true;
        }
    }
    if (m_sculpt->copies_version != m_mesh_version || m_sculpt->instance != ii) {
        m_sculpt->instance = ii;
        sculpt_copies();
    }
    // Extrude / inset replace the session (new topology): [ss] is only used before that, *m_sculpt after.
    SculptSession &ss = *m_sculpt;
    // Face regions for this crease angle (the across-edge neighbours hold while only vertices move).
    auto regions = [this, angle] {
        SculptSession &ss = *m_sculpt;
        if (ss.edit_nb.empty())
            ss.edit_nb = meshedit::face_neighbors(ss.mesh);
        if (ss.edit_region.empty() || ss.edit_angle != angle) {
            ss.edit_region = meshedit::face_regions(ss.mesh, ss.edit_nb, angle);
            ss.edit_angle = angle;
        }
    };
    const double scale = std::max(std::cbrt(std::abs(ss.frame.linear().determinant())), 1e-9);

    // A drag step: the selection to [matrix] about the drag's start pivot, from the start positions; the triangles round it.
    auto move = [this, &matrix](SculptSession &ss) {
        Transform3d m = Transform3d::Identity();
        for (int r = 0; r < 4; ++r)
            for (int c = 0; c < 4; ++c)
                m.matrix()(r, c) = (*matrix)[size_t(4 * r + c)];
        const Vec3d cw = ss.frame * ss.edit_pivot;
        const Transform3d local = ss.frame.inverse() * Eigen::Translation3d(cw) * m * Eigen::Translation3d(-cw) * ss.frame;
        for (size_t k = 0; k < ss.edit_sel.size(); ++k) {
            const auto &q = ss.edit_orig[k];
            const Vec3d p = local * Vec3d(q.x, q.y, q.z);
            ss.mesh.vertices[size_t(ss.edit_sel[k])] = {p.x(), p.y(), p.z()};
        }
        meshsculpt::update_normals(ss.mesh, ss.topo, ss.edit_sel);
        ss.touch(ss.edit_sel);
        ss.dirty = true;
        std::vector<int> faces;
        for (int v : ss.edit_sel)
            faces.insert(faces.end(), ss.topo.faces.begin() + ss.topo.fstart[v], ss.topo.faces.begin() + ss.topo.fstart[v + 1]);
        std::sort(faces.begin(), faces.end());
        faces.erase(std::unique(faces.begin(), faces.end()), faces.end());
        return sculpt_patch(faces);
    };
    // A drag's start: one undo step for the whole drag, from the selection's start positions about its centroid.
    auto start_drag = [](SculptSession &ss) {
        ss.edit_orig.clear();
        for (int v : ss.edit_sel)
            ss.edit_orig.push_back(ss.mesh.vertices[size_t(v)]);
        const auto c = meshedit::centroid(ss.mesh, ss.edit_sel);
        ss.edit_pivot = Vec3d(c.x, c.y, c.z);
        ss.edit_moving = true;
    };

    std::string patch;
    std::optional<json> scene; // a commit's scene
    if (op == "select" || op == "clear" || op == "grow" || op == "all") {
        ss.edit_moving = false;
        if (op == "clear")
            ss.edit_sel.clear();
        else if (op == "all") {
            ss.edit_sel.resize(ss.mesh.vertices.size());
            std::iota(ss.edit_sel.begin(), ss.edit_sel.end(), 0);
        }
        else if (op == "grow")
            meshedit::grow(ss.edit_sel, ss.topo);
        else {
            if (!ray)
                throw std::runtime_error("select needs a ray");
            const Transform3d to_local = ss.frame.inverse();
            const Vec3d o = to_local * Vec3d((*ray)[0], (*ray)[1], (*ray)[2]);
            const Vec3d d = (to_local.linear() * Vec3d((*ray)[3], (*ray)[4], (*ray)[5])).normalized();
            const auto h = ss.hit(o, d);
            if (!h) {
                if (!toggle)
                    ss.edit_sel.clear();
            } else {
                regions();
                const std::vector<int> picked = meshedit::pick(ss.mesh, ss.edit_nb, ss.edit_region, kind, h->face,
                                                               {h->point.x(), h->point.y(), h->point.z()}, reach / scale);
                if (toggle)
                    meshedit::toggle(ss.edit_sel, picked);
                else
                    ss.edit_sel = picked;
            }
        }
    } else if (op == "move") {
        if (!matrix)
            throw std::runtime_error("move needs a matrix");
        if (ss.edit_sel.empty())
            throw std::runtime_error("Nothing selected");
        if (!ss.edit_moving) {
            sculpt_flush_locked();
            push_undo();
            start_drag(ss);
        }
        patch = move(ss);
    } else if (op == "extrude" || op == "inset") {
        // The cap's border gets copies and walls (MeshEdit.hpp split_cap); the new mesh goes into the part and the scene
        // at once, the session is rebuilt on it with the cap selected. Extrude then moves the cap like a drag ("move"
        // calls follow, "end" closes it); inset pulls the border copies [amount] mm in and ends here.
        if (ss.edit_sel.empty())
            throw std::runtime_error("Nothing selected");
        if (op == "extrude" ? !matrix : !(amount > 0.))
            throw std::runtime_error(op == "extrude" ? "extrude needs a matrix" : "inset needs an amount");
        meshdeform::Mesh m = ss.mesh;
        const meshedit::CapSplit cs = meshedit::split_cap(m, ss.edit_sel,
            op == "extrude" ? meshedit::selection_normal(ss.mesh, ss.edit_sel) : meshdeform::Vec3{0, 0, 0});
        if (cs.cap.empty())
            throw std::runtime_error("Select a face first");
        if (op == "inset") {
            using namespace meshsculpt::detail;
            for (size_t k = 0; k < cs.copies.size(); ++k) {
                auto &p = m.vertices[size_t(cs.copies[k])];
                p = add(p, mul(cs.inset[k], amount / scale));
            }
        }
        const std::vector<int> before = ss.edit_sel;
        ss.edit_moving = false;
        sculpt_flush_locked();
        push_undo();
        ss.mesh = std::move(m);
        ss.dirty = true;
        sculpt_flush_locked();
        vol->supported_facets.reset();
        vol->seam_facets.reset();
        vol->mmu_segmentation_facets.reset();
        vol->fuzzy_skin_facets.reset();
        if (op == "inset")
            drop_to_bed(obj);
        scene = commit();
        sculpt_build(oi, ii, vi);
        sculpt_copies();
        SculptSession &ns = *m_sculpt;
        ns.edit_sel = cs.cap;
        if (op == "extrude") {
            ns.edit_before = before;
            ns.edit_extruded = true;
            start_drag(ns);
            patch = move(ns);
        }
    } else if (op == "end") {
        ss.edit_moving = false;
        // An extrude let go at zero height: flat walls, so it is taken back (the undo step it pushed too).
        bool flat = ss.edit_extruded;
        for (size_t k = 0; flat && k < ss.edit_sel.size(); ++k) {
            const auto &p = ss.mesh.vertices[size_t(ss.edit_sel[k])], &q = ss.edit_orig[k];
            flat = std::abs(p.x - q.x) + std::abs(p.y - q.y) + std::abs(p.z - q.z) < 1e-6;
        }
        ss.edit_extruded = false;
        if (flat && !m_undo.empty()) {
            const std::vector<int> before = ss.edit_before;
            m_model = std::make_unique<Model>(*m_undo.back().model);
            m_plates = m_undo.back().plates;
            m_undo.pop_back();
            m_sculpt.reset();
            scene = commit();
            sculpt_build(oi, ii, vi);
            sculpt_copies();
            m_sculpt->edit_sel = before;
        } else if (sculpt_flush_locked()) {
            // Regions follow the moved faces from here (kept during the drag: the overlay's creases stay put).
            ss.edit_region.clear();
            drop_to_bed(obj);
            scene = commit();
            // The drop moved the copy: overlay positions follow the new frame.
            sculpt_copies();
        }
    } else
        throw std::runtime_error("Unknown mesh edit op: " + op);

    json out = scene ? std::move(*scene) : committed ? scene_json() : json::object();
    out["patch"] = patch;
    out["mesh_version"] = m_mesh_version;
    // The selection in world mm: pivot, push direction, the crease edges inside it (the overlay) and its vertices.
    SculptSession &s = *m_sculpt;
    json sel = json::object();
    sel["count"] = s.edit_sel.size();
    if (!s.edit_sel.empty()) {
        auto world = [&s](const meshdeform::Vec3 &v) {
            const Vec3d w = s.frame * Vec3d(v.x, v.y, v.z);
            return json::array({w.x(), w.y(), w.z()});
        };
        sel["centroid"] = world(meshedit::centroid(s.mesh, s.edit_sel));
        const auto n = meshedit::selection_normal(s.mesh, s.edit_sel);
        Vec3d wn = s.frame.linear().inverse().transpose() * Vec3d(n.x, n.y, n.z);
        wn = wn.squaredNorm() > 0. ? Vec3d(wn.normalized()) : Vec3d(Vec3d::UnitZ());
        sel["normal"] = {wn.x(), wn.y(), wn.z()};
        regions();
        std::vector<char> in(s.mesh.vertices.size(), 0);
        for (int v : s.edit_sel)
            in[size_t(v)] = 1;
        json edges = json::array();
        size_t shown = 0;
        for (const meshedit::Crease &c : meshedit::creases(s.mesh, s.edit_nb, s.edit_region))
            if (in[size_t(c.v0)] && in[size_t(c.v1)] && shown++ < 4000)
                for (int v : {c.v0, c.v1})
                    for (const json &x : world(s.mesh.vertices[size_t(v)]))
                        edges.push_back(x);
        sel["edges"] = std::move(edges);
        json verts = json::array();
        for (size_t k = 0; k < s.edit_sel.size() && k < 500; ++k)
            for (const json &x : world(s.mesh.vertices[size_t(s.edit_sel[k])]))
                verts.push_back(x);
        sel["verts"] = std::move(verts);
    }
    out["selection"] = std::move(sel);
    return out;
}

} // namespace orca
