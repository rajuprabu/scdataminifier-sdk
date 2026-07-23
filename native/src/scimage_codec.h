/*
 * scimage - pinned WEBP/AVIF encode+decode core for SCDataMinifier.
 *
 * Pure C API (no JNI) so the same code serves:
 *   - desktop/server JVM and Android via the JNI layer (scimage_jni.c)
 *   - iOS via direct Swift/ObjC calls (built without SCIMG_JNI)
 *
 * All returned buffers are malloc'd and must be released with scimg_free().
 * On failure, functions return NULL and scimg_last_error() describes why.
 */
#ifndef SCIMAGE_CODEC_H
#define SCIMAGE_CODEC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Versions of the statically linked libraries, e.g. "1.6.0". */
const char* scimg_webp_version(void);
const char* scimg_avif_version(void);
/* Codec details, e.g. "aom [enc/dec]:3.14.1". */
const char* scimg_codec_versions(void);
/* 1 if the linked codecs match the versions this build is pinned to, else 0. */
int scimg_versions_ok(void);

const char* scimg_last_error(void);

/*
 * Encode 24-bit RGB pixels (row-major, 3 bytes per pixel, no padding).
 * WebP: simple lossy VP8, picture preset, method 6, no alpha.
 * AVIF: AV1 Main profile 8-bit 4:2:0, sRGB nclx (CICP 1/13/6 full range).
 * quality: 1-100. speed: avif encoder speed 0-10 (use 6).
 */
uint8_t* scimg_encode_webp(const uint8_t* rgb, int width, int height,
                           int quality, size_t* out_size);

/*
 * Rate-controlled WebP encode: instead of a fixed quality, libwebp's own
 * rate-control converges on target_bytes over several passes (method 6,
 * autofilter, segments 4, partitions 3, preprocessing on). Produces visibly
 * smoother output at small byte budgets than a fixed-quality encode of the
 * same size, because the bit allocation is optimized per-segment for the
 * budget instead of uniformly. target_bytes is a goal, not a hard cap - the
 * result may land slightly above it; the caller re-tries with a smaller
 * target if the overshoot matters.
 */
uint8_t* scimg_encode_webp_target(const uint8_t* rgb, int width, int height,
                                  int target_bytes, size_t* out_size);

uint8_t* scimg_encode_avif(const uint8_t* rgb, int width, int height,
                           int quality, int speed, size_t* out_size);

/* Decode a complete .webp/.avif file to 24-bit RGB. */
uint8_t* scimg_decode_webp(const uint8_t* data, size_t size,
                           int* out_width, int* out_height);
uint8_t* scimg_decode_avif(const uint8_t* data, size_t size,
                           int* out_width, int* out_height);

void scimg_free(uint8_t* p);

#ifdef __cplusplus
}
#endif

#endif /* SCIMAGE_CODEC_H */
