// libslic3r calls nanosvg but leaves its implementation to the GUI (BitmapCache.cpp), which the
// Android build does not have; this translation unit provides it instead.
#include <cmath>
#include <cstdio>
#include <cstring>
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"
#define NANOSVGRAST_IMPLEMENTATION
#include "nanosvg/nanosvgrast.h"
