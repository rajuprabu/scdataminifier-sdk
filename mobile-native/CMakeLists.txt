cmake_minimum_required(VERSION 3.16)
project(scdec C)

# Decode-only, license-gated image core in two format-neutral flavours.
#
# Options:
#   -DSCDEC_FMT=1|2       flavour A / flavour B (REQUIRED)
#   -DDEPS_PREFIX=<dir>   where the flavour's decode dependency was installed (REQUIRED)
#   -DSCDEC_JNI=ON|OFF    include Android JNI bindings (OFF for iOS)
#   -DSCDEC_STATIC=ON|OFF build as a static archive (ON for iOS)
#
# The output library exports ONLY the scdec_* / JNI entry points; every dependency symbol is
# hidden (visibility=hidden + --exclude-libs ALL) and the binary is stripped by the caller.

option(SCDEC_JNI "Build Android JNI bindings" ON)
option(SCDEC_STATIC "Build as a static library" OFF)
set(DEPS_PREFIX "" CACHE PATH "Prefix where the decode dependency was installed")
set(SCDEC_FMT "" CACHE STRING "Flavour: 1 or 2")

if(NOT SCDEC_FMT MATCHES "^[12]$")
    message(FATAL_ERROR "Pass -DSCDEC_FMT=1 or -DSCDEC_FMT=2")
endif()
if(NOT DEPS_PREFIX)
    message(FATAL_ERROR "Pass -DDEPS_PREFIX=<dir>")
endif()

set(NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../native)

set(SCDEC_SOURCES
    src/scdec.c
    ${NATIVE_DIR}/src/license.c
    ${NATIVE_DIR}/src/sha256.c
    ${NATIVE_DIR}/third_party/micro-ecc/uECC.c)
if(SCDEC_JNI)
    list(APPEND SCDEC_SOURCES src/scdec_jni.c)
endif()

add_compile_definitions(uECC_PLATFORM=0 SCDEC_FMT=${SCDEC_FMT})
if(SCDEC_JNI)
    add_compile_definitions(SCDEC_JNI=1)
endif()

if(SCDEC_STATIC)
    add_library(scdec STATIC ${SCDEC_SOURCES})
else()
    add_library(scdec SHARED ${SCDEC_SOURCES})
endif()

set_property(TARGET scdec PROPERTY C_STANDARD 99)
set_property(TARGET scdec PROPERTY POSITION_INDEPENDENT_CODE ON)
set_property(TARGET scdec PROPERTY C_VISIBILITY_PRESET hidden)
set_property(TARGET scdec PROPERTY VISIBILITY_INLINES_HIDDEN ON)

target_include_directories(scdec PRIVATE
    ${DEPS_PREFIX}/include
    ${NATIVE_DIR}/src
    ${NATIVE_DIR}/third_party/micro-ecc)

if(SCDEC_JNI AND NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(scdec PRIVATE ${JNI_INCLUDE_DIRS})
endif()

# Per-flavour decode dependency ONLY: flavour A links the decoder-only image lib; flavour B
# links the still-image + AV1-decoder libs. Neither pulls the other in, and neither pulls an
# encoder in — that is what keeps the encoder banners/strings out of the artifact.
if(SCDEC_FMT STREQUAL "1")
    find_library(DEC_A webpdecoder PATHS ${DEPS_PREFIX}/lib ${DEPS_PREFIX}/lib64 NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
    if(NOT DEC_A)
        message(FATAL_ERROR "decoder lib (A) not found under ${DEPS_PREFIX}/lib")
    endif()
    target_link_libraries(scdec PRIVATE ${DEC_A})
else()
    find_library(DEC_B1 avif PATHS ${DEPS_PREFIX}/lib ${DEPS_PREFIX}/lib64 NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
    find_library(DEC_B2 aom  PATHS ${DEPS_PREFIX}/lib ${DEPS_PREFIX}/lib64 NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
    if(NOT DEC_B1 OR NOT DEC_B2)
        message(FATAL_ERROR "decode libs (B) not found under ${DEPS_PREFIX}/lib")
    endif()
    target_link_libraries(scdec PRIVATE ${DEC_B1} ${DEC_B2})
endif()

if(NOT WIN32)
    target_link_libraries(scdec PRIVATE m)
    find_package(Threads REQUIRED)
    target_link_libraries(scdec PRIVATE Threads::Threads)
endif()

# Export nothing but our own entry points; keep the library self-contained.
# GNU ld (Linux / Android): hide every symbol coming from the linked static archives.
if(NOT APPLE AND NOT WIN32)
    target_link_options(scdec PRIVATE -static-libgcc -Wl,--exclude-libs,ALL -Wl,--gc-sections)
    target_compile_options(scdec PRIVATE -ffunction-sections -fdata-sections)
endif()
# Apple ld64 has no --exclude-libs: -fvisibility=hidden only hides OUR symbols, not those from
# the statically-linked decoder (libwebp/libavif export WebPDecode/… by default). Restrict the
# dylib's export table to exactly the scdec_* entry points via an explicit symbols list, so no
# codec symbol is exported (and the stripped, unexported names then drop out of the binary too).
if(APPLE)
    target_link_options(scdec PRIVATE
        -Wl,-exported_symbols_list,${CMAKE_CURRENT_SOURCE_DIR}/ios-exported-symbols.txt
        -Wl,-dead_strip)
endif()

install(TARGETS scdec LIBRARY DESTINATION lib ARCHIVE DESTINATION lib RUNTIME DESTINATION lib)
install(FILES src/scdec.h DESTINATION include)
