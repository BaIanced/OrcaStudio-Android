#pragma once

#include <array>
#include <string>
#include <vector>

#include "libslic3r/GCode/ThumbnailData.hpp"

namespace Slic3r {
class Model;
class ModelObject;
class ModelVolume;
}

namespace orca {

// "#RRGGBB" (or "#RRGGBBAA") to linear 0..1 RGB; grey for anything unparsable.
std::array<float, 3> parse_color(const std::string &hex);

// 0-based filament of a volume: its own "extruder" setting, else its object's, else the first.
int filament_of(const Slic3r::ModelObject &obj, const Slic3r::ModelVolume &vol);

// Software-rendered isometric view of the model's printable parts on a transparent background,
// for G-code and 3MF thumbnails (there is no GL context while slicing on Android). Parts are tinted
// with the colour of their filament (`colors`, 0-based by filament). Pixel rows are bottom-up like
// the desktop's glReadPixels output that the thumbnail encoders expect.
Slic3r::ThumbnailData render_thumbnail(const Slic3r::Model &model, unsigned width, unsigned height,
                                       const std::vector<std::array<float, 3>> &colors);

} // namespace orca
