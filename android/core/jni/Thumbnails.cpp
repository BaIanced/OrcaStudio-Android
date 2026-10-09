#include "Thumbnails.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

#include "libslic3r/Model.hpp"
#include "libslic3r/PrintConfig.hpp"

namespace orca {

using namespace Slic3r;

std::array<float, 3> parse_color(const std::string &hex)
{
    std::string h = hex;
    if (!h.empty() && h[0] == '#')
        h = h.substr(1);
    if (h.size() < 6)
        return {0.6f, 0.6f, 0.6f};
    try {
        const unsigned long v = std::stoul(h.substr(0, 6), nullptr, 16);
        return {((v >> 16) & 0xff) / 255.f, ((v >> 8) & 0xff) / 255.f, (v & 0xff) / 255.f};
    } catch (...) {
        return {0.6f, 0.6f, 0.6f};
    }
}

namespace {

struct Tri
{
    Vec3f a, b, c;
    std::array<float, 3> color;
};

} // namespace

int filament_of(const ModelObject &obj, const ModelVolume &vol)
{
    // Volume setting wins over the object setting; both are 1-based, 0 = inherit.
    for (const ModelConfig *cfg : {static_cast<const ModelConfig *>(&vol.config), static_cast<const ModelConfig *>(&obj.config)})
        if (cfg->has("extruder"))
            if (const int e = cfg->get().opt_int("extruder"); e > 0)
                return e - 1;
    return 0;
}

ThumbnailData render_thumbnail(const Model &model, unsigned width, unsigned height, const std::vector<std::array<float, 3>> &colors)
{
    ThumbnailData out;
    if (width == 0 || height == 0)
        return out;
    out.set(width, height);
    std::fill(out.pixels.begin(), out.pixels.end(), 0);

    // Camera: from the front-left, above the plate (like the desktop thumbnails).
    const Vec3f forward = Vec3f(0.35f, 0.8f, -0.6f).normalized();
    const Vec3f right   = forward.cross(Vec3f::UnitZ()).normalized();
    const Vec3f up      = right.cross(forward).normalized();
    const Vec3f light   = (Vec3f(-0.3f, -0.6f, 0.8f)).normalized();

    std::vector<Tri> tris;
    for (const ModelObject *obj : model.objects)
        for (const ModelInstance *inst : obj->instances) {
            if (!inst->printable)
                continue;
            for (const ModelVolume *vol : obj->volumes) {
                if (!vol->is_model_part())
                    continue;
                const int f = filament_of(*obj, *vol);
                const std::array<float, 3> color = f < int(colors.size()) ? colors[f] : std::array<float, 3>{0.9f, 0.5f, 0.2f};
                const Transform3d trafo = inst->get_matrix() * vol->get_matrix();
                const indexed_triangle_set &its = vol->mesh().its;
                for (const Vec3i32 &t : its.indices)
                    tris.push_back({(trafo * its.vertices[t[0]].cast<double>()).cast<float>(),
                                    (trafo * its.vertices[t[1]].cast<double>()).cast<float>(),
                                    (trafo * its.vertices[t[2]].cast<double>()).cast<float>(), color});
            }
        }
    if (tris.empty())
        return out;

    // Fit the projected model into the image with a margin; render at 2x for anti-aliasing.
    const int ss = 2;
    const int W = int(width) * ss, H = int(height) * ss;
    float minx = std::numeric_limits<float>::max(), maxx = -minx, miny = minx, maxy = -minx;
    for (const Tri &t : tris)
        for (const Vec3f *p : {&t.a, &t.b, &t.c}) {
            const float x = p->dot(right), y = p->dot(up);
            minx = std::min(minx, x); maxx = std::max(maxx, x);
            miny = std::min(miny, y); maxy = std::max(maxy, y);
        }
    const float scale = 0.9f * std::min(W / std::max(maxx - minx, 1e-3f), H / std::max(maxy - miny, 1e-3f));
    const float cx = (minx + maxx) / 2, cy = (miny + maxy) / 2;
    auto project = [&](const Vec3f &p) {
        return Vec3f(W / 2.f + (p.dot(right) - cx) * scale, H / 2.f + (p.dot(up) - cy) * scale, p.dot(forward));
    };

    std::vector<float>         depth(size_t(W) * H, std::numeric_limits<float>::max());
    std::vector<unsigned char> rgba(size_t(W) * H * 4, 0);
    for (const Tri &t : tris) {
        const Vec3f a = project(t.a), b = project(t.b), c = project(t.c);
        Vec3f n = (t.b - t.a).cross(t.c - t.a);
        if (n.squaredNorm() < 1e-12f)
            continue;
        n.normalize();
        const float shade = 0.35f + 0.65f * std::abs(n.dot(light));
        const unsigned char r = (unsigned char)std::clamp(t.color[0] * shade * 255.f, 0.f, 255.f);
        const unsigned char g = (unsigned char)std::clamp(t.color[1] * shade * 255.f, 0.f, 255.f);
        const unsigned char bl = (unsigned char)std::clamp(t.color[2] * shade * 255.f, 0.f, 255.f);

        const int x0 = std::max(0, int(std::floor(std::min({a.x(), b.x(), c.x()}))));
        const int x1 = std::min(W - 1, int(std::ceil(std::max({a.x(), b.x(), c.x()}))));
        const int y0 = std::max(0, int(std::floor(std::min({a.y(), b.y(), c.y()}))));
        const int y1 = std::min(H - 1, int(std::ceil(std::max({a.y(), b.y(), c.y()}))));
        const float area = (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
        if (std::abs(area) < 1e-9f)
            continue;
        for (int y = y0; y <= y1; ++y)
            for (int x = x0; x <= x1; ++x) {
                const float px = x + 0.5f, py = y + 0.5f;
                const float w0 = ((b.x() - px) * (c.y() - py) - (b.y() - py) * (c.x() - px)) / area;
                const float w1 = ((c.x() - px) * (a.y() - py) - (c.y() - py) * (a.x() - px)) / area;
                const float w2 = 1.f - w0 - w1;
                if (w0 < 0.f || w1 < 0.f || w2 < 0.f)
                    continue;
                const float z = w0 * a.z() + w1 * b.z() + w2 * c.z();
                const size_t idx = size_t(y) * W + x;
                if (z >= depth[idx])
                    continue;
                depth[idx] = z;
                unsigned char *p = &rgba[idx * 4];
                p[0] = r; p[1] = g; p[2] = bl; p[3] = 255;
            }
    }

    // Downsample (box filter); rows stay bottom-up since y grows upwards in screen space.
    for (unsigned y = 0; y < height; ++y)
        for (unsigned x = 0; x < width; ++x) {
            int acc[4] = {0, 0, 0, 0};
            for (int dy = 0; dy < ss; ++dy)
                for (int dx = 0; dx < ss; ++dx) {
                    const unsigned char *p = &rgba[(size_t(y * ss + dy) * W + (x * ss + dx)) * 4];
                    // Premultiply so transparent samples do not darken edges.
                    acc[0] += p[0] * p[3]; acc[1] += p[1] * p[3]; acc[2] += p[2] * p[3]; acc[3] += p[3];
                }
            unsigned char *o = &out.pixels[(size_t(y) * width + x) * 4];
            if (acc[3] > 0) {
                o[0] = (unsigned char)(acc[0] / acc[3]);
                o[1] = (unsigned char)(acc[1] / acc[3]);
                o[2] = (unsigned char)(acc[2] / acc[3]);
            }
            o[3] = (unsigned char)(acc[3] / (ss * ss));
        }
    return out;
}

} // namespace orca
