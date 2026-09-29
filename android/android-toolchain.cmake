# Wrapper around the NDK toolchain so sub-projects (ExternalProject) inherit the same settings.
set(ANDROID_ABI arm64-v8a CACHE STRING "")
set(ANDROID_PLATFORM 29 CACHE STRING "")
set(ANDROID_STL c++_static CACHE STRING "")
set(ANDROID_CPP_FEATURES "rtti exceptions" CACHE STRING "")
set(ANDROID_NDK $ENV{HOME}/Android/Sdk/ndk/28.2.13676358)
include(${ANDROID_NDK}/build/cmake/android.toolchain.cmake)
# autotools-based deps (GMP/MPFR) use this as --host
set(TOOLCHAIN_PREFIX aarch64-linux-android)
# Let find_package() see the deps prefix even though the NDK toolchain restricts search roots.
list(APPEND CMAKE_FIND_ROOT_PATH $ENV{HOME}/build/orca-android/deps/OrcaSlicer_dep/usr/local)
