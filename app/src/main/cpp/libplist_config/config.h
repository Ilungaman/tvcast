/*
 * Hand-written replacement for the config.h that libplist's autotools
 * build (./autogen.sh && ./configure) would normally generate.
 *
 * We compile libplist's sources directly under our own Android/NDK CMake
 * build instead of running autotools, so this file supplies the same
 * macros configure.ac checks for, using values that hold for every
 * Android target we build (bionic libc, minSdk 24, little-endian
 * arm64/x86_64/x86 hardware).
 */
#ifndef TVCAST_LIBPLIST_CONFIG_H
#define TVCAST_LIBPLIST_CONFIG_H

#define PACKAGE_VERSION "2.6.0"

#define __LITTLE_ENDIAN__ 1

/* Bionic (Android's libc) has all of these from API 24 onward. */
#define HAVE_STRDUP 1
#define HAVE_STRNDUP 1
#define HAVE_STRERROR 1
#define HAVE_GMTIME_R 1
#define HAVE_LOCALTIME_R 1
#define HAVE_TIMEGM 1
#define HAVE_STRPTIME 1
#define HAVE_MEMMEM 1
#define HAVE_TM_TM_GMTOFF 1
#define HAVE_TM_TM_ZONE 1

/* Supported by NDK clang. */
#define HAVE_ATTRIBUTE_CONSTRUCTOR 1

#endif /* TVCAST_LIBPLIST_CONFIG_H */
