#include "Preview.hpp"

#include <algorithm>
#include <climits>
#include <cstdio>
#include <cmath>
#include <fstream>
#include <limits>
#include <map>
#include <stdexcept>

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

    auto close_layer = [&]() {
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
            extrusions.put(prev.position);
            extrusions.put(m.position);
            extrusions.put(float(m.extrusion_role));
            extrusions.put(m.width);
            extrusions.put(m.height);
            extrusions.put(m.feedrate);
            extrusions.put(m.fan_speed);
            extrusions.put(m.temperature);
            extrusions.put(rate);
            extrusions.put(float(m.extruder_id));
            extrusions.put(float(m.gcode_id));
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
