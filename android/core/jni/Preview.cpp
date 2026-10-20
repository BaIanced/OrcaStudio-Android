#include "Preview.hpp"

#include <algorithm>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cmath>
#include <fstream>
#include <limits>
#include <map>
#include <stdexcept>
#include <vector>

#include <boost/filesystem.hpp>

#include "libslic3r/GCode/GCodeProcessor.hpp"

namespace orca {

using namespace Slic3r;
using json = nlohmann::json;

namespace {

// Writes to "<path>.tmp" and renames it over <path> in finish(): the app memory-maps these files,
// and truncating a mapped file in place would raise SIGBUS in the reader (a rename keeps the old
// inode, and so an existing mapping, intact).
class FloatWriter
{
public:
    explicit FloatWriter(const std::string &path) : m_path(path), m_file(path + ".tmp", std::ios::binary | std::ios::trunc)
    {
        if (!m_file)
            throw std::runtime_error("Cannot write " + path);
    }
    ~FloatWriter()
    {
        if (m_file.is_open()) {
            m_file.close();
            std::remove((m_path + ".tmp").c_str());
        }
    }
    void put(float v) { m_file.write(reinterpret_cast<const char *>(&v), sizeof(float)); }
    void put(const Vec3f &v) { put(v.x()); put(v.y()); put(v.z()); }
    void finish()
    {
        m_file.close();
        if (m_file.fail() || std::rename((m_path + ".tmp").c_str(), m_path.c_str()) != 0)
            throw std::runtime_error("Cannot write " + m_path);
    }

private:
    std::string   m_path;
    std::ofstream m_file;
};

struct Range
{
    float lo = std::numeric_limits<float>::max();
    float hi = std::numeric_limits<float>::lowest();
    void  add(float v)
    {
        if (!std::isfinite(v))
            return;
        lo = std::min(lo, v);
        hi = std::max(hi, v);
    }
    json to_json() const { return lo <= hi ? json{lo, hi} : json{0., 0.}; }
};

constexpr int DEPTH_LABELS = 5;

struct Segment
{
    Vec3f a, b;
    int   role;
    float width, height, speed, fan, temperature, rate, extruder, gcode;
};

// The roles' depth classes in PATH_VS (Shaders.kt, "lift"): faces of different classes are already ordered there.
int depth_class(int role)
{
    return role == 2 ? 4 : (role == 1 || role == 3) ? 3 : (role == 6 || role == 8 || role == 11) ? 2
        : (role == 5 || role == 7 || role == 9 || role == 10) ? 1 : 0;
}

float point_segment2(float px, float py, const Vec3f &a, const Vec3f &b)
{
    const float dx = b.x() - a.x(), dy = b.y() - a.y(), l = dx * dx + dy * dy;
    const float t  = l > 0.f ? std::clamp(((px - a.x()) * dx + (py - a.y()) * dy) / l, 0.f, 1.f) : 0.f;
    const float ex = a.x() + t * dx - px, ey = a.y() + t * dy - py;
    return ex * ex + ey * ey;
}

float side(const Vec3f &a, const Vec3f &b, const Vec3f &p) { return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x()); }

// Squared distance between two segments seen from above.
float segment_distance2(const Segment &s, const Segment &t)
{
    if ((side(s.a, s.b, t.a) > 0.f) != (side(s.a, s.b, t.b) > 0.f) && (side(t.a, t.b, s.a) > 0.f) != (side(t.a, t.b, s.b) > 0.f))
        return 0.f;
    return std::min(std::min(point_segment2(t.a.x(), t.a.y(), s.a, s.b), point_segment2(t.b.x(), t.b.y(), s.a, s.b)),
                    std::min(point_segment2(s.a.x(), s.a.y(), t.a, t.b), point_segment2(s.b.x(), s.b.y(), t.a, t.b)));
}

// Depth labels 0..DEPTH_LABELS-1 for one layer's segments. Beads of one depth class on one plane overlap where
// they touch or cross; when both got the same depth rank, the winner flipped with every camera move (z-fight
// notches where support lines meet, combs between neighbouring top lines, user r325 / r329). Greedy colouring
// of the touch graph gives touching beads different labels, so PATH_VS orders them the same from every angle.
void label_layer(const std::vector<Segment> &segs, std::vector<uint8_t> &labels,
                 std::vector<std::pair<uint64_t, uint32_t>> &cells, std::vector<std::pair<uint32_t, uint32_t>> &edges)
{
    constexpr float CELL = 2.f, STEP = 1.f;
    labels.assign(segs.size(), 0);
    cells.clear();
    edges.clear();
    for (uint32_t i = 0; i < segs.size(); ++i) {
        const Segment &s = segs[i];
        const float    r = s.width * 0.5f + STEP * 0.5f + 0.05f;
        const float    len = std::hypot(s.b.x() - s.a.x(), s.b.y() - s.a.y());
        const int      n = std::min(int(len / STEP) + 1, 100000);
        for (int k = 0; k <= n; ++k) {
            const float t = float(k) / float(n), x = s.a.x() + t * (s.b.x() - s.a.x()), y = s.a.y() + t * (s.b.y() - s.a.y());
            for (int cx = int(std::floor((x - r) / CELL)); cx <= int(std::floor((x + r) / CELL)); ++cx)
                for (int cy = int(std::floor((y - r) / CELL)); cy <= int(std::floor((y + r) / CELL)); ++cy)
                    cells.emplace_back((uint64_t(uint32_t(cx)) << 32) | uint32_t(cy), i);
        }
    }
    std::sort(cells.begin(), cells.end());
    cells.erase(std::unique(cells.begin(), cells.end()), cells.end());
    for (size_t g = 0; g < cells.size();) {
        size_t e = g;
        while (e < cells.size() && cells[e].first == cells[g].first)
            ++e;
        for (size_t p = g; p < e; ++p)
            for (size_t q = p + 1; q < e; ++q) {
                const Segment &s = segs[cells[p].second], &t = segs[cells[q].second];
                if (depth_class(s.role) != depth_class(t.role) || std::abs(s.b.z() - t.b.z()) > 1e-3f)
                    continue;
                const float reach = (s.width + t.width) * 0.5f + 0.01f;
                if (segment_distance2(s, t) < reach * reach)
                    edges.emplace_back(cells[q].second, cells[p].second); // (later, earlier): cells sort by segment
            }
        g = e;
    }
    std::sort(edges.begin(), edges.end());
    edges.erase(std::unique(edges.begin(), edges.end()), edges.end());
    for (size_t e = 0; e < edges.size();) {
        const uint32_t j = edges[e].first;
        unsigned       used = 0;
        for (; e < edges.size() && edges[e].first == j; ++e)
            used |= 1u << labels[edges[e].second];
        int label = 0;
        while (label < DEPTH_LABELS && (used >> label & 1u))
            ++label;
        labels[j] = uint8_t(label < DEPTH_LABELS ? label : j % DEPTH_LABELS);
    }
}

} // namespace

json write_preview(const GCodeProcessorResult &result, const std::string &dir)
{
    boost::filesystem::create_directories(dir);
    FloatWriter extrusions(dir + "/extrusions.bin");
    FloatWriter travels(dir + "/travels.bin");
    FloatWriter markers(dir + "/markers.bin");

    constexpr size_t NORMAL = size_t(PrintEstimatedStatistics::ETimeMode::Normal);
    size_t n_extrusions = 0, n_travels = 0, n_markers = 0;
    json   layers = json::array();
    unsigned int current_layer = UINT_MAX;
    float  layer_time = 0.f, layer_z = 0.f;
    std::map<int, float> role_times;
    float  travel_time = 0.f;
    Range  speed, fan, temperature, volumetric, width, height;

    // A layer's extrusions wait here for their depth labels (label_layer).
    std::vector<Segment> pending;
    std::vector<uint8_t> labels;
    std::vector<std::pair<uint64_t, uint32_t>> cells;
    std::vector<std::pair<uint32_t, uint32_t>> edges;
    auto flush = [&]() {
        label_layer(pending, labels, cells, edges);
        for (size_t i = 0; i < pending.size(); ++i) {
            const Segment &s = pending[i];
            extrusions.put(s.a);
            extrusions.put(s.b);
            // role + 32 * (label + 1); 0 above the role (older files) makes PATH_VS fall back to direction bins.
            extrusions.put(float(s.role + 32 * (labels[i] + 1)));
            for (float v : {s.width, s.height, s.speed, s.fan, s.temperature, s.rate, s.extruder, s.gcode})
                extrusions.put(v);
        }
        pending.clear();
    };

    auto close_layer = [&]() {
        flush();
        if (!layers.empty()) {
            layers.back()["time"] = layer_time;
            layers.back()["z"]    = layer_z;
        }
    };

    for (size_t i = 1; i < result.moves.size(); ++i) {
        const auto &m    = result.moves[i];
        const auto &prev = result.moves[i - 1];
        if (m.layer_id != current_layer && m.type == EMoveType::Extrude) {
            close_layer();
            layers.push_back({{"z", m.position.z()}, {"extrusion", n_extrusions}, {"travel", n_travels}, {"marker", n_markers}, {"time", 0.}});
            current_layer = m.layer_id;
            layer_time    = 0.f;
            layer_z       = m.position.z();
        }
        const float t = m.time[NORMAL];
        layer_time += t;

        switch (m.type) {
        case EMoveType::Extrude: {
            const float rate = m.mm3_per_mm * m.feedrate;
            pending.push_back({prev.position, m.position, int(m.extrusion_role), m.width, m.height, m.feedrate, m.fan_speed,
                               m.temperature, rate, float(m.extruder_id), float(m.gcode_id)});
            ++n_extrusions;
            role_times[int(m.extrusion_role)] += t;
            layer_z = std::max(layer_z, m.position.z());
            speed.add(m.feedrate);
            fan.add(m.fan_speed);
            temperature.add(m.temperature);
            volumetric.add(rate);
            width.add(m.width);
            height.add(m.height);
            break;
        }
        case EMoveType::Travel:
            travels.put(prev.position);
            travels.put(m.position);
            ++n_travels;
            travel_time += t;
            break;
        case EMoveType::Retract:
        case EMoveType::Unretract:
        case EMoveType::Seam:
        case EMoveType::Wipe: {
            const float kind = m.type == EMoveType::Retract ? 0.f : m.type == EMoveType::Unretract ? 1.f : m.type == EMoveType::Seam ? 2.f : 3.f;
            markers.put(m.position);
            markers.put(kind);
            ++n_markers;
            break;
        }
        default: break;
        }
    }
    close_layer();
    extrusions.finish();
    travels.finish();
    markers.finish();

    json roles = json::array();
    for (const auto &[role, time] : role_times)
        roles.push_back({{"role", role}, {"time", time}, {"filament_m", 0.}, {"filament_g", 0.}});

    return {{"layers", layers},
            {"roles", roles},
            {"travel_time", travel_time},
            {"counts", {{"extrusions", n_extrusions}, {"travels", n_travels}, {"markers", n_markers}}},
            {"ranges",
             {{"speed", speed.to_json()},
              {"fan", fan.to_json()},
              {"temperature", temperature.to_json()},
              {"volumetric", volumetric.to_json()},
              {"width", width.to_json()},
              {"height", height.to_json()}}}};
}

} // namespace orca
