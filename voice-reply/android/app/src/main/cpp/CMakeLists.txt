cmake_minimum_required(VERSION 3.22.1)
project(voicereply C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp, pinned to one release. To build offline, pass
# -DFETCHCONTENT_SOURCE_DIR_LLAMA=/path/to/llama.cpp
include(FetchContent)
FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b12627
    GIT_SHALLOW    TRUE)

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)

if(ANDROID)
    # Same settings as llama.cpp's own Android example: one CPU backend per
    # ARM feature level, picked at runtime, so it runs fast on any phone.
    set(BUILD_SHARED_LIBS      ON  CACHE BOOL "" FORCE)
    set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH  "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP       ON)
    else()
        set(GGML_SYSTEM_ARCH  "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP       OFF)
    endif()
else()
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
endif()

FetchContent_MakeAvailable(llama)

if(ANDROID)
    add_library(voicereply SHARED llm_core.cpp llm_jni.cpp)
    target_link_libraries(voicereply llama ggml android log)
else()
    add_executable(llm_test llm_core.cpp desktop_test.cpp)
    target_link_libraries(llm_test llama ggml)
endif()
