cmake_minimum_required(VERSION 3.22.1)
project(tarmac_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Phase 2: OpenSSL is now provided by the prefab AAR
# `com.android.ndk.thirdparty:openssl` (declared in app/build.gradle.kts).
# The Android Gradle Plugin exposes its prefab packages on CMAKE_FIND_ROOT_PATH
# automatically when buildFeatures.prefab = true is set.
#
# TARMAC_LINK_LIBAIRPLAY pulls UxPlay's RAOP/RTSP server sources into the JNI
# library. It currently still requires libplist (libimobiledevice/libplist),
# which has not been vendored yet. Until then this stays OFF and the JNI
# bridge compiles against stubs only.
option(TARMAC_LINK_LIBAIRPLAY "Compile UxPlay protocol sources into the JNI lib" OFF)

add_library(tarmac-native SHARED
    jni_bridge.cpp
    android_video_renderer.cpp
    android_audio_renderer.cpp
)

# OpenSSL prefab — always wired so crypto is ready as soon as libairplay is on.
find_package(openssl REQUIRED CONFIG)
target_link_libraries(tarmac-native openssl::crypto openssl::ssl)
target_compile_definitions(tarmac-native PRIVATE OPENSSL_API_COMPAT=0x10101000L)

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
    target_compile_definitions(tarmac-native PRIVATE
        HAVE_LIBAIRPLAY=1
        NOHOLD=1
    )
    # libplist must be wired here when vendored — see CMake TODO above.
    # find_package(plist REQUIRED CONFIG) or add_subdirectory of vendored copy.
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
