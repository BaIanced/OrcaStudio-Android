cmake_minimum_required(VERSION 3.22)
project(orca_xr CXX)

# OpenXR comes from the loader AAR's prefab package (build.gradle.kts).
find_package(OpenXR REQUIRED CONFIG)

add_library(orca_xr SHARED xr_shim.cpp)
target_compile_options(orca_xr PRIVATE -Wall -Wextra)
target_link_libraries(orca_xr PRIVATE OpenXR::openxr_loader EGL GLESv3 log)
