# OrcaMicro engine: libslic3r plus a small JNI layer, linked into one shared library.
# Added to OrcaSlicer's build tree by the OrcaMicro patch when ORCAMICRO_ENGINE is on.
#
# On a desktop the same engine builds as orcamicro_cli, which runs one slice request from a JSON
# file. It is how the engine is tested without a phone.

set(ORCAMICRO_ENGINE_SOURCES engine.cpp engine.hpp nanosvg.cpp)
set(ORCAMICRO_ENGINE_LIBS libslic3r cereal::cereal boost_libs orcamicro_openssl_md5)

if (ANDROID)
    add_library(orcamicro_engine SHARED ${ORCAMICRO_ENGINE_SOURCES} jni.cpp)
    target_link_libraries(orcamicro_engine PRIVATE ${ORCAMICRO_ENGINE_LIBS} log)
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
    add_executable(orcamicro_cli ${ORCAMICRO_ENGINE_SOURCES} cli.cpp)
    target_link_libraries(orcamicro_cli PRIVATE ${ORCAMICRO_ENGINE_LIBS})
    set(ORCAMICRO_TARGET orcamicro_cli)
endif ()

target_include_directories(${ORCAMICRO_TARGET} PRIVATE
    ${CMAKE_SOURCE_DIR}/src
    ${CMAKE_SOURCE_DIR}/deps_src
)
