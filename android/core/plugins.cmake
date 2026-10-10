# Orca plugin runtime: embedded CPython (android/scripts/stage_python.py) with OrcaSlicer's plugin
# host (src/slic3r/plugin). The GUI-free upstream sources are compiled as they are; the parts that
# are bound to wxWidgets (plugin manager, capability config store, audit dialogs, host UI and app
# access) are replaced by the Android implementations in plugin/, behind the same headers.
set(ORCA_PYTHON_PREFIX "$ENV{HOME}/build/orca-android/python/prefix" CACHE PATH "Staged CPython for Android")
set(ORCA_PYTHON_VERSION 3.12)
if(NOT EXISTS "${ORCA_PYTHON_PREFIX}/include/python${ORCA_PYTHON_VERSION}/Python.h")
    message(FATAL_ERROR "CPython not staged in ${ORCA_PYTHON_PREFIX}; run android/scripts/stage_python.py")
endif()

add_library(orca_python SHARED IMPORTED)
set_target_properties(orca_python PROPERTIES
    IMPORTED_LOCATION "${ORCA_PYTHON_PREFIX}/lib/libpython${ORCA_PYTHON_VERSION}.so"
    # Chaquopy's libpython has no SONAME; without this the absolute build path ends up in DT_NEEDED.
    IMPORTED_NO_SONAME TRUE
    INTERFACE_INCLUDE_DIRECTORIES "${ORCA_PYTHON_PREFIX}/include/python${ORCA_PYTHON_VERSION}")

# The plugin host reads build-time paths from GeneratedConfig.hpp; none apply on Android (the
# Python home is found in <data dir>/python, and there is no uv).
set(ORCA_UPDATER_SIG_KEY_B64 "")
set(ORCA_UPDATER_SIG_KEY_AVAILABLE 0)
set(_bundled_python_root "")
set(ORCA_BUNDLED_UV_EXECUTABLE_CONFIG "")
configure_file(${ORCA_SRC}/src/slic3r/GeneratedConfig.hpp.in ${CMAKE_CURRENT_BINARY_DIR}/generated/slic3r/GeneratedConfig.hpp)

set(_plugin ${ORCA_SRC}/src/slic3r/plugin)
# The capability config store without its wxWidgets dialog responses (see the script).
set(_plugin_config_out ${CMAKE_CURRENT_BINARY_DIR}/generated/PluginConfigGenerated.cpp)
add_custom_command(
    OUTPUT ${_plugin_config_out}
    COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_LIST_DIR}/../scripts/extract_plugin_config.py ${_plugin}/PluginConfig.cpp ${_plugin_config_out}
    DEPENDS ${_plugin}/PluginConfig.cpp ${CMAKE_CURRENT_LIST_DIR}/../scripts/extract_plugin_config.py
    COMMENT "Extracting the plugin config store from PluginConfig.cpp")
add_library(orca_plugins STATIC
    ${_plugin}/PythonInterpreter.cpp
    ${_plugin}/PythonPluginBridge.cpp
    ${_plugin}/PluginFsUtils.cpp
    ${_plugin}/PluginLoader.cpp
    ${_plugin}/PluginHooks.cpp
    ${_plugin}/host/PluginHost.cpp
    ${_plugin}/host/PluginHostGeometry.cpp
    ${_plugin}/host/PluginHostMesh.cpp
    ${_plugin}/host/PluginHostModel.cpp
    ${_plugin}/host/PluginHostPresets.cpp
    ${_plugin}/host/PluginHostSlicing.cpp
    ${_plugin}/pluginTypes/pages/PagesPluginCapability.cpp
    ${_plugin}/pluginTypes/printerAgent/PrinterAgentPluginCapability.cpp
    ${_plugin}/pluginTypes/script/ScriptPluginCapability.cpp
    ${_plugin}/pluginTypes/slicingPipeline/SlicingPipelinePluginCapability.cpp
    plugin/AndroidPluginManager.cpp
    ${_plugin_config_out}
    plugin/AndroidPluginAudit.cpp
    plugin/AndroidPluginHost.cpp
    plugin/AndroidPluginRuntime.cpp plugin/AndroidPluginRuntime.hpp)
target_include_directories(orca_plugins PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}/generated/slic3r   # GeneratedConfig.hpp, included as a sibling
    ${_plugin}                                     # sibling includes of the generated config store
    ${ORCA_SRC}/src/slic3r                         # "plugin/..." style includes
    ${ORCA_SRC}/src/slic3r/Utils                   # IPrinterAgent.hpp (upstream: target_include_directories Utils)
    ${ORCA_SRC}/deps_src/pybind11/include
    ${CMAKE_CURRENT_LIST_DIR}/plugin)
target_include_directories(orca_plugins PUBLIC ${ORCA_SRC}/deps_src/pybind11/include ${CMAKE_CURRENT_LIST_DIR}/plugin)
target_compile_definitions(orca_plugins PRIVATE SLIC3R_ANDROID_PLUGINS=1)
target_link_libraries(orca_plugins PUBLIC libslic3r boost_libs orca_python log)
