# Android (arm64-v8a) build of OpenSCAD's language and geometry core as libopenscad.so (C API in
# bridge/scad_bridge.h), plus the scad_smoke test executable.
#
# Builds on the libslic3r deps prefix (android/deps: Boost, CGAL, GMP/MPFR, Eigen, TBB, FreeType,
# OpenSSL, zlib, expat) and adds only what OpenSCAD needs on top, installed into its own prefix so
# the (hours long) deps cache never has to be rebuilt for it. OpenSCAD runs headless (no Qt, no
# OpenGL) with the Manifold backend on TBB.
#
#   cmake -G Ninja -S android/openscad -B ~/build/orca-android/openscad \
#         -DCMAKE_TOOLCHAIN_FILE=android/android-toolchain.cmake -DCMAKE_BUILD_TYPE=Release
#   cmake --build ~/build/orca-android/openscad --target scad_deps   # cacheable prefix
#   cmake --build ~/build/orca-android/openscad --target openscad    # lib + smoke test
cmake_minimum_required(VERSION 3.20)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()

project(OrcaStudio-android-openscad C CXX)

if (NOT ANDROID)
    message(FATAL_ERROR "This superbuild is for Android only; pass the android toolchain file.")
endif ()

include(ExternalProject)
include(ProcessorCount)
ProcessorCount(NPROC)

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif ()

set(ORCA_DEPS "$ENV{HOME}/build/orca-android/deps/OrcaSlicer_dep/usr/local" CACHE PATH "libslic3r deps prefix")
set(SCAD_PREFIX "${CMAKE_BINARY_DIR}/OpenSCAD_dep" CACHE PATH "Install prefix of OpenSCAD's own deps")
set(SCAD_OUT "${CMAKE_BINARY_DIR}/out" CACHE PATH "libopenscad.so, scad_smoke and resources")
set(DL_DIR "${CMAKE_BINARY_DIR}/../dl" CACHE PATH "Download cache")
set(SHIM "${CMAKE_CURRENT_LIST_DIR}/shim")

if (NOT EXISTS "${ORCA_DEPS}/lib")
    message(FATAL_ERROR "libslic3r deps prefix not found: ${ORCA_DEPS} (build android/deps first)")
endif ()

# Sub-projects use the same NDK settings and also search our own prefix.
set(SCAD_TOOLCHAIN "${CMAKE_BINARY_DIR}/scad-toolchain.cmake")
file(WRITE "${SCAD_TOOLCHAIN}"
    "include(\"${CMAKE_TOOLCHAIN_FILE}\")\n"
    "list(APPEND CMAKE_FIND_ROOT_PATH \"${SCAD_PREFIX}\" \"${ORCA_DEPS}\")\n")

set(SCAD_CMAKE_ARGS
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    -DCMAKE_TOOLCHAIN_FILE:FILEPATH=${SCAD_TOOLCHAIN}
    -DCMAKE_BUILD_TYPE:STRING=${CMAKE_BUILD_TYPE}
    -DCMAKE_INSTALL_PREFIX:PATH=${SCAD_PREFIX}
    -DCMAKE_INSTALL_LIBDIR:STRING=lib
    "-DCMAKE_PREFIX_PATH:STRING=${SCAD_PREFIX}\;${ORCA_DEPS}"
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DBUILD_SHARED_LIBS=OFF
    -DBUILD_TESTING=OFF)

function(scad_dep name)
    cmake_parse_arguments(A "" "" "CMAKE_ARGS" ${ARGN})
    ExternalProject_Add(dep_${name}
        EXCLUDE_FROM_ALL ON
        DOWNLOAD_DIR ${DL_DIR}/${name}
        CMAKE_ARGS ${SCAD_CMAKE_ARGS} ${A_CMAKE_ARGS}
        ${A_UNPARSED_ARGUMENTS}
        BUILD_COMMAND ${CMAKE_COMMAND} --build . -- -j${NPROC}
        INSTALL_COMMAND ${CMAKE_COMMAND} --build . --target install)
endfunction()

# --- OpenSCAD's own dependencies ---------------------------------------------------------------
scad_dep(utf8proc
    URL https://github.com/JuliaStrings/utf8proc/archive/refs/tags/v2.10.0.tar.gz
    CMAKE_ARGS -DUTF8PROC_ENABLE_TESTING=OFF -DUTF8PROC_INSTALL=ON)

scad_dep(double_conversion
    URL https://github.com/google/double-conversion/archive/refs/tags/v3.3.1.tar.gz)

scad_dep(libzip
    URL https://github.com/nih-at/libzip/releases/download/v1.11.4/libzip-1.11.4.tar.xz
    CMAKE_ARGS -DENABLE_BZIP2=OFF -DENABLE_LZMA=OFF -DENABLE_ZSTD=OFF -DENABLE_OPENSSL=OFF
               -DENABLE_GNUTLS=OFF -DENABLE_MBEDTLS=OFF -DENABLE_COMMONCRYPTO=OFF
               -DENABLE_WINDOWS_CRYPTO=OFF -DBUILD_TOOLS=OFF -DBUILD_REGRESS=OFF
               -DBUILD_EXAMPLES=OFF -DBUILD_DOC=OFF -DBUILD_OSSFUZZ=OFF)

scad_dep(libxml2
    URL https://github.com/GNOME/libxml2/archive/refs/tags/v2.14.6.tar.gz
    CMAKE_ARGS -DLIBXML2_WITH_ICONV=OFF -DLIBXML2_WITH_ICU=OFF -DLIBXML2_WITH_LZMA=OFF
               -DLIBXML2_WITH_ZLIB=OFF -DLIBXML2_WITH_PYTHON=OFF -DLIBXML2_WITH_PROGRAMS=OFF
               -DLIBXML2_WITH_TESTS=OFF -DLIBXML2_WITH_HTTP=OFF -DLIBXML2_WITH_DOCS=OFF)

scad_dep(harfbuzz
    URL https://github.com/harfbuzz/harfbuzz/releases/download/11.5.1/harfbuzz-11.5.1.tar.xz
    CMAKE_ARGS -DHB_HAVE_FREETYPE=ON -DHB_HAVE_GLIB=OFF -DHB_HAVE_ICU=OFF -DHB_HAVE_GOBJECT=OFF
               -DHB_BUILD_SUBSET=OFF -DHB_BUILD_UTILS=OFF -DHB_HAVE_INTROSPECTION=OFF)

# fontconfig only builds with meson. Cross file for the NDK's API-level clang wrappers; freetype2
# and expat come from the libslic3r prefix through pkg-config.
set(_ndk_bin "${ANDROID_TOOLCHAIN_ROOT}/bin")
set(_triple aarch64-linux-android${ANDROID_PLATFORM_LEVEL})
set(MESON_CROSS "${CMAKE_BINARY_DIR}/meson-android.ini")
file(WRITE "${MESON_CROSS}" "[binaries]
c = '${_ndk_bin}/${_triple}-clang'
cpp = '${_ndk_bin}/${_triple}-clang++'
ar = '${_ndk_bin}/llvm-ar'
strip = '${_ndk_bin}/llvm-strip'
ranlib = '${_ndk_bin}/llvm-ranlib'
pkg-config = 'pkg-config'

[properties]
pkg_config_libdir = ['${SCAD_PREFIX}/lib/pkgconfig', '${ORCA_DEPS}/lib/pkgconfig', '${ORCA_DEPS}/share/pkgconfig']

[built-in options]
c_args = ['-fPIC', '-O2', '-DNDEBUG']
cpp_args = ['-fPIC', '-O2', '-DNDEBUG']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
")

ExternalProject_Add(dep_fontconfig
    EXCLUDE_FROM_ALL ON
    URL https://gitlab.freedesktop.org/api/v4/projects/890/packages/generic/fontconfig/2.17.1/fontconfig-2.17.1.tar.xz
    DOWNLOAD_DIR ${DL_DIR}/fontconfig
    CONFIGURE_COMMAND env PKG_CONFIG_SYSROOT_DIR= meson setup <BINARY_DIR> <SOURCE_DIR>
        --cross-file ${MESON_CROSS} --prefix ${SCAD_PREFIX} --libdir lib --buildtype release
        --default-library static --wrap-mode nodownload
        -Ddoc=disabled -Dtests=disabled -Dtools=disabled -Dnls=disabled -Dcache-build=disabled
        -Diconv=disabled -Dxml-backend=expat -Dfontations=disabled
    BUILD_COMMAND ninja -C <BINARY_DIR>
    INSTALL_COMMAND ninja -C <BINARY_DIR> install)

add_custom_target(scad_deps
    COMMAND ${CMAKE_COMMAND} -E touch ${SCAD_PREFIX}/.complete)
add_dependencies(scad_deps dep_utf8proc dep_double_conversion dep_libzip dep_libxml2 dep_harfbuzz dep_fontconfig)

# A prefix restored from the CI cache has no ExternalProject stamps; don't rebuild it.
if (EXISTS "${SCAD_PREFIX}/.complete")
    set(_openscad_depends "")
else ()
    set(_openscad_depends DEPENDS scad_deps)
endif ()

# --- OpenSCAD ----------------------------------------------------------------------------------
# Upstream's command line front end (src/openscad.cc) is replaced by bridge/scad_bridge.cc; it is
# the only user of boost::program_options, which the libslic3r Boost build doesn't have.
ExternalProject_Add(dep_openscad
    EXCLUDE_FROM_ALL ON
    ${_openscad_depends}
    GIT_REPOSITORY https://github.com/openscad/openscad.git
    GIT_TAG c0ac8289f1c9db6f70891da5df3b4085b7eaf8b6
    GIT_SUBMODULES submodules/manifold submodules/Clipper2 submodules/sanitizers-cmake
                   submodules/mimalloc libraries/MCAD
    GIT_PROGRESS OFF
    UPDATE_DISCONNECTED ON
    PATCH_COMMAND ${CMAKE_COMMAND} -DSRC=<SOURCE_DIR> -DBRIDGE=${CMAKE_CURRENT_LIST_DIR}/bridge
        -P ${CMAKE_CURRENT_LIST_DIR}/patch_openscad.cmake
    CMAKE_ARGS ${SCAD_CMAKE_ARGS}
        -DCMAKE_MODULE_PATH:PATH=${SHIM}/cmake
        -DHEADLESS=ON -DNULLGL=ON -DENABLE_TESTS=OFF -DEXPERIMENTAL=ON -DUSE_MIMALLOC=OFF
        -DENABLE_CAIRO=OFF -DENABLE_HIDAPI=OFF -DENABLE_SPNAV=OFF -DENABLE_GAMEPAD=OFF
        -DENABLE_PYTHON=OFF -DOFFLINE_DOCS=OFF -DCMAKE_REQUIRE_FIND_PACKAGE_Lib3MF=OFF
        -DCMAKE_DISABLE_FIND_PACKAGE_Lib3MF=ON -DCMAKE_DISABLE_FIND_PACKAGE_Catch2=ON
        -DUSE_CCACHE=ON -DCMAKE_UNITY_BUILD=OFF
        -DBoost_USE_STATIC_LIBS=ON -DOPENSSL_ROOT_DIR=${ORCA_DEPS} -DOPENSSL_USE_STATIC_LIBS=ON
        -DHARFBUZZ_INCLUDE_DIRS=${SCAD_PREFIX}/include/harfbuzz
        -DHARFBUZZ_LIBRARIES=${SCAD_PREFIX}/lib/libharfbuzz.a
        -DFONTCONFIG_INCLUDE_DIR=${SCAD_PREFIX}/include
        -DFONTCONFIG_LIBRARIES=${SCAD_PREFIX}/lib/libfontconfig.a
    BUILD_COMMAND ${CMAKE_COMMAND} --build . --target openscad scad_smoke -- -j${NPROC}
    INSTALL_COMMAND ${CMAKE_COMMAND} -DBIN=<BINARY_DIR> -DSRC=<SOURCE_DIR> -DOUT=${SCAD_OUT}
        -DHEADER=${CMAKE_CURRENT_LIST_DIR}/bridge/scad_bridge.h -DSTRIP=${_ndk_bin}/llvm-strip -P ${CMAKE_CURRENT_LIST_DIR}/install_openscad.cmake)

add_custom_target(openscad)
add_dependencies(openscad dep_openscad)
