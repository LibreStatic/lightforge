cmake_minimum_required(VERSION 3.22.1)
project(lightforge_legacy_video LANGUAGES C CXX)

# Vendored FFmpeg (LGPL 2.1+), see ../../../third_party/ffmpeg/VENDORED.txt. Per-ABI generated
# headers and the exact source list come from scripts/ffmpeg/vendor-ffmpeg.sh.
set(FFMPEG_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/ffmpeg)
set(FFMPEG_CONFIG_DIR ${FFMPEG_ROOT}/config/${ANDROID_ABI})
include(${FFMPEG_CONFIG_DIR}/sources.cmake)

add_library(lightforge_ffmpeg STATIC ${FFMPEG_SOURCES})
target_include_directories(lightforge_ffmpeg PRIVATE ${FFMPEG_CONFIG_DIR} ${FFMPEG_ROOT})
if(EXISTS ${FFMPEG_ROOT}/compat/stdbit)
    target_include_directories(lightforge_ffmpeg PRIVATE ${FFMPEG_ROOT}/compat/stdbit)
endif()
target_compile_definitions(lightforge_ffmpeg PRIVATE
    HAVE_AV_CONFIG_H _ISOC11_SOURCE _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE PIC strtod=avpriv_strtod)
target_compile_options(lightforge_ffmpeg PRIVATE
    -std=c17 -Os -fPIC -pthread -fvisibility=hidden -ffunction-sections -fdata-sections
    -fno-math-errno -fno-signed-zeros -w)

add_library(lightforge_legacy_video SHARED ffmpeg_video_jni.cpp)
target_include_directories(lightforge_legacy_video PRIVATE ${FFMPEG_CONFIG_DIR} ${FFMPEG_ROOT})
target_compile_options(lightforge_legacy_video PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(lightforge_legacy_video PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(lightforge_legacy_video lightforge_ffmpeg android log jnigraphics)
