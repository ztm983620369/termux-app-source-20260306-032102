cmake_minimum_required(VERSION 3.22.1)

project(termux_vulkan_renderer C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffunction-sections -fdata-sections -fno-strict-aliasing")

if(NOT CMAKE_ANDROID_NDK)
    message(FATAL_ERROR "The Android NDK is required to build termux-vulkan")
endif()

if(ANDROID_HOST_TAG)
    set(TERMUX_GLSLC "${CMAKE_ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc")
else()
    set(TERMUX_GLSLC "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64/glslc")
endif()
if(NOT EXISTS "${TERMUX_GLSLC}")
    message(FATAL_ERROR "glslc not found: ${TERMUX_GLSLC}")
endif()

set(TERMUX_SHADER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/shaders")
set(TERMUX_SHADER_OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${TERMUX_SHADER_OUT_DIR}")

add_custom_command(
    OUTPUT "${TERMUX_SHADER_OUT_DIR}/terminal.vert.inc"
    COMMAND "${TERMUX_GLSLC}" --target-env=vulkan1.0 -O -mfmt=c
        -o "${TERMUX_SHADER_OUT_DIR}/terminal.vert.inc"
        "${TERMUX_SHADER_DIR}/terminal.vert"
    DEPENDS "${TERMUX_SHADER_DIR}/terminal.vert"
    VERBATIM)

add_custom_command(
    OUTPUT "${TERMUX_SHADER_OUT_DIR}/terminal.frag.inc"
    COMMAND "${TERMUX_GLSLC}" --target-env=vulkan1.0 -O -mfmt=c
        -o "${TERMUX_SHADER_OUT_DIR}/terminal.frag.inc"
        "${TERMUX_SHADER_DIR}/terminal.frag"
    DEPENDS "${TERMUX_SHADER_DIR}/terminal.frag"
    VERBATIM)

add_custom_target(termux-vulkan-shaders
    DEPENDS
        "${TERMUX_SHADER_OUT_DIR}/terminal.vert.inc"
        "${TERMUX_SHADER_OUT_DIR}/terminal.frag.inc")

add_library(termux-vulkan SHARED terminal_vulkan_renderer.c)
add_dependencies(termux-vulkan termux-vulkan-shaders)
target_include_directories(termux-vulkan PRIVATE "${TERMUX_SHADER_OUT_DIR}")
target_compile_options(termux-vulkan PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter)
target_link_options(termux-vulkan PRIVATE -Wl,--gc-sections)
target_link_libraries(termux-vulkan PRIVATE vulkan android log jnigraphics)
