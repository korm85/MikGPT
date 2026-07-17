cmake_minimum_required(VERSION 3.22)
project(mikgpt_native VERSION 1.0.0 LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Fetch llama.cpp source automatically at build time
FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b4749 # Stable tag that supports Q1_0_g128
)

# Configure llama.cpp flags
set(GGML_VULKAN ON CACHE BOOL "Use Vulkan" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build common" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server executable" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)

FetchContent_MakeAvailable(llamacpp)

# Create our JNI wrapper library
add_library(mikgpt SHARED
    mikgpt-jni.cpp
)

# Link JNI library with llama.cpp core
target_link_libraries(mikgpt PRIVATE llama common ${CMAKE_DL_LIBS})
find_library(log-lib log)
target_link_libraries(mikgpt PRIVATE ${log-lib})
