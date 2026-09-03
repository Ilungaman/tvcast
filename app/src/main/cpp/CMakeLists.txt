cmake_minimum_required(VERSION 3.22.1)
project(tvcast_airplay)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 14)
set(UXPLAY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/uxplay)
set(LIBPLIST_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libplist)
set(OPENSSL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/android_openssl)

# ---------------------------------------------------------------------------
# Prebuilt OpenSSL (github.com/KDAB/android_openssl), static libcrypto only.
# We only need libcrypto (EVP/BN/SHA/RAND) for UxPlay's SRP6a + Curve25519 +
# AES-GCM pairing/encryption code -- no TLS, so libssl is not linked.
# ---------------------------------------------------------------------------
add_library(openssl_crypto STATIC IMPORTED)
set_target_properties(openssl_crypto PROPERTIES
    IMPORTED_LOCATION "${OPENSSL_DIR}/ssl_3/${ANDROID_ABI}/libcrypto.a"
    INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_DIR}/ssl_3/include"
)

# ---------------------------------------------------------------------------
# libplist -- compiled directly from source (no CMakeLists.txt upstream, and
# we skip its autotools build entirely). Source list taken verbatim from
# third_party/libplist/src/Makefile.am and libcnary/Makefile.am so it stays
# accurate if the submodule is bumped.
# ---------------------------------------------------------------------------
add_library(plist STATIC
    ${LIBPLIST_DIR}/libcnary/node.c
    ${LIBPLIST_DIR}/libcnary/node_list.c
    ${LIBPLIST_DIR}/src/base64.c
    ${LIBPLIST_DIR}/src/bytearray.c
    ${LIBPLIST_DIR}/src/hashtable.c
    ${LIBPLIST_DIR}/src/ptrarray.c
    ${LIBPLIST_DIR}/src/time64.c
    ${LIBPLIST_DIR}/src/xplist.c
    ${LIBPLIST_DIR}/src/bplist.c
    ${LIBPLIST_DIR}/src/jsmn.c
    ${LIBPLIST_DIR}/src/jplist.c
    ${LIBPLIST_DIR}/src/oplist.c
    ${LIBPLIST_DIR}/src/out-default.c
    ${LIBPLIST_DIR}/src/out-plutil.c
    ${LIBPLIST_DIR}/src/out-limd.c
    ${LIBPLIST_DIR}/src/plist.c
    ${LIBPLIST_DIR}/src/Node.cpp
    ${LIBPLIST_DIR}/src/Structure.cpp
    ${LIBPLIST_DIR}/src/Array.cpp
    ${LIBPLIST_DIR}/src/Boolean.cpp
    ${LIBPLIST_DIR}/src/Data.cpp
    ${LIBPLIST_DIR}/src/Date.cpp
    ${LIBPLIST_DIR}/src/Dictionary.cpp
    ${LIBPLIST_DIR}/src/Integer.cpp
    ${LIBPLIST_DIR}/src/Key.cpp
    ${LIBPLIST_DIR}/src/Real.cpp
    ${LIBPLIST_DIR}/src/String.cpp
    ${LIBPLIST_DIR}/src/Uid.cpp
)
target_include_directories(plist PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libplist_config
    ${LIBPLIST_DIR}
    ${LIBPLIST_DIR}/src
    ${LIBPLIST_DIR}/libcnary/include
)
target_include_directories(plist PUBLIC
    ${LIBPLIST_DIR}/include
)
target_compile_definitions(plist PRIVATE HAVE_CONFIG_H)

# ---------------------------------------------------------------------------
# UxPlay's self-contained helper libraries: reuse their own CMakeLists.txt
# as-is, they have no external dependencies beyond libc.
# ---------------------------------------------------------------------------
add_subdirectory(${UXPLAY_DIR}/lib/llhttp uxplay_llhttp)
add_subdirectory(${UXPLAY_DIR}/lib/playfair uxplay_playfair)
add_subdirectory(${UXPLAY_DIR}/lib/mdnsd uxplay_mdnsd)

# ---------------------------------------------------------------------------
# UxPlay's protocol core ("airplay" target: raop.c, pairing.c, srp.c,
# crypto.c, the RTSP/HTTP control server, RTP audio+mirror handling, NTP
# timing). We do NOT reuse third_party/uxplay/lib/CMakeLists.txt -- it
# assumes a desktop pkg-config environment (find_package(OpenSSL),
# pkg_search_module(PLIST)) that doesn't exist when cross-compiling for
# Android, so we list its sources ourselves and link our own OpenSSL/plist
# targets above instead.
# ---------------------------------------------------------------------------
file(GLOB AIRPLAY_CORE_SOURCES ${UXPLAY_DIR}/lib/*.c)
add_library(airplay STATIC ${AIRPLAY_CORE_SOURCES})
target_include_directories(airplay PUBLIC
    ${UXPLAY_DIR}/lib
    ${UXPLAY_DIR}/lib/llhttp
    ${UXPLAY_DIR}/lib/playfair
    ${UXPLAY_DIR}/lib/mdnsd
)
target_compile_definitions(airplay PRIVATE
    NOHOLD
    STANDALONE
    __STDC_CONSTANT_MACROS
    __STDC_LIMIT_MACROS
    TARGET_POSIX
    _LINUX
    PIC
    _REENTRANT
    _LARGEFILE64_SOURCE
    _FILE_OFFSET_BITS=64
    PLIST_210
    PLIST_230
    OPENSSL_API_COMPAT=0x10101000L
)
target_link_libraries(airplay PUBLIC
    llhttp
    playfair
    dnssd
    plist
    openssl_crypto
    log
)

# ---------------------------------------------------------------------------
# Our own JNI bridge: implements raop_callbacks_t and exposes the native
# entry points Kotlin calls. This is the only code in this tree that isn't
# vendored -- everything above is a faithful, minimally-adapted rebuild of
# UxPlay's own lib/CMakeLists.txt for the Android toolchain.
# ---------------------------------------------------------------------------
add_library(tvcast_airplay SHARED
    jni/airplay_jni.cpp
)
target_link_libraries(tvcast_airplay PRIVATE
    airplay
    log
)
