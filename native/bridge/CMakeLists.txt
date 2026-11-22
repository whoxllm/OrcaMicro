# OrcaMicro engine: libslic3r plus a small JNI layer, linked into one shared library.
# Added to OrcaSlicer's build tree by the OrcaMicro patch when ORCAMICRO_ENGINE is on.
#
# On a desktop the same engine builds as orcamicro_cli, which runs one slice request from a JSON
# file. It is how the engine is tested without a phone.

set(ORCAMICRO_ENGINE_SOURCES engine.cpp engine.hpp cad.cpp cad.hpp nanosvg.cpp plugins.hpp)
set(ORCAMICRO_ENGINE_LIBS libslic3r cereal::cereal boost_libs orcamicro_openssl_md5)

if (ANDROID)
    # NDK appends its toolchain sysroot to CMAKE_PREFIX_PATH; the dependency prefix is always entry 0.
    list(GET CMAKE_PREFIX_PATH 0 _orcamicro_dep_prefix)
    add_library(orca_python SHARED IMPORTED)
    set_target_properties(orca_python PROPERTIES
        IMPORTED_LOCATION "${_orcamicro_dep_prefix}/lib/libpython3.12.so"
        # Chaquopy's libpython has no DT_SONAME; avoid embedding the build-machine absolute path.
        IMPORTED_NO_SONAME TRUE
        INTERFACE_INCLUDE_DIRECTORIES "${_orcamicro_dep_prefix}/include/python3.12"
    )

    set(_plugin_dir "${CMAKE_SOURCE_DIR}/src/slic3r/plugin")
    set(ORCAMICRO_PLUGIN_SOURCES
        plugins.cpp
        ${_plugin_dir}/PythonInterpreter.cpp
        ${_plugin_dir}/PythonPluginBridge.cpp
        ${_plugin_dir}/host/PluginHost.cpp
        ${_plugin_dir}/host/PluginHostGeometry.cpp
        ${_plugin_dir}/host/PluginHostMesh.cpp
        ${_plugin_dir}/host/PluginHostModel.cpp
        ${_plugin_dir}/host/PluginHostSlicing.cpp
        ${_plugin_dir}/pluginTypes/slicingPipeline/SlicingPipelinePluginCapability.cpp
        ${_plugin_dir}/pluginTypes/script/ScriptPluginCapability.cpp
        ${_plugin_dir}/pluginTypes/pages/PagesPluginCapability.cpp
        ${_plugin_dir}/pluginTypes/printerAgent/PrinterAgentPluginCapability.cpp
        ${_plugin_dir}/pluginTypes/resourceTexture/ResourceTexturePluginCapability.cpp
    )

    add_library(orcamicro_engine SHARED ${ORCAMICRO_ENGINE_SOURCES} ${ORCAMICRO_PLUGIN_SOURCES} jni.cpp)
    target_compile_definitions(orcamicro_engine PRIVATE PYBIND11_SIMPLE_GIL_MANAGEMENT)
    target_include_directories(orcamicro_engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../shims/plugin-gui
        ${CMAKE_SOURCE_DIR}/src/slic3r
        ${_plugin_dir}
        ${CMAKE_SOURCE_DIR}/src/slic3r/Utils
        ${CMAKE_SOURCE_DIR}/deps_src/pybind11/include
    )
    target_link_libraries(orcamicro_engine PRIVATE ${ORCAMICRO_ENGINE_LIBS} orca_python log)
    target_link_options(orcamicro_engine PRIVATE
        # Keep the exported surface to the JNI entry points.
        "LINKER:--exclude-libs,ALL"
        "LINKER:--gc-sections"
        "LINKER:--no-undefined"
        # Android 15+ devices may use 16 KB pages.
        "LINKER:-z,max-page-size=16384"
    )
    set(ORCAMICRO_TARGET orcamicro_engine)
else ()
    add_executable(orcamicro_cli ${ORCAMICRO_ENGINE_SOURCES} plugins_stub.cpp cli.cpp)
    target_link_libraries(orcamicro_cli PRIVATE ${ORCAMICRO_ENGINE_LIBS})
    set(ORCAMICRO_TARGET orcamicro_cli)
endif ()

target_include_directories(${ORCAMICRO_TARGET} PRIVATE
    ${CMAKE_SOURCE_DIR}/src
    ${CMAKE_SOURCE_DIR}/deps_src
)
