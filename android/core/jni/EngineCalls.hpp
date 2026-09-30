#pragma once

#include <string>

#include "OrcaEngine.hpp"

namespace orca {

// Runs engine function `method` with JSON arguments (names as in OrcaNative.kt / EngineCalls.cpp).
json dispatch(OrcaEngine &engine, const std::string &method, const json &args);

} // namespace orca
