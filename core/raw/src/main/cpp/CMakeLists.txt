cmake_minimum_required(VERSION 3.22.1)
project(lightforge_raw LANGUAGES CXX)

set(LIBRAW_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/libraw)
file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS ${LIBRAW_ROOT}/src/*.cpp)
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")

add_library(lightforge_raw SHARED raw_jni.cpp ${LIBRAW_SOURCES})
target_include_directories(lightforge_raw PRIVATE ${LIBRAW_ROOT} ${LIBRAW_ROOT}/internal)
target_compile_definitions(lightforge_raw PRIVATE
    LIBRAW_NOTHREADS
    LIBRAW_NO_LCMS
    LIBRAW_NO_JASPER
    LIBRAW_NO_JPEG
    LIBRAW_NO_OPENMP
)
target_compile_options(lightforge_raw PRIVATE -O2 -Wno-deprecated-declarations -Wno-unused-parameter)
target_link_libraries(lightforge_raw android log z)
