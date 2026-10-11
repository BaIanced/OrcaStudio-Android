// libopenscad.so: the C API in scad_bridge.h over OpenSCAD's core. Follows the render path of
// OpenSCAD's own command line (src/openscad.cc: cmdline() / do_export()), which the Android build
// leaves out together with its boost::program_options dependency.
#include "scad_bridge.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <filesystem>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include <sys/resource.h>

#include <boost/property_tree/json_parser.hpp>
#include <boost/property_tree/ptree.hpp>

#include "core/Builtins.h"
#include "core/BuiltinContext.h"
#include "core/Context.h"
#include "core/EvaluationSession.h"
#include "core/ScopeContext.h"
#include "core/SourceFile.h"
#include "core/Tree.h"
#include "core/customizer/CommentParser.h"
#include "core/customizer/ParameterObject.h"
#include "core/customizer/ParameterSet.h"
#include "core/node.h"
#include "core/parsersettings.h"
#include "geometry/Geometry.h"
#include "geometry/GeometryEvaluator.h"
#include "geometry/PolySet.h"
#include "geometry/PolySetUtils.h"
#include "io/export.h"
#include "openscad.h"
#include "platform/PlatformUtils.h"
#include "utils/printutils.h"
#include "version.h"
#ifdef ENABLE_MANIFOLD
#include "geometry/manifold/ManifoldGeometry.h"
#endif
#ifdef ENABLE_CGAL
#include <CGAL/assertions.h>
#include <CGAL/assertions_behaviour.h>
#include "geometry/cgal/CGALNefGeometry.h"
#include "geometry/cgal/cgalutils.h"
#endif

namespace fs = std::filesystem;

// Defined in src/openscad.cc upstream (left out here); SourceFileCache appends it to every file.
std::string commandline_commands;

struct scad_result {
    bool ok = false;
    int dimension = 0;
    std::vector<float> triangles;
    std::string log;
};

namespace {

void log_handler(const Message &msg, void *userdata)
{
    auto *log = static_cast<std::string *>(userdata);
    *log += msg.str();
    *log += '\n';
}

// Captures OpenSCAD's messages for one call.
struct LogCapture {
    explicit LogCapture(std::string &log) { set_output_handler(&log_handler, nullptr, &log); }
    ~LogCapture() { set_output_handler(nullptr, nullptr, nullptr); }
};

void append_polyset(const PolySet &input, std::vector<float> &out)
{
    std::shared_ptr<const PolySet> ps;
    const PolySet *tri = &input;
    if (!input.isTriangular()) {
        ps = PolySetUtils::tessellate_faces(input);
        tri = ps.get();
    }
    out.reserve(out.size() + tri->indices.size() * 9);
    for (const auto &face : tri->indices) {
        if (face.size() != 3) continue;
        for (int idx : face) {
            const auto &v = tri->vertices[idx];
            out.push_back(float(v[0]));
            out.push_back(float(v[1]));
            out.push_back(float(v[2]));
        }
    }
}

// Same dispatch as append_stl() in src/io/export_stl.cc.
void append_geometry(const std::shared_ptr<const Geometry> &geom, std::vector<float> &out)
{
    if (!geom) return;
    if (const auto list = std::dynamic_pointer_cast<const GeometryList>(geom)) {
        for (const auto &item : list->getChildren()) append_geometry(item.second, out);
    } else if (const auto ps = std::dynamic_pointer_cast<const PolySet>(geom)) {
        append_polyset(*ps, out);
#ifdef ENABLE_MANIFOLD
    } else if (const auto mani = std::dynamic_pointer_cast<const ManifoldGeometry>(geom)) {
        if (const auto ps2 = mani->toPolySet()) append_polyset(*ps2, out);
#endif
#ifdef ENABLE_CGAL
    } else if (const auto nef = std::dynamic_pointer_cast<const CGALNefGeometry>(geom)) {
        if (const auto ps2 = PolySetUtils::getGeometryAsPolySet(nef)) append_polyset(*ps2, out);
#endif
    }
}

fs::path source_dir(const char *filename)
{
    if (filename && *filename) return fs::absolute(fs::path(filename)).parent_path();
    return fs::current_path();
}

// Parses source (+ the Customizer comment annotations). Returns nullptr on a syntax error.
std::unique_ptr<SourceFile> parse_source(const char *source, const char *filename)
{
    std::string text = source ? source : "";
    text += "\n\x03\n" + commandline_commands;
    const std::string name = filename ? filename : "";
    SourceFile *root = nullptr;
    if (!parse(root, text, name, name, false)) {
        delete root;
        return nullptr;
    }
    CommentParser::collectParameters(text, root);
    return std::unique_ptr<SourceFile>(root);
}

void apply_params(SourceFile *root, const char *params_json)
{
    if (!params_json || !*params_json) return;
    boost::property_tree::ptree values;
    std::istringstream in(params_json);
    boost::property_tree::read_json(in, values);
    ParameterSet set;
    for (const auto &entry : values) set[entry.first] = entry.second;
    ParameterObjects params = ParameterObjects::fromSourceFile(root);
    params.importValues(set);
    params.apply(root);
}

} // namespace

extern "C" {

int scad_init(const char *resource_dir)
{
    try {
        // Resources are looked up relative to the application path (PlatformUtils::lookupResourcesPath).
        PlatformUtils::registerApplicationPath(resource_dir && *resource_dir ? resource_dir : ".");
        // Library search path (OPENSCADPATH, user and bundled libraries), as openscad.cc's main does.
        parser_init();
        // OpenSCAD's recursion guard (StackCheck) allows RLIMIT_STACK minus a margin, and Android
        // keeps that at 8 MB, which designs with a few thousand loop iterations already exceed.
        // The app renders on a thread with a 256 MB stack (ScadService); a main thread grows up to
        // the raised limit. Must run before the first evaluation: StackCheck reads it once.
        struct rlimit lim {};
        if (getrlimit(RLIMIT_STACK, &lim) == 0) {
            const rlim_t want = rlim_t(240) << 20;
            const rlim_t cap = lim.rlim_max == RLIM_INFINITY ? want : std::min<rlim_t>(want, lim.rlim_max);
            if (lim.rlim_cur != RLIM_INFINITY && lim.rlim_cur < cap) {
                lim.rlim_cur = cap;
                setrlimit(RLIMIT_STACK, &lim);
            }
        }
#ifdef ENABLE_CGAL
        // As openscad_main(): throw instead of aborting on bad geometry.
        CGAL::set_error_behaviour(CGAL::THROW_EXCEPTION);
        CGAL::set_warning_behaviour(CGAL::THROW_EXCEPTION);
#endif
        Builtins::initialize();
        return 0;
    } catch (const std::exception &) {
        return 1;
    }
}

scad_result *scad_render(const char *source, const char *filename, const char *params_json)
{
    auto *r = new scad_result;
    LogCapture capture(r->log);
    const fs::path original = fs::current_path();
    try {
        auto root_file = parse_source(source, filename);
        if (!root_file) {
            r->log += "ERROR: Parsing failed\n";
            return r;
        }
        apply_params(root_file.get(), params_json);

        const fs::path dir = source_dir(filename);
        fs::current_path(dir);
        EvaluationSession session{dir.string()};
        ContextHandle<BuiltinContext> builtin_context{Context::create<BuiltinContext>(&session)};
        AbstractNode::resetIndexCounter();
        std::shared_ptr<const FileContext> file_context;
        std::shared_ptr<AbstractNode> absolute_root = root_file->instantiate(*builtin_context, &file_context);
        fs::current_path(original);

        std::shared_ptr<const AbstractNode> root_node = find_root_tag(absolute_root, nullptr);
        if (!root_node) root_node = absolute_root;
        if (!root_node) {
            r->ok = true; // empty design
            return r;
        }
        Tree tree(root_node, dir.string());
        GeometryEvaluator evaluator(tree);
        std::shared_ptr<const Geometry> geom = evaluator.evaluateGeometry(*tree.root(), true);
        if (geom) {
            r->dimension = geom->getDimension();
            if (r->dimension == 3) append_geometry(geom, r->triangles);
        }
        r->ok = true;
    } catch (const std::exception &e) {
        r->log += std::string("ERROR: ") + e.what() + "\n";
        std::error_code ec;
        fs::current_path(original, ec);
    }
    return r;
}

char *scad_parameters(const char *source, const char *filename)
{
    std::string log;
    LogCapture capture(log);
    try {
        auto root_file = parse_source(source, filename);
        if (!root_file) return nullptr;
        std::ostringstream out;
        export_param(root_file.get(), fs::path(filename ? filename : ""), out);
        return strdup(out.str().c_str());
    } catch (const std::exception &) {
        return nullptr;
    }
}

int scad_result_ok(const scad_result *r) { return r && r->ok ? 1 : 0; }

const float *scad_result_triangles(const scad_result *r, size_t *triangle_count)
{
    if (triangle_count) *triangle_count = r ? r->triangles.size() / 9 : 0;
    return r && !r->triangles.empty() ? r->triangles.data() : nullptr;
}

const char *scad_result_log(const scad_result *r) { return r ? r->log.c_str() : ""; }
int scad_result_dimension(const scad_result *r) { return r ? r->dimension : 0; }
void scad_result_free(scad_result *r) { delete r; }
void scad_free_string(char *s) { free(s); }
const char *scad_version(void) { return openscad_versionnumber.c_str(); }

} // extern "C"
