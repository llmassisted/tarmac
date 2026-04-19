cmake_minimum_required(VERSION 3.22.1)
project(tarmac_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Phase 1: stub-only native library (JNI entry points + renderer placeholders).
# Phase 2 will turn on -DTARMAC_LINK_LIBAIRPLAY=ON and compile the UxPlay lib/,
# which requires vendored OpenSSL for Android and excludes dnssd.c (NsdManager
# handles Bonjour on the Java side).
option(TARMAC_LINK_LIBAIRPLAY "Compile UxPlay protocol sources into the JNI lib" OFF)

add_library(tarmac-native SHARED
    jni_bridge.cpp
    android_video_renderer.cpp
    android_audio_renderer.cpp
)

if(TARMAC_LINK_LIBAIRPLAY)
    set(LIBAIRPLAY_DIR ${CMAKE_SOURCE_DIR}/../../../../native/libairplay/lib)
    # dnssd.c is intentionally excluded — NsdManager is used on the Java side.
    file(GLOB LIBAIRPLAY_SOURCES
        ${LIBAIRPLAY_DIR}/*.c
        ${LIBAIRPLAY_DIR}/llhttp/*.c
        ${LIBAIRPLAY_DIR}/playfair/*.c
    )
    list(FILTER LIBAIRPLAY_SOURCES EXCLUDE REGEX "/dnssd\\.c$")
    target_sources(tarmac-native PRIVATE ${LIBAIRPLAY_SOURCES})
    target_include_directories(tarmac-native PRIVATE
        ${LIBAIRPLAY_DIR}
        ${LIBAIRPLAY_DIR}/llhttp
        ${LIBAIRPLAY_DIR}/playfair
    )
    target_compile_definitions(tarmac-native PRIVATE HAVE_LIBAIRPLAY=1)
    # OpenSSL for Android must be wired here in Phase 2 (prefab or vendored prebuilts).
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
