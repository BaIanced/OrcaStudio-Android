# PATCH_COMMAND of dep_openscad: run with -DSRC=<openscad source> -DBRIDGE=<android/openscad/bridge>.
# Idempotent (ExternalProject may run it again on a reconfigure).
file(READ "${SRC}/CMakeLists.txt" txt)
set(orig "${txt}")

# The command line front end (and with it boost::program_options) is replaced by the bridge.
string(REPLACE "COMPONENTS regex program_options" "COMPONENTS regex" txt "${txt}")
string(REPLACE " Boost::program_options" "" txt "${txt}")
string(REPLACE "  src/openscad.cc\n" "" txt "${txt}")

if (NOT txt MATCHES "android/openscad bridge")
    string(APPEND txt "\n# android/openscad bridge\nadd_subdirectory(\"${BRIDGE}\" \"\${CMAKE_BINARY_DIR}/bridge\")\n")
endif ()

foreach (needle "COMPONENTS regex program_options" "  src/openscad.cc\n")
    string(FIND "${txt}" "${needle}" pos)
    if (NOT pos EQUAL -1)
        message(FATAL_ERROR "patch_openscad: '${needle}' still present")
    endif ()
endforeach ()
if (txt STREQUAL orig)
    message(STATUS "patch_openscad: already patched")
else ()
    file(WRITE "${SRC}/CMakeLists.txt" "${txt}")
endif ()
