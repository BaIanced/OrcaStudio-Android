# open-bamboo-networking (obn), embedded for signed Bambu Lab LAN printing.
#
# https://github.com/ClusterM/open-bamboo-networking (AGPL-3.0), pinned below by tag + commit.
# obn-android.patch makes its CMake build the agent as a static library on Android (vendored
# libcurl, static OpenSSL from the deps prefix) and skips the desktop-only plugin extras. The
# static library is linked into liborca_jni.so so obn and the app share one libc++: obn's entry
# points pass std::string / std::function, which must not cross a shared-library boundary with
# two copies of c++_static.
#
# Included from android/core/CMakeLists.txt. Defines target `bambu_networking` and the variables
# OBN_INCLUDE_DIR and OBN_COMPILE_DEFINITIONS for the JNI bridge (jni/ObnBridge.cpp).

include(FetchContent)

set(OBN_GIT_TAG v2.2.0)
set(OBN_GIT_COMMIT_PIN 5e6a359c71a0c07476f5d372bf7ad0f2b43efe94)

# Plugin ABI series this build speaks (BBL::PrintParams layout). Matches the 02.08.01 series of
# Bambu Studio / OrcaSlicer 02.08.01.55, the version the bundled OrcaSlicer sources report.
set(OBN_VERSION "02.08.01.99" CACHE STRING "" FORCE)
set(OBN_EMBED_STATIC ON CACHE BOOL "" FORCE)
set(OBN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
# OpenSSL 3.x from the Android deps superbuild (android/deps), linked statically.
set(OPENSSL_ROOT_DIR "${ORCA_DEPS_PREFIX}" CACHE PATH "" FORCE)

find_package(Git REQUIRED)
FetchContent_Declare(obn
    GIT_REPOSITORY https://github.com/ClusterM/open-bamboo-networking.git
    GIT_TAG        ${OBN_GIT_COMMIT_PIN} # ${OBN_GIT_TAG}
    GIT_SHALLOW    FALSE
    PATCH_COMMAND  ${GIT_EXECUTABLE} apply --whitespace=nowarn ${CMAKE_CURRENT_LIST_DIR}/obn-android.patch
    UPDATE_DISCONNECTED TRUE)
FetchContent_MakeAvailable(obn)

if(NOT TARGET bambu_networking)
    message(FATAL_ERROR "obn: target bambu_networking was not created")
endif()
get_target_property(_obn_type bambu_networking TYPE)
if(NOT _obn_type STREQUAL "STATIC_LIBRARY")
    message(FATAL_ERROR "obn: expected a static library, got ${_obn_type} (patch not applied?)")
endif()

set(OBN_INCLUDE_DIR "${obn_SOURCE_DIR}/include")
# ABI_VERSION gates PrintParams fields; the bridge must see the same value obn was built with.
get_target_property(_obn_defs bambu_networking COMPILE_DEFINITIONS)
set(OBN_COMPILE_DEFINITIONS "")
foreach(_d IN LISTS _obn_defs)
    if(_d MATCHES "^ABI_VERSION=")
        list(APPEND OBN_COMPILE_DEFINITIONS "${_d}")
    endif()
endforeach()
if(NOT OBN_COMPILE_DEFINITIONS)
    message(FATAL_ERROR "obn: could not read ABI_VERSION from bambu_networking")
endif()
message(STATUS "obn: ${OBN_GIT_TAG} (${OBN_GIT_COMMIT_PIN}) embedded, ${OBN_COMPILE_DEFINITIONS}")
