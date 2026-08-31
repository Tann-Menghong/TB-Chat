cmake_minimum_required(VERSION 3.22.1)
project(tbchat_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# third_party/llama.cpp lives at the repository root, five levels above this file.
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/llama.cpp")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp submodule missing at ${LLAMA_CPP_DIR}.\n"
        "Run: git submodule update --init --recursive")
endif()

# CPU backend only for v1.0.0.
#
# Vulkan needs a host-side vulkan-shaders-gen tool compiled during the Android
# build, and OpenCL is Adreno-only in practice. Both are worth having, but
# neither is worth blocking the first release on -- and per the design notes, a
# GPU backend must beat the CPU on a benchmark before the app adopts it anyway,
# so shipping CPU-only costs nothing today.
set(GGML_VULKAN   OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP   OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE   OFF CACHE BOOL "" FORCE)

set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" llama.cpp EXCLUDE_FROM_ALL)

add_library(tbchat_llama SHARED llama_jni.cpp)

# c++_shared is listed explicitly rather than relying on ANDROID_STL to inject
# it. Adding llama.cpp via add_subdirectory() leaves the STL off the generated
# link line, so every std::string and std::runtime_error in libllama.a comes
# back as an undefined symbol at link time.
target_link_libraries(tbchat_llama PRIVATE llama ggml c++_shared android log)

target_compile_options(tbchat_llama PRIVATE -O3 -fvisibility=hidden)

# Android 15+ requires native libraries aligned to 16 KB pages, and Play rejects
# uploads that are not. Applied to every target, not just ours, because the
# static ggml/llama objects end up inside our .so.
target_link_options(tbchat_llama PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
