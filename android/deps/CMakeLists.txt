# Android (arm64-v8a) superbuild for the dependencies of libslic3r.
#
# Reuses the upstream recipes in src-orca/deps/<Name>/<Name>.cmake wherever they cross-compile
# cleanly, and replaces the autotools/perl-driven ones (GMP, MPFR, OpenSSL) with Android-aware
# variants. GUI-only dependencies (wxWidgets, GLEW, GLFW, OpenCSG, FFMPEG, CURL, python3) are
# intentionally absent.
#
# Configure with:
#   cmake -G Ninja -S android/deps -B ~/build/orca-android/deps \
#         -DCMAKE_TOOLCHAIN_FILE=android/android-toolchain.cmake -DCMAKE_BUILD_TYPE=Release
cmake_minimum_required(VERSION 3.20)
if(${CMAKE_VERSION} VERSION_GREATER_EQUAL "4.0")
    set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)
endif()
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()

project(OrcaSlicer-android-deps C CXX)

if (NOT ANDROID)
    message(FATAL_ERROR "This superbuild is for Android only; pass the android toolchain file.")
endif ()

include(ExternalProject)
include(ProcessorCount)
ProcessorCount(NPROC)

set(ORCA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../src-orca" CACHE PATH "OrcaSlicer source tree")
get_filename_component(ORCA_SRC "${ORCA_SRC}" ABSOLUTE)
set(UPSTREAM_DEPS "${ORCA_SRC}/deps")

set(DEP_DOWNLOAD_DIR ${CMAKE_BINARY_DIR}/../dl CACHE PATH "Download cache")
set(DESTDIR "${CMAKE_BINARY_DIR}/OrcaSlicer_dep/usr/local" CACHE PATH "Install prefix of the deps")
set(SLIC3R_CAD OFF)
set(IN_GIT_REPO FALSE)          # the build dir is outside git, so patches apply in place
set(IS_CROSS_COMPILE TRUE)
set(DEP_CMAKE_OPTS "-DCMAKE_POSITION_INDEPENDENT_CODE=ON")
set(PATCH_CMD git apply --verbose --ignore-space-change --whitespace=fix)
find_package(Git REQUIRED)

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif ()

message(STATUS "Android deps DESTDIR: ${DESTDIR}")

# Trimmed copy of the upstream helper: always single-config, always forwards the toolchain file.
function(orcaslicer_add_cmake_project projectname)
    cmake_parse_arguments(P_ARGS "FORWARD_CONFIG" "INSTALL_DIR;BUILD_COMMAND;INSTALL_COMMAND" "CMAKE_ARGS" ${ARGN})
    ExternalProject_Add(
        dep_${projectname}
        EXCLUDE_FROM_ALL    ON
        INSTALL_DIR         ${DESTDIR}
        DOWNLOAD_DIR        ${DEP_DOWNLOAD_DIR}/${projectname}
        CMAKE_ARGS
            -DCMAKE_POLICY_VERSION_MINIMUM=3.5
            -DCMAKE_INSTALL_PREFIX:STRING=${DESTDIR}
            -DCMAKE_MODULE_PATH:STRING=${ORCA_SRC}/cmake/modules
            -DCMAKE_PREFIX_PATH:STRING=${DESTDIR}
            -DCMAKE_FIND_ROOT_PATH:STRING=${DESTDIR}
            -DCMAKE_TOOLCHAIN_FILE:STRING=${CMAKE_TOOLCHAIN_FILE}
            -DCMAKE_BUILD_TYPE:STRING=${CMAKE_BUILD_TYPE}
            -DBUILD_SHARED_LIBS:BOOL=OFF
            -DBUILD_TESTING:BOOL=OFF
            ${DEP_CMAKE_OPTS}
            ${P_ARGS_CMAKE_ARGS}
        ${P_ARGS_UNPARSED_ARGUMENTS}
        BUILD_COMMAND   ${CMAKE_COMMAND} --build . --config Release -- -j${NPROC}
        INSTALL_COMMAND ${CMAKE_COMMAND} --build . --target install --config Release
    )
endfunction()

# ---------------------------------------------------------------------------------------------
# Autotools / perl deps. They need the API-level-prefixed clang wrappers so that plain `$CC`
# targets Android; the bare clang the NDK toolchain file selects would build for the host.
set(_ndk_bin "${ANDROID_TOOLCHAIN_ROOT}/bin")
set(_triple aarch64-linux-android)
set(_cc  "${_ndk_bin}/${_triple}${ANDROID_PLATFORM_LEVEL}-clang")
set(_cxx "${_ndk_bin}/${_triple}${ANDROID_PLATFORM_LEVEL}-clang++")
set(_autotools_env env
    "CC=${_cc}" "CXX=${_cxx}"
    "AR=${_ndk_bin}/llvm-ar" "RANLIB=${_ndk_bin}/llvm-ranlib" "STRIP=${_ndk_bin}/llvm-strip"
    "CFLAGS=-O2 -DNDEBUG -fPIC" "CXXFLAGS=-O2 -DNDEBUG -fPIC")

ExternalProject_Add(dep_GMP
    EXCLUDE_FROM_ALL ON
    URL https://github.com/SoftFever/OrcaSlicer_deps/releases/download/gmp-6.2.1/gmp-6.2.1.tar.bz2
    URL_HASH SHA256=eae9326beb4158c386e39a356818031bd28f3124cf915f8c5b1dc4c7a36b4d7c
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/GMP
    PATCH_COMMAND ${PATCH_CMD} ${UPSTREAM_DEPS}/GMP/0001-GMP_GCC15.patch
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND ${_autotools_env} ./configure --host=${_triple} --prefix=${DESTDIR}
                      --enable-shared=no --enable-static=yes --enable-cxx=yes
    BUILD_COMMAND make -j${NPROC}
    INSTALL_COMMAND make install
)

ExternalProject_Add(dep_MPFR
    EXCLUDE_FROM_ALL ON
    URL https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.2.tar.bz2
        https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2
    URL_HASH SHA256=9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/MPFR
    BUILD_IN_SOURCE ON
    # Extraction resets timestamps, so make would otherwise try to rerun the tarball's automake.
    CONFIGURE_COMMAND autoreconf -f -i && ${_autotools_env} ./configure --host=${_triple} --prefix=${DESTDIR}
                      --enable-shared=no --enable-static=yes --with-gmp=${DESTDIR}
    BUILD_COMMAND make -j${NPROC}
    INSTALL_COMMAND make install
    DEPENDS dep_GMP
)

# OpenSSL 3.x instead of upstream's 1.1.1: its Apache-2.0 license is compatible with the AGPL,
# the OpenSSL 1.1.1 license is not. libslic3r only uses the (still available) digest functions.
# The android-arm64 target locates the NDK via ANDROID_NDK_ROOT.
set(_openssl_env env "ANDROID_NDK_ROOT=${ANDROID_NDK}" "ANDROID_NDK_HOME=${ANDROID_NDK}" "PATH=${_ndk_bin}:$ENV{PATH}")
ExternalProject_Add(dep_OpenSSL
    EXCLUDE_FROM_ALL ON
    URL "https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz"
    URL_HASH SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/OpenSSL
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND ${_openssl_env}
        ./Configure android-arm64 -D__ANDROID_API__=${ANDROID_PLATFORM_LEVEL}
        --prefix=${DESTDIR} --openssldir=${DESTDIR} --libdir=lib
        no-shared no-asm no-ssl3-method no-dynamic-engine no-tests no-docs no-apps
    BUILD_COMMAND ${_openssl_env} make -j${NPROC} build_libs
    INSTALL_COMMAND ${_openssl_env} make install_dev
)

# ---------------------------------------------------------------------------------------------
# CMake deps: upstream recipes, in dependency order.
set(ZLIB_PKG dep_ZLIB)
include(${UPSTREAM_DEPS}/ZLIB/ZLIB.cmake)
set(PNG_PKG dep_PNG)
include(${UPSTREAM_DEPS}/PNG/PNG.cmake)
set(EXPAT_PKG dep_EXPAT)
include(${UPSTREAM_DEPS}/EXPAT/EXPAT.cmake)
set(JPEG_PKG dep_JPEG)
include(${UPSTREAM_DEPS}/JPEG/JPEG.cmake)
set(FREETYPE_PKG dep_FREETYPE)
include(${UPSTREAM_DEPS}/FREETYPE/FREETYPE.cmake)

set(DEP_Boost_COMPONENTS system iostreams filesystem thread log locale regex date_time)
include(${UPSTREAM_DEPS}/Boost/Boost.cmake)
include(${UPSTREAM_DEPS}/Cereal/Cereal.cmake)
include(${UPSTREAM_DEPS}/Qhull/Qhull.cmake)
include(${UPSTREAM_DEPS}/TBB/TBB.cmake)
# OpenVDB is only used by SLA hollowing, but libslic3r compiles that unconditionally.
include(${UPSTREAM_DEPS}/Blosc/Blosc.cmake)
include(${UPSTREAM_DEPS}/OpenEXR/OpenEXR.cmake)
include(${UPSTREAM_DEPS}/OpenVDB/OpenVDB.cmake)
include(${UPSTREAM_DEPS}/Eigen/Eigen.cmake)
include(${UPSTREAM_DEPS}/CGAL/CGAL.cmake)
include(${UPSTREAM_DEPS}/NLopt/NLopt.cmake)
include(${UPSTREAM_DEPS}/libnoise/libnoise.cmake)
include(${UPSTREAM_DEPS}/Draco/Draco.cmake)
include(${UPSTREAM_DEPS}/Assimp/Assimp.cmake)
include(${UPSTREAM_DEPS}/OCCT/OCCT.cmake)
# OpenCV builds its Android sample apps and Java bindings by default; the recipe cannot be told
# otherwise, so the extra switches ride along in DEP_CMAKE_OPTS for this one call.
set(_dep_cmake_opts_saved "${DEP_CMAKE_OPTS}")
list(APPEND DEP_CMAKE_OPTS -DBUILD_ANDROID_PROJECTS=OFF -DBUILD_ANDROID_EXAMPLES=OFF -DBUILD_ANDROID_SERVICE=OFF
                           -DBUILD_KOTLIN_EXTENSIONS=OFF -DBUILD_FAT_JAVA_LIB=OFF -DWITH_ANDROID_MEDIANDK=OFF)
include(${UPSTREAM_DEPS}/OpenCV/OpenCV.cmake)
set(DEP_CMAKE_OPTS "${_dep_cmake_opts_saved}")

add_dependencies(dep_Boost dep_ZLIB)
add_dependencies(dep_PNG dep_ZLIB)
add_dependencies(dep_Assimp dep_ZLIB)
add_dependencies(dep_OpenEXR dep_ZLIB)

add_custom_target(deps ALL DEPENDS
    dep_ZLIB dep_PNG dep_EXPAT dep_JPEG dep_FREETYPE dep_OpenSSL
    dep_Boost dep_Cereal dep_Qhull dep_TBB dep_GMP dep_MPFR dep_Eigen dep_CGAL
    dep_NLopt dep_libnoise dep_Draco dep_Assimp dep_OCCT dep_OpenCV dep_Blosc dep_OpenEXR dep_OpenVDB
)
