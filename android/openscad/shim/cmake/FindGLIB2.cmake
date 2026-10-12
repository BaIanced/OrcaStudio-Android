# Replaces OpenSCAD's FindGLIB2 (pkg-config glib-2.0): the Android build uses the header-only
# shim in ../glib.h (plus libintl.h), backed by utf8proc.
find_library(GLIB2_UTF8PROC_LIBRARY NAMES utf8proc)
set(GLIB2_INCLUDE_DIRS "${CMAKE_CURRENT_LIST_DIR}/..")
set(GLIB2_LIBRARIES ${GLIB2_UTF8PROC_LIBRARY})
set(GLIB2_VERSION 2.80.0)
include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(GLIB2 REQUIRED_VARS GLIB2_UTF8PROC_LIBRARY VERSION_VAR GLIB2_VERSION)
