cmake_minimum_required(VERSION 3.22.1)
project(closepaw_gguf_jni CXX C)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server" FORCE)

# Android CPU tuning. OpenMP and llamafile are not useful/safe defaults on Android.
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP on Android" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile kernels on Android" FORCE)

# Enable Vulkan backend. llama.cpp will still retain the CPU backend and can
# fall back to CPU when no usable Vulkan device is available at runtime.
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "Disable Vulkan validation checks" FORCE)
set(GGML_VULKAN_DEBUG OFF CACHE BOOL "Disable Vulkan debug output" FORCE)
set(GGML_VULKAN_VALIDATE OFF CACHE BOOL "Disable Vulkan validation layers" FORCE)

get_filename_component(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp" ABSOLUTE)

if (NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp CMakeLists.txt not found at ${LLAMA_CPP_DIR}")
endif()

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(closepaw_gguf_jni SHARED
    closepaw_gguf_jni.cpp
)

target_include_directories(closepaw_gguf_jni PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include"
)

target_link_libraries(closepaw_gguf_jni PRIVATE
    llama
    log
)
