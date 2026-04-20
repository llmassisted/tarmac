#
# Vendored libplist (https://github.com/libimobiledevice/libplist), pinned at
# commit dddb76d74a646a7ec41cfa6b9f7772830b7acbd2 — see VENDOR-COMMIT.
#
# This CMake file replaces the upstream autotools build.  It produces a static
# library named `plist` with a header search path rooted at `include/` so the
# canonical `#include <plist/plist.h>` resolves.  libcnary is compiled directly
# into the same archive (upstream builds it as a separate `libcnary.la` that
# gets merged into libplist — we just glob the sources).
#
# Only exposed to the outer build when TARMAC_LINK_LIBAIRPLAY=ON.
#
cmake_minimum_required(VERSION 3.22.1)

set(LIBPLIST_SOURCES
    src/base64.c
    src/bplist.c
    src/bytearray.c
    src/hashtable.c
    src/jplist.c
    src/jsmn.c
    src/oplist.c
    src/out-default.c
    src/out-limd.c
    src/out-plutil.c
    src/plist.c
    src/ptrarray.c
    src/time64.c
    src/xplist.c
    libcnary/cnary.c
    libcnary/node.c
    libcnary/node_list.c
)

add_library(plist STATIC ${LIBPLIST_SOURCES})

# Public header root: resolves `#include <plist/plist.h>`.
target_include_directories(plist PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)
# Private headers and hand-rolled config.h.
target_include_directories(plist PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/libcnary/include
)

target_compile_definitions(plist PRIVATE
    HAVE_CONFIG_H=1
    _GNU_SOURCE=1
)

# libplist relies on unused-parameter / sign-compare patterns; silence the
# noisiest warnings from clang so the Android NDK build stays readable.
target_compile_options(plist PRIVATE
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-implicit-function-declaration
    -Wno-deprecated-declarations
)

set_target_properties(plist PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    C_STANDARD 99
)
