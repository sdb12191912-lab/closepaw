cmake_minimum_required(VERSION 3.22.1)
project(closepaw_gguf_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build llama common support" FORCE)

# Android CPU tuning.
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP on Android" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile kernels on Android" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Do not pin one host CPU ISA" FORCE)

# Build CPU variants when supported by this pinned llama.cpp revision.
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "Build all Android CPU variants" FORCE)

# Keep static linking for now. The pinned b4832 revision requires
# BUILD_SHARED_LIBS=ON for GGML_BACKEND_DL, while our JNI integration is
# intentionally static to avoid conflicts with the existing Leap libllama.so.
# Dynamic backend loading will be revisited together with a newer llama.cpp.
set(GGML_BACKEND_DL OFF CACHE BOOL "Disable dynamic GGML backend loading on pinned static build" FORCE)

# Vulkan backend. llama.cpp keeps CPU fallback available.
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "Disable Vulkan validation checks" FORCE)
set(GGML_VULKAN_DEBUG OFF CACHE BOOL "Disable Vulkan debug output" FORCE)
set(GGML_VULKAN_VALIDATE OFF CACHE BOOL "Disable Vulkan validation layers" FORCE)

# Release-style native optimisation is useful even in the debug APK used for
# inference diagnostics. Do not optimise for size (-Os): LLM kernels benefit
# materially from -O3/SIMD/loop optimisation.
add_compile_options(-O3 -DNDEBUG -fvisibility=hidden -ffunction-sections -fdata-sections)

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
    android
    log
)
