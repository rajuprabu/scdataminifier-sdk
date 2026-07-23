/*
 * scdec - license-gated, decode-only image core for the mobile SDK.
 *
 * Two build flavours are produced from THIS SAME source, selected by a compile-time flag
 * (SCDEC_FMT). Neither the flavour nor the underlying image format is named in the public
 * API, the exported symbols, or the shipped strings — a caller sees only opaque "sc" entry
 * points that turn a stored IMAGE value into raw RGB pixels.
 *
 *   SCDEC_FMT == 1  -> flavour A
 *   SCDEC_FMT == 2  -> flavour B
 *
 * Input is the SDK IMAGE value exactly as carried in a TLV: a 1-byte image descriptor
 * followed by the version-1 headerless data. The container shell is rebuilt internally, so
 * the caller never handles any container/format detail.
 *
 * All returned buffers are malloc'd and must be released with scdec_free().
 */
#ifndef SCDEC_H
#define SCDEC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define SCDEC_API __declspec(dllexport)
#else
#define SCDEC_API __attribute__((visibility("default")))
#endif

/* License result codes (mirror the shared license gate). */
#define SCDEC_LIC_OK          0
#define SCDEC_LIC_MALFORMED  -1
#define SCDEC_LIC_SIGNATURE  -2
#define SCDEC_LIC_PACKAGE    -3
#define SCDEC_LIC_NOT_YET    -4
#define SCDEC_LIC_EXPIRED    -5

/*
 * Apply a signed license bound to calling_package. Must succeed before scdec_info/scdec_open
 * return anything. Returns SCDEC_LIC_OK (0) on success, negative otherwise.
 */
SCDEC_API int scdec_license(const uint8_t *lic, int lic_len, const char *calling_package);

/* 1 if a valid license has been accepted, else 0. */
SCDEC_API int scdec_licensed(void);

/*
 * Read the pixel dimensions of an IMAGE value without decoding the pixels.
 * Returns 0 on success (and fills out_w and out_h), negative on error.
 */
SCDEC_API int scdec_info(const uint8_t *value, int value_len, int *out_w, int *out_h);

/*
 * Decode an IMAGE value to 24-bit RGB (3 bytes/pixel, row-major, no padding).
 * On success returns a malloc'd buffer of *out_w * *out_h * 3 bytes; NULL on error.
 * Free with scdec_free().
 */
SCDEC_API uint8_t *scdec_open(const uint8_t *value, int value_len, int *out_w, int *out_h);

SCDEC_API void scdec_free(uint8_t *p);

/* Short description of the last error on the calling thread (never names a codec). */
SCDEC_API const char *scdec_error(void);

#ifdef __cplusplus
}
#endif

#endif /* SCDEC_H */
