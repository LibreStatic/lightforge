cmake_minimum_required(VERSION 3.22.1)
project(ugallery_frame_interpolation)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libncnn.so"
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/ncnn/include/ncnn"
)

function(rife_add_shader shader)
    get_filename_component(name ${shader} NAME_WE)
    set(header "${CMAKE_CURRENT_BINARY_DIR}/${name}.comp.hex.h")
    add_custom_command(
        OUTPUT "${header}"
        COMMAND ${CMAKE_COMMAND}
            -DSHADER_SRC=${CMAKE_CURRENT_SOURCE_DIR}/rife/${shader}
            -DSHADER_COMP_HEADER=${header}
            -P ${CMAKE_CURRENT_SOURCE_DIR}/rife/generate_shader_comp_header.cmake
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/rife/${shader}
    )
    set(SHADER_HEADERS ${SHADER_HEADERS} "${header}" PARENT_SCOPE)
endfunction()

foreach(shader
    rife_preproc.comp rife_postproc.comp rife_preproc_tta.comp rife_postproc_tta.comp
    rife_flow_tta_avg.comp rife_v2_flow_tta_avg.comp rife_v4_flow_tta_avg.comp
    rife_flow_tta_temporal_avg.comp rife_v2_flow_tta_temporal_avg.comp
    rife_v4_flow_tta_temporal_avg.comp rife_out_tta_temporal_avg.comp
    rife_v4_timestep.comp rife_v4_timestep_tta.comp warp.comp warp_pack4.comp warp_pack8.comp
)
    rife_add_shader(${shader})
endforeach()

add_library(ugallery_frame_interpolation SHARED
    frame_interpolation_jni.cpp
    rife/rife.cpp
    rife/warp.cpp
    ${SHADER_HEADERS}
)
target_include_directories(ugallery_frame_interpolation PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/rife
)
target_compile_definitions(ugallery_frame_interpolation PRIVATE NCNN_VULKAN=1)
target_link_libraries(ugallery_frame_interpolation ncnn android jnigraphics log vulkan)
