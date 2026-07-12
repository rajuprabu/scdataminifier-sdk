cmake_minimum_required(VERSION 3.16)
project(scimage C)

# libscimage - JNI/C wrapper around statically linked libwebp + libavif(+aom).
#
# Expects the dependencies to be pre-installed into DEPS_PREFIX by
# scripts/build-deps.sh (static .a libs + headers).
#
# Options:
#   -DDEPS_PREFIX=<dir>   where libwebp/libavif/aom were installed (required)
#   -DSCIMG_JNI=ON|OFF    include JNI bindings (OFF for iOS)
#   -DSCIMG_STATIC=ON|OFF build libscimage as static archive (ON for iOS)

option(SCIMG_JNI "Build JNI bindings" ON)
option(SCIMG_STATIC "Build scimage as a static library" OFF)
set(DEPS_PREFIX "" CACHE PATH "Prefix where libwebp/libavif/aom are installed")

if(NOT DEPS_PREFIX)
    message(FATAL_ERROR "Pass -DDEPS_PREFIX=<dir> (run scripts/build-deps.sh first)")
endif()

set(SCIMG_SOURCES src/scimage_codec.c
                  src/license.c
                  src/sha256.c
                  third_party/micro-ecc/uECC.c)
if(SCIMG_JNI)
    list(APPEND SCIMG_SOURCES src/scimage_jni.c)
endif()

# Force micro-ecc's portable C path (no per-arch asm) for reproducible builds across all
# Android ABIs + iOS. License verification runs once at init, so speed is irrelevant.
add_compile_definitions(uECC_PLATFORM=0)

if(SCIMG_STATIC)
    add_library(scimage STATIC ${SCIMG_SOURCES})
else()
    add_library(scimage SHARED ${SCIMG_SOURCES})
endif()

set_property(TARGET scimage PROPERTY C_STANDARD 99)
set_property(TARGET scimage PROPERTY POSITION_INDEPENDENT_CODE ON)

target_include_directories(scimage PRIVATE ${DEPS_PREFIX}/include)

if(SCIMG_JNI AND NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(scimage PRIVATE ${JNI_INCLUDE_DIRS})
endif()
# Android: jni.h is part of the NDK sysroot; nothing to find.

# Static dependency archives (order matters: avif -> aom, webp -> sharpyuv).
# NO_CMAKE_FIND_ROOT_PATH: our deps live in DEPS_PREFIX, outside the Android/iOS sysroot;
# without it the cross toolchain re-roots the PATHS into the sysroot and can't find them.
find_library(AVIF_LIB avif PATHS ${DEPS_PREFIX}/lib ${DEPS_PREFIX}/lib64 NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
find_library(AOM_LIB aom PATHS ${DEPS_PREFIX}/lib ${DEPS_PREFIX}/lib64 NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
find_library(WEBP_LIB webp PATHS ${DEPS_PREFIX}/lib ${DEPS_PREFIX}/lib64 NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
find_library(SHARPYUV_LIB sharpyuv PATHS ${DEPS_PREFIX}/lib ${DEPS_PREFIX}/lib64 NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
foreach(lib AVIF_LIB AOM_LIB WEBP_LIB SHARPYUV_LIB)
    if(NOT ${lib})
        message(FATAL_ERROR "${lib} not found under ${DEPS_PREFIX}/lib - run scripts/build-deps.sh")
    endif()
endforeach()

target_link_libraries(scimage PRIVATE ${AVIF_LIB} ${AOM_LIB} ${WEBP_LIB} ${SHARPYUV_LIB})

if(NOT WIN32)
    target_link_libraries(scimage PRIVATE m)
    find_package(Threads REQUIRED)
    target_link_libraries(scimage PRIVATE Threads::Threads)
endif()

# Keep the shared library self-contained.
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_options(scimage PRIVATE -static-libgcc -Wl,--exclude-libs,ALL)
elseif(WIN32 AND CMAKE_C_COMPILER_ID MATCHES "GNU")
    target_link_options(scimage PRIVATE -static-libgcc -static)
endif()

install(TARGETS scimage
        LIBRARY DESTINATION lib
        ARCHIVE DESTINATION lib
        RUNTIME DESTINATION lib)
install(FILES src/scimage_codec.h DESTINATION include)
