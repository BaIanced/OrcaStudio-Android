# Builds libslic3r for Android together with the JNI bridge (liborca_jni.so) the app loads.
#
# Replaces the upstream top-level CMakeLists.txt, which unconditionally pulls in the GUI stack
# (wxWidgets, OpenGL, GLFW, CURL, Python, DBus). Only the slicing core is configured here.
#
#   cmake -G Ninja -S android/core -B ~/build/orca-android/core \
#         -DCMAKE_TOOLCHAIN_FILE=android/android-toolchain.cmake -DCMAKE_BUILD_TYPE=Release
cmake_minimum_required(VERSION 3.20)
if(${CMAKE_VERSION} VERSION_GREATER_EQUAL "4.0")
    set(CMAKE_POLICY_VERSION_MINIMUM 3.13 CACHE STRING "" FORCE)
endif()
if(POLICY CMP0167)
    cmake_policy(SET CMP0167 NEW)
endif()

project(OrcaSlicer-android-core C CXX)

set(ORCA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../src-orca" CACHE PATH "OrcaSlicer source tree")
get_filename_component(ORCA_SRC "${ORCA_SRC}" ABSOLUTE)
set(ORCA_DEPS_PREFIX "$ENV{HOME}/build/orca-android/deps/OrcaSlicer_dep/usr/local" CACHE PATH "Android deps prefix")

# libslic3r's CMakeLists reads CMAKE_PREFIX_PATH directly to find OCCT.
set(CMAKE_PREFIX_PATH "${ORCA_DEPS_PREFIX}")
list(APPEND CMAKE_FIND_ROOT_PATH "${ORCA_DEPS_PREFIX}")
list(APPEND CMAKE_MODULE_PATH "${ORCA_SRC}/cmake/modules")
# OpenCV installs in its Android SDK layout.
set(OpenCV_DIR "${ORCA_DEPS_PREFIX}/sdk/native/jni")

include("${ORCA_SRC}/version.inc")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(IS_CROSS_COMPILE TRUE)
set(IS_CLANG_CL FALSE)
set(SLIC3R_STATIC ON)
set(SLIC3R_GUI OFF)
set(SLIC3R_CAD OFF)
set(SLIC3R_ENC_CHECK OFF CACHE BOOL "" FORCE)

add_compile_options(-fsigned-char -w)
add_compile_definitions(NDEBUG)
# Same (harmless) string-handling defines the desktop build sets globally.
add_definitions(-DwxUSE_UNICODE -D_UNICODE -DUNICODE -DWXINTL_NO_GETTEXT_MACRO -DwxNO_UNSAFE_WXSTRING_CONV)

set(LIBDIR ${ORCA_SRC}/src)
set(LIBDIR_BIN ${CMAKE_CURRENT_BINARY_DIR}/src)
include_directories(SYSTEM ${LIBDIR})
include_directories(SYSTEM ${LIBDIR_BIN}/dev-utils/platform)
include_directories(SYSTEM ${LIBDIR}/libigl)

# --- Third-party packages from the Android deps prefix ---------------------------------------
set(Boost_USE_STATIC_LIBS ON)
set(Boost_NO_SYSTEM_PATHS TRUE)
find_package(Boost 1.83.0 REQUIRED COMPONENTS system filesystem thread log log_setup locale regex chrono atomic date_time iostreams program_options nowide)
find_package(Eigen3 5.0.1 REQUIRED)

add_library(boost_libs INTERFACE)
add_library(boost_headeronly INTERFACE)
target_include_directories(boost_headeronly SYSTEM INTERFACE ${Boost_INCLUDE_DIRS})
target_link_libraries(boost_libs INTERFACE boost_headeronly ${Boost_LIBRARIES})

set(TBB_STATIC 1)
find_package(TBB REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Freetype REQUIRED)
find_package(ZLIB REQUIRED)
find_package(EXPAT REQUIRED)
find_package(PNG REQUIRED)
find_package(NLopt 1.4 REQUIRED)
find_package(cereal REQUIRED)
if (NOT TARGET cereal::cereal)
    set_target_properties(cereal PROPERTIES IMPORTED_GLOBAL TRUE)
    add_library(cereal::cereal ALIAS cereal)
else ()
    set_target_properties(cereal::cereal PROPERTIES IMPORTED_GLOBAL TRUE)
endif ()

# libslic3r links fontconfig on every non-Apple Unix; Android has none and OCCT's font code is
# not used by the slicing path, so an empty stand-in keeps the upstream link line intact.
add_library(fontconfig INTERFACE)

function(slic3r_remap_configs targets from_Cfg to_Cfg)
endfunction()

# Needed by SLA hollowing, which libslic3r always compiles.
set(OPENVDB_USE_STATIC_LIBS ON)
set(USE_BLOSC TRUE)
find_package(OpenVDB 5.0 REQUIRED COMPONENTS openvdb)

# --- Vendored sources (subset of deps_src/ that libslic3r uses) ------------------------------
set(_deps_src ${ORCA_SRC}/deps_src)
foreach(_dir agg ankerl earcut fast_float nanosvg nlohmann stb_dxt
             Shiny admesh clipper2 expat glu-libtess libigl libnest2d mcut miniz qhull qoi semver)
    add_subdirectory(${_deps_src}/${_dir} ${CMAKE_BINARY_DIR}/deps_src/${_dir})
endforeach()
include_directories(SYSTEM ${_deps_src})

# --- libslic3r ---------------------------------------------------------------------------------
add_subdirectory(${ORCA_SRC}/src/dev-utils ${CMAKE_BINARY_DIR}/src/dev-utils)
find_package(libnoise REQUIRED)
add_subdirectory(${ORCA_SRC}/src/libslic3r ${CMAKE_BINARY_DIR}/src/libslic3r)

# --- JNI bridge --------------------------------------------------------------------------------
# Process option dependency rules, copied from the desktop GUI at build time.
set(_toggles_src ${ORCA_SRC}/src/slic3r/GUI/ConfigManipulation.cpp)
set(_toggles_out ${CMAKE_CURRENT_BINARY_DIR}/generated/OptionTogglesGenerated.cpp)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
add_custom_command(
    OUTPUT ${_toggles_out}
    COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_LIST_DIR}/../scripts/extract_option_toggles.py ${_toggles_src} ${_toggles_out}
    DEPENDS ${_toggles_src} ${CMAKE_CURRENT_LIST_DIR}/../scripts/extract_option_toggles.py
    COMMENT "Extracting option toggle rules from ConfigManipulation.cpp")

# Colour conversions libslic3r's FlushVolCalc needs; upstream keeps them in the GUI sources.
set(_color_src ${ORCA_SRC}/src/slic3r/Utils/ColorSpaceConvert.cpp)
set(_color_out ${CMAKE_CURRENT_BINARY_DIR}/generated/ColorSpaceConvertGenerated.cpp)
add_custom_command(
    OUTPUT ${_color_out}
    COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_LIST_DIR}/../scripts/extract_color_space.py ${_color_src} ${_color_out}
    DEPENDS ${_color_src} ${CMAKE_CURRENT_LIST_DIR}/../scripts/extract_color_space.py
    COMMENT "Extracting colour space conversions from ColorSpaceConvert.cpp")

add_library(orca_jni SHARED
    jni/orca_jni.cpp
    jni/EngineCalls.cpp jni/EngineCalls.hpp
    jni/OrcaEngine.cpp jni/OrcaEngine.hpp
    jni/OrcaScene.cpp
    jni/OrcaTools.cpp
    jni/OrcaExtras.cpp
    jni/OrcaProject.cpp
    jni/Preview.cpp jni/Preview.hpp
    jni/Thumbnails.cpp jni/Thumbnails.hpp
    jni/OptionToggles.hpp ${_toggles_out}
    ${_color_out}
    jni/nanosvg_impl.cpp
    jni/ObnBridge.cpp
    jni/OrcaPlugins.cpp)
target_include_directories(orca_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/jni)
target_link_libraries(orca_jni PRIVATE libslic3r boost_libs log android)

# Orca plugins (embedded CPython + OrcaSlicer's plugin host).
include(${CMAKE_CURRENT_LIST_DIR}/plugins.cmake)
target_link_libraries(orca_jni PRIVATE orca_plugins)

# Signed Bambu Lab LAN printing (open-bamboo-networking, statically linked).
include(${CMAKE_CURRENT_LIST_DIR}/../obn/obn.cmake)
set_source_files_properties(jni/ObnBridge.cpp PROPERTIES
    INCLUDE_DIRECTORIES "${OBN_INCLUDE_DIR}"
    COMPILE_DEFINITIONS "${OBN_COMPILE_DEFINITIONS}")
target_link_libraries(orca_jni PRIVATE bambu_networking)
# Keep the .so small; only the JNI entry points need to be exported.
target_link_options(orca_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
set_target_properties(orca_jni PROPERTIES CXX_VISIBILITY_PRESET hidden)
