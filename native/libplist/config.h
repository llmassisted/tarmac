/*
 * Hand-written libplist config.h for the Android NDK build (targeting API 26+,
 * arm64-v8a + x86_64).  Replaces the autoconf-generated config.h that upstream
 * builds with autotools.  Only the symbols that actually guard code in
 * src/*.c and libcnary/*.c are defined here.
 *
 * Check with:
 *   grep -rhn "HAVE_\|PACKAGE_\|HAVE_CONFIG_H" native/libplist/src/*.c \
 *       native/libplist/libcnary/*.c
 */
#ifndef TARMAC_LIBPLIST_CONFIG_H
#define TARMAC_LIBPLIST_CONFIG_H

/* --- Standard library features available in modern bionic (API 21+). ----- */
#define HAVE_STRDUP 1
#define HAVE_STRNDUP 1
#define HAVE_STRERROR 1
#define HAVE_GMTIME_R 1
#define HAVE_LOCALTIME_R 1
#define HAVE_TIMEGM 1
#define HAVE_MEMMEM 1

/* strptime lives in <time.h> on Android API 21+ (bionic). */
#define HAVE_STRPTIME 1

/* struct tm provides tm_gmtoff / tm_zone in modern bionic. */
#define HAVE_TM_TM_GMTOFF 1
#define HAVE_TM_TM_ZONE 1

/* --- Headers (all present in bionic). ------------------------------------ */
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1

/* --- Endianness --------------------------------------------------------- */
/* Android arm64-v8a and x86_64 are both little-endian. The plist code also
 * checks __BYTE_ORDER__ so we only need the marker when the header isn't
 * available — but the autoconf build historically sets one of these, and
 * leaving __LITTLE_ENDIAN__ undefined has been observed to compile. Define
 * __LITTLE_ENDIAN__ so out-limd.c's copy fast-path stays on. */
#ifndef __BIG_ENDIAN__
#ifndef __LITTLE_ENDIAN__
#define __LITTLE_ENDIAN__ 1
#endif
#endif

/* --- Package identity (plist_get_libplist_version references this). ------ */
#ifndef PACKAGE_NAME
#define PACKAGE_NAME "libplist"
#endif
#ifndef PACKAGE_VERSION
/* Matches upstream libplist master circa 2024-2025 (commit dddb76d). */
#define PACKAGE_VERSION "2.6.99"
#endif
#ifndef PACKAGE_STRING
#define PACKAGE_STRING PACKAGE_NAME " " PACKAGE_VERSION
#endif

#endif /* TARMAC_LIBPLIST_CONFIG_H */
