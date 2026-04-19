cmake_minimum_required(VERSION 3.22.1)
project(tarmac_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# OpenSSL comes from the prefab AAR (com.android.ndk.thirdparty:openssl)
# declared in app/build.gradle.kts. When buildFeatures.prefab = true is set,
# Android Gradle Plugin wires it onto CMAKE_FIND_ROOT_PATH automatically, so
# `find_package(openssl REQUIRED CONFIG)` below just works.
#
# libplist is vendored under native/libplist (see its CMakeLists.txt) and is
# always compiled when TARMAC_LINK_LIBAIRPLAY is ON. The real Bonjour /
# mDNS responder lives on the Java side (NsdManager — see
# com.tarmac.service.BonjourAdvertiser); we replace lib/dnssd.c with the
# lightweight stub in app/src/main/cpp/dnssd_stub.c so raop_handlers.h still
# has a non-NULL raop->dnssd to read TXT bytes from.
option(TARMAC_LINK_LIBAIRPLAY "Compile UxPlay protocol sources into the JNI lib" ON)

add_library(tarmac-native SHARED
    jni_bridge.cpp
    android_video_renderer.cpp
    android_audio_renderer.cpp
)

# OpenSSL 3.x prefab (io.github.ronickg:openssl, ndkports layout). The CMake
# package name (`openssl`) and module names (`crypto`, `ssl`) match Google's
# AAR, so the link commands stay unchanged if/when we switch back.
#
# OPENSSL_API_COMPAT=0x10101000L keeps UxPlay's crypto.c / srp.c seeing
# 1.1.1-era typedefs (EVP_* and SHA/MD5 helpers are still available in the
# default OpenSSL 3 provider, so runtime is unaffected).
find_package(openssl REQUIRED CONFIG)
target_link_libraries(tarmac-native openssl::crypto openssl::ssl)
target_compile_definitions(tarmac-native PRIVATE OPENSSL_API_COMPAT=0x10101000L)

if(TARMAC_LINK_LIBAIRPLAY)
    set(LIBAIRPLAY_DIR ${CMAKE_SOURCE_DIR}/../../../../native/libairplay/lib)
    set(LIBPLIST_DIR   ${CMAKE_SOURCE_DIR}/../../../../native/libplist)

    # Pull in vendored libplist as a static library target named `plist`.
    add_subdirectory(${LIBPLIST_DIR} ${CMAKE_BINARY_DIR}/libplist)

    # Upstream lib/dnssd.c registers services via Apple Bonjour's
    # DNSServiceRegister — we replace it with dnssd_stub.c. Filter it out of
    # the glob and add the stub instead.
    file(GLOB LIBAIRPLAY_SOURCES
        ${LIBAIRPLAY_DIR}/*.c
        ${LIBAIRPLAY_DIR}/llhttp/*.c
        ${LIBAIRPLAY_DIR}/playfair/*.c
    )
    list(FILTER LIBAIRPLAY_SOURCES EXCLUDE REGEX "/dnssd\\.c$")
    target_sources(tarmac-native PRIVATE
        ${LIBAIRPLAY_SOURCES}
        ${CMAKE_SOURCE_DIR}/dnssd_stub.c
    )
    target_include_directories(tarmac-native PRIVATE
        ${LIBAIRPLAY_DIR}
        ${LIBAIRPLAY_DIR}/llhttp
        ${LIBAIRPLAY_DIR}/playfair
    )
    target_compile_definitions(tarmac-native PRIVATE
        HAVE_LIBAIRPLAY=1
        NOHOLD=1
        PLIST_230=1
    )
    target_link_libraries(tarmac-native plist)

    # Upstream lib/ was written when warnings-as-errors was rare; silence the
    # noisiest patterns so the NDK build log stays readable.
    target_compile_options(tarmac-native PRIVATE
        -Wno-unused-parameter
        -Wno-unused-variable
        -Wno-unused-function
        -Wno-sign-compare
        -Wno-pointer-sign
        -Wno-implicit-function-declaration
        -Wno-incompatible-pointer-types
        -Wno-deprecated-declarations
        -Wno-int-conversion
    )
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
