# Builds libwhisper_android.so: whisper.cpp (vendored as a git submodule, see NATIVE_SETUP.md)
# plus our thin JNI bridge in jni_bridge.cpp.
cmake_minimum_required(VERSION 3.22.1)
project(whisper_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Expects `whisper.cpp` vendored at app/src/main/cpp/whisper.cpp
# (git submodule add https://github.com/ggerganov/whisper.cpp app/src/main/cpp/whisper.cpp)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_DIR}. Run:\n"
        "  git submodule add https://github.com/ggerganov/whisper.cpp app/src/main/cpp/whisper.cpp\n"
        "See NATIVE_SETUP.md.")
endif()

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)   # cross-compiling for Android, not the build host
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_DIR} whisper_build)

add_library(whisper_android SHARED jni_bridge.cpp)

target_include_directories(whisper_android PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
target_link_libraries(whisper_android PRIVATE whisper log android)
