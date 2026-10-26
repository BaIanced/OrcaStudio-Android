// OrcaEngine: the shape builder's "Blend joins": a group's shapes as exact OCCT solids, fused, the
// edges where they meet rounded, holes cut (their rims rounded too), tessellated to an STL.

#include "OrcaEngine.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>

#include "libslic3r/CAD/GeometryEngine.hpp"
#include "libslic3r/TriangleMesh.hpp"

#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepBuilderAPI_GTransform.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <BRepFilletAPI_MakeChamfer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCone.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRepPrimAPI_MakeSphere.hxx>
#include <BRepTools_History.hxx>
#include <BRep_Tool.hxx>
#include <ShapeUpgrade_UnifySameDomain.hxx>
#include <Standard_Failure.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <NCollection_IndexedDataMap.hxx>
#include <NCollection_IndexedMap.hxx>
#include <NCollection_List.hxx>
#include <TopTools_ShapeMapHasher.hxx>
#include <TopoDS.hxx>
#include <gp_Ax2.hxx>
#include <gp_GTrsf.hxx>
#include <gp_Trsf.hxx>

namespace orca {

using namespace Slic3r;

namespace {

// The shape builder's own edge rounding / chamfer, on every real edge of one part: not a cylinder's
// seam (one face on both sides) nor a cone's degenerate apex, which OCCT can't round.
TopoDS_Shape round_all(const TopoDS_Shape &s, double r, bool chamfer)
{
    NCollection_IndexedDataMap<TopoDS_Shape, NCollection_List<TopoDS_Shape>, TopTools_ShapeMapHasher> faces;
    TopExp::MapShapesAndAncestors(s, TopAbs_EDGE, TopAbs_FACE, faces);
    BRepFilletAPI_MakeFillet fillet(s);
    BRepFilletAPI_MakeChamfer cut(s);
    int n = 0;
    for (int i = 1; i <= faces.Extent(); ++i) {
        const TopoDS_Edge &e = TopoDS::Edge(faces.FindKey(i));
        const NCollection_List<TopoDS_Shape> &fs = faces.FindFromIndex(i);
        if (BRep_Tool::Degenerated(e) || fs.Size() != 2 || fs.First().IsSame(fs.Last())) continue;
        if (chamfer) cut.Add(r, e);
        else fillet.Add(r, e);
        ++n;
    }
    if (n == 0) return s;
    if (chamfer) {
        cut.Build();
        if (!cut.IsDone()) throw std::runtime_error("Blend joins: a shape's chamfer is too large");
        return cut.Shape();
    }
    fillet.Build();
    if (!fillet.IsDone()) throw std::runtime_error("Blend joins: a shape's rounded edges are too large");
    return fillet.Shape();
}

// One part in its own frame as the shape builder draws it: centred on x / y, standing on z = 0.
// A cylinder, cone or sphere that isn't round across (w != d, or a sphere's h) is a round one
// stretched, which turns its faces into B-splines; fillets on those are slower but work.
TopoDS_Shape make_part(const json &p)
{
    const std::string kind = p.at("kind");
    const auto &sz = p.at("size");
    const double w = std::max(0.05, sz.at(0).get<double>()), d = std::max(0.05, sz.at(1).get<double>()),
                 h = std::max(0.05, sz.at(2).get<double>());
    TopoDS_Shape s;
    double sx = 1., sy = 1., sz_ = 1.;
    if (kind == "box") {
        s = BRepPrimAPI_MakeBox(gp_Pnt(-w / 2, -d / 2, 0), w, d, h).Solid();
    } else if (kind == "cylinder") {
        s = BRepPrimAPI_MakeCylinder(gp_Ax2(gp_Pnt(0, 0, 0), gp_Dir(0, 0, 1)), w / 2, h).Solid();
        sy = d / w;
    } else if (kind == "cone") {
        const double top = std::clamp(p.value("top", 0.), 0., 1.);
        s = BRepPrimAPI_MakeCone(gp_Ax2(gp_Pnt(0, 0, 0), gp_Dir(0, 0, 1)), w / 2, w / 2 * top, h).Solid();
        sy = d / w;
    } else if (kind == "sphere") {
        s = BRepPrimAPI_MakeSphere(gp_Pnt(0, 0, w / 2), w / 2).Solid();
        sy = d / w;
        sz_ = h / w;
    } else {
        throw std::runtime_error("Blend joins: unsupported shape " + kind);
    }
    if (std::abs(sy - 1.) > 1e-6 || std::abs(sz_ - 1.) > 1e-6) {
        gp_GTrsf g;
        g.SetValue(1, 1, sx);
        g.SetValue(2, 2, sy);
        g.SetValue(3, 3, sz_);
        s = BRepBuilderAPI_GTransform(s, g, true).Shape();
    }
    const double r = p.value("round", 0.);
    if (r > 0.) s = round_all(s, r, p.value("chamfer", false));

    // The part's place: rigid, maybe mirrored. Every part above is symmetric about x = 0, so a
    // mirrored matrix M equals (M with its x column negated) applied to the part: always a rotation.
    const auto &m = p.at("m");
    auto at = [&](int i, int j) { return m.at(i * 4 + j).get<double>(); };
    double a[3][4];
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 4; ++j) a[i][j] = at(i, j);
    const double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                     + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
    if (det < 0)
        for (int i = 0; i < 3; ++i) a[i][0] = -a[i][0];
    gp_Trsf t;
    t.SetValues(a[0][0], a[0][1], a[0][2], a[0][3], a[1][0], a[1][1], a[1][2], a[1][3], a[2][0], a[2][1], a[2][2], a[2][3]);
    return BRepBuilderAPI_Transform(s, t, true).Shape();
}

// Coplanar or tangent faces left split by a boolean are merged first (a fillet along a flat seam
// fails); `edges` follow the merge through its history, the ones it removed are dropped.
TopoDS_Shape unify(const TopoDS_Shape &shape, std::vector<TopoDS_Shape> &edges)
{
    ShapeUpgrade_UnifySameDomain u(shape, true, true, false);
    u.Build();
    const auto &hist = u.History();
    std::vector<TopoDS_Shape> kept;
    for (const TopoDS_Shape &e : edges) {
        if (hist->IsRemoved(e)) continue;
        const NCollection_List<TopoDS_Shape> &mod = hist->Modified(e);
        if (mod.IsEmpty()) kept.push_back(e);
        else
            for (const TopoDS_Shape &n : mod) kept.push_back(n);
    }
    edges.swap(kept);
    return u.Shape();
}

// Rounds `edges` (from a boolean's SectionEdges) of `shape` by `radius`; [what] names the step in the error.
TopoDS_Shape round_edges(const TopoDS_Shape &shape, std::vector<TopoDS_Shape> edges, double radius, const char *what)
{
    TopoDS_Shape s = unify(shape, edges);
    NCollection_IndexedMap<TopoDS_Shape, TopTools_ShapeMapHasher> all;
    TopExp::MapShapes(s, TopAbs_EDGE, all);
    // Once each: merged seams can map several section edges to one edge.
    NCollection_IndexedMap<TopoDS_Shape, TopTools_ShapeMapHasher> pick;
    for (const TopoDS_Shape &e : edges)
        if (all.Contains(e)) pick.Add(e);
    if (pick.IsEmpty()) {
        // Shapes that only touch flush leave no edge; section edges missing from the result would be a bug.
        if (!edges.empty()) throw std::runtime_error(std::string("Blend joins: found no ") + what + " to round");
        return s;
    }
    BRepFilletAPI_MakeFillet mk(s);
    for (int i = 1; i <= pick.Extent(); ++i) mk.Add(radius, TopoDS::Edge(pick.FindKey(i)));
    mk.Build();
    if (!mk.IsDone())
        throw std::runtime_error(std::string("Blend joins: rounding the ") + what + " failed (try a smaller radius)");
    return mk.Shape();
}

std::vector<TopoDS_Shape> section_edges(BRepAlgoAPI_BuilderAlgo &op)
{
    std::vector<TopoDS_Shape> out;
    for (const TopoDS_Shape &e : op.SectionEdges()) out.push_back(e);
    return out;
}

} // namespace

json OrcaEngine::blend_solids(const json &parts, double radius, const std::string &out)
{
    if (radius <= 0.) throw std::runtime_error("Blend joins: the radius must be above 0");
    NCollection_List<TopoDS_Shape> solids, holes;
    try {
        for (const json &p : parts) (p.value("hole", false) ? holes : solids).Append(make_part(p));
        if (solids.IsEmpty()) throw std::runtime_error("Blend joins: the group has no solid shapes");

        TopoDS_Shape shape = solids.First();
        if (solids.Size() > 1) {
            NCollection_List<TopoDS_Shape> args, tools;
            args.Append(solids.First());
            bool first = true;
            for (const TopoDS_Shape &s : solids) {
                if (!first) tools.Append(s);
                first = false;
            }
            BRepAlgoAPI_Fuse fuse;
            fuse.SetArguments(args);
            fuse.SetTools(tools);
            fuse.Build();
            if (!fuse.IsDone() || fuse.HasErrors()) throw std::runtime_error("Blend joins: joining the shapes failed");
            shape = round_edges(fuse.Shape(), section_edges(fuse), radius, "joins");
        }
        if (!holes.IsEmpty()) {
            NCollection_List<TopoDS_Shape> args;
            args.Append(shape);
            BRepAlgoAPI_Cut cut;
            cut.SetArguments(args);
            cut.SetTools(holes);
            cut.Build();
            if (!cut.IsDone() || cut.HasErrors()) throw std::runtime_error("Blend joins: cutting the holes failed");
            shape = round_edges(cut.Shape(), section_edges(cut), radius, "hole rims");
        }
        // Chord error about 0.02 mm and 0.3 rad: smooth on a print, a few thousand triangles per fillet.
        TriangleMesh mesh = GeometryEngine::tessellate(shape, 0.02, 0.3);
        if (mesh.empty()) throw std::runtime_error("Blend joins: the result is empty");
        if (!its_write_stl_binary(out.c_str(), "blend", mesh.its)) throw std::runtime_error("Blend joins: unable to write the mesh");
        return {{"triangles", mesh.facets_count()}};
    } catch (const Standard_Failure &e) {
        throw std::runtime_error(std::string("Blend joins: ") + (*e.what() ? e.what() : "OCCT error"));
    }
}

} // namespace orca
