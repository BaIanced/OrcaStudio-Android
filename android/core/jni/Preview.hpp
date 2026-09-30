#pragma once

#include <string>

#include <nlohmann/json.hpp>

namespace Slic3r {
struct GCodeProcessorResult;
}

namespace orca {

// Writes the toolpath preview of a processed G-code to `dir` (extrusions.bin, travels.bin,
// markers.bin; formats in OrcaEngine::slice) and returns the per-layer index, time per extrusion
// role, travel time and value ranges for the colour schemes.
nlohmann::json write_preview(const Slic3r::GCodeProcessorResult &result, const std::string &dir);

} // namespace orca
