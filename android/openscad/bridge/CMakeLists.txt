# Added to OpenSCAD's own build tree (see ../CMakeLists.txt, PATCH_COMMAND), so the bridge links
# OpenSCADLibInternal with all of its usage requirements.
add_library(openscad SHARED ${CMAKE_CURRENT_LIST_DIR}/scad_bridge.cc)
# Static fontconfig/libzip leave their own dependencies (expat, zlib) to the final link.
find_package(ZLIB REQUIRED)
find_library(SCAD_EXPAT_LIBRARY NAMES expat libexpat REQUIRED)
target_link_libraries(openscad PRIVATE OpenSCADLibInternal ZLIB::ZLIB ${SCAD_EXPAT_LIBRARY} log)
set_target_properties(openscad PROPERTIES
    C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
# Only the C API leaves the library: the static Boost/CGAL/Clipper2/libc++ copies inside stay
# private, so they can't clash with liborca_jni.so's in the same process.
target_link_options(openscad PRIVATE
    -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--no-undefined -Wl,-z,max-page-size=16384)

add_executable(scad_smoke ${CMAKE_CURRENT_LIST_DIR}/scad_smoke.c)
target_include_directories(scad_smoke PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_link_libraries(scad_smoke PRIVATE openscad)
set_target_properties(scad_smoke PROPERTIES BUILD_RPATH "\$ORIGIN")

