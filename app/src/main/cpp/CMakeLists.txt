cmake_minimum_required(VERSION 3.22.1)
project(tarmac_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Path to the UxPlay `lib/` sources pulled in as a git subtree at repo root.
set(LIBAIRPLAY_DIR ${CMAKE_SOURCE_DIR}/../../../../native/libairplay)

# Collect UxPlay protocol sources. The subtree hasn't been added yet in Phase 1,
# so guard with EXISTS so the Gradle build still succeeds before Phase 2.
if(EXISTS ${LIBAIRPLAY_DIR}/lib)
    file(GLOB LIBAIRPLAY_SOURCES
        ${LIBAIRPLAY_DIR}/lib/*.c
        ${LIBAIRPLAY_DIR}/lib/llhttp/*.c
        ${LIBAIRPLAY_DIR}/lib/playfair/*.c
    )
    set(HAS_LIBAIRPLAY TRUE)
else()
    set(LIBAIRPLAY_SOURCES "")
    set(HAS_LIBAIRPLAY FALSE)
    message(WARNING "native/libairplay not present yet — building stub only. "
                    "Run `git subtree add --prefix=native/libairplay "
                    "https://github.com/FDH2/UxPlay.git v1.73 --squash` from repo root.")
endif()

add_library(tarmac-native SHARED
    jni_bridge.cpp
    android_video_renderer.cpp
    android_audio_renderer.cpp
    ${LIBAIRPLAY_SOURCES}
)

if(HAS_LIBAIRPLAY)
    target_include_directories(tarmac-native PRIVATE
        ${LIBAIRPLAY_DIR}/lib
        ${LIBAIRPLAY_DIR}/lib/llhttp
        ${LIBAIRPLAY_DIR}/lib/playfair
    )
    target_compile_definitions(tarmac-native PRIVATE HAVE_LIBAIRPLAY=1)
endif()

find_library(log-lib log)
find_library(android-lib android)
find_library(mediandk-lib mediandk)
find_library(opensles-lib OpenSLES)

target_link_libraries(tarmac-native
    ${log-lib}
    ${android-lib}
    ${mediandk-lib}
    ${opensles-lib}
)
