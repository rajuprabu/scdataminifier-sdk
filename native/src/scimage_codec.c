#include "scimage_codec.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <webp/encode.h>
#include <webp/decode.h>
#include <avif/avif.h>

#if defined(_MSC_VER)
#define SCIMG_THREAD_LOCAL __declspec(thread)
#else
#define SCIMG_THREAD_LOCAL __thread
#endif

static SCIMG_THREAD_LOCAL char g_error[256];
static SCIMG_THREAD_LOCAL char g_webp_version[32];
static SCIMG_THREAD_LOCAL char g_codec_versions[256];

static void set_error(const char* msg) {
    snprintf(g_error, sizeof(g_error), "%s", msg ? msg : "unknown error");
}

const char* scimg_last_error(void) { return g_error; }

const char* scimg_webp_version(void) {
    int v = WebPGetEncoderVersion();
    snprintf(g_webp_version, sizeof(g_webp_version), "%d.%d.%d",
             (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
    return g_webp_version;
}

const char* scimg_avif_version(void) { return avifVersion(); }

const char* scimg_codec_versions(void) {
    avifCodecVersions(g_codec_versions);
    return g_codec_versions;
}

void scimg_free(uint8_t* p) { free(p); }

/* ==================== WebP ==================== */

uint8_t* scimg_encode_webp(const uint8_t* rgb, int width, int height,
                           int quality, size_t* out_size) {
    WebPConfig config;
    WebPPicture pic;
    WebPMemoryWriter writer;
    uint8_t* out = NULL;

    if (!rgb || width < 1 || height < 1 || quality < 1 || quality > 100 || !out_size) {
        set_error("invalid encode arguments");
        return NULL;
    }
    /* Same tuning as the pinned cwebp command line:
       -preset picture -m 6 -noalpha (RGB import = no alpha channel). */
    if (!WebPConfigPreset(&config, WEBP_PRESET_PICTURE, (float) quality)) {
        set_error("WebPConfigPreset failed");
        return NULL;
    }
    config.method = 6;
    if (!WebPPictureInit(&pic)) {
        set_error("WebPPictureInit failed");
        return NULL;
    }
    pic.width = width;
    pic.height = height;
    pic.use_argb = 1;
    WebPMemoryWriterInit(&writer);
    pic.writer = WebPMemoryWrite;
    pic.custom_ptr = &writer;

    if (!WebPPictureImportRGB(&pic, rgb, width * 3)) {
        set_error("WebPPictureImportRGB failed");
        WebPPictureFree(&pic);
        return NULL;
    }
    if (!WebPEncode(&config, &pic)) {
        snprintf(g_error, sizeof(g_error), "WebPEncode failed (error %d)", pic.error_code);
        WebPPictureFree(&pic);
        WebPMemoryWriterClear(&writer);
        return NULL;
    }
    WebPPictureFree(&pic);

    out = (uint8_t*) malloc(writer.size);
    if (!out) {
        set_error("out of memory");
        WebPMemoryWriterClear(&writer);
        return NULL;
    }
    memcpy(out, writer.mem, writer.size);
    *out_size = writer.size;
    WebPMemoryWriterClear(&writer);
    return out;
}

uint8_t* scimg_decode_webp(const uint8_t* data, size_t size,
                           int* out_width, int* out_height) {
    uint8_t* rgb;
    uint8_t* out;
    if (!data || size == 0 || !out_width || !out_height) {
        set_error("invalid decode arguments");
        return NULL;
    }
    rgb = WebPDecodeRGB(data, size, out_width, out_height);
    if (!rgb) {
        set_error("WebPDecodeRGB failed (corrupt or unsupported WebP)");
        return NULL;
    }
    /* copy into plain malloc'd memory so scimg_free() is uniform */
    out = (uint8_t*) malloc((size_t) (*out_width) * (*out_height) * 3);
    if (!out) {
        set_error("out of memory");
        WebPFree(rgb);
        return NULL;
    }
    memcpy(out, rgb, (size_t) (*out_width) * (*out_height) * 3);
    WebPFree(rgb);
    return out;
}

/* ==================== AVIF ==================== */

uint8_t* scimg_encode_avif(const uint8_t* rgb, int width, int height,
                           int quality, int speed, size_t* out_size) {
    avifImage* image = NULL;
    avifEncoder* encoder = NULL;
    avifRWData output = AVIF_DATA_EMPTY;
    avifRGBImage rgbImage;
    avifResult result;
    uint8_t* out = NULL;

    if (!rgb || width < 1 || height < 1 || quality < 1 || quality > 100 || !out_size) {
        set_error("invalid encode arguments");
        return NULL;
    }

    /* AV1 Main profile: 8-bit 4:2:0; sRGB nclx matching the pinned
       avifenc command line (CICP 1/13/6, full range). */
    image = avifImageCreate((uint32_t) width, (uint32_t) height, 8, AVIF_PIXEL_FORMAT_YUV420);
    if (!image) {
        set_error("avifImageCreate failed");
        return NULL;
    }
    image->colorPrimaries = AVIF_COLOR_PRIMARIES_BT709;                /* 1  */
    image->transferCharacteristics = AVIF_TRANSFER_CHARACTERISTICS_SRGB; /* 13 */
    image->matrixCoefficients = AVIF_MATRIX_COEFFICIENTS_BT601;        /* 6  */
    image->yuvRange = AVIF_RANGE_FULL;

    avifRGBImageSetDefaults(&rgbImage, image);
    rgbImage.format = AVIF_RGB_FORMAT_RGB;
    rgbImage.pixels = (uint8_t*) rgb;
    rgbImage.rowBytes = (uint32_t) width * 3;

    result = avifImageRGBToYUV(image, &rgbImage);
    if (result != AVIF_RESULT_OK) {
        snprintf(g_error, sizeof(g_error), "avifImageRGBToYUV: %s", avifResultToString(result));
        goto cleanup;
    }

    encoder = avifEncoderCreate();
    if (!encoder) {
        set_error("avifEncoderCreate failed");
        goto cleanup;
    }
    encoder->quality = quality;
    encoder->speed = speed;
    encoder->maxThreads = 1; /* deterministic output */

    result = avifEncoderWrite(encoder, image, &output);
    if (result != AVIF_RESULT_OK) {
        snprintf(g_error, sizeof(g_error), "avifEncoderWrite: %s", avifResultToString(result));
        goto cleanup;
    }

    out = (uint8_t*) malloc(output.size);
    if (!out) {
        set_error("out of memory");
        goto cleanup;
    }
    memcpy(out, output.data, output.size);
    *out_size = output.size;

cleanup:
    avifRWDataFree(&output);
    if (encoder) avifEncoderDestroy(encoder);
    if (image) avifImageDestroy(image);
    return out;
}

uint8_t* scimg_decode_avif(const uint8_t* data, size_t size,
                           int* out_width, int* out_height) {
    avifDecoder* decoder = NULL;
    avifRGBImage rgbImage;
    avifResult result;
    uint8_t* out = NULL;

    if (!data || size == 0 || !out_width || !out_height) {
        set_error("invalid decode arguments");
        return NULL;
    }
    decoder = avifDecoderCreate();
    if (!decoder) {
        set_error("avifDecoderCreate failed");
        return NULL;
    }
    result = avifDecoderSetIOMemory(decoder, data, size);
    if (result != AVIF_RESULT_OK) {
        snprintf(g_error, sizeof(g_error), "avifDecoderSetIOMemory: %s", avifResultToString(result));
        goto cleanup;
    }
    result = avifDecoderParse(decoder);
    if (result != AVIF_RESULT_OK) {
        snprintf(g_error, sizeof(g_error), "avifDecoderParse: %s", avifResultToString(result));
        goto cleanup;
    }
    result = avifDecoderNextImage(decoder);
    if (result != AVIF_RESULT_OK) {
        snprintf(g_error, sizeof(g_error), "avifDecoderNextImage: %s", avifResultToString(result));
        goto cleanup;
    }

    avifRGBImageSetDefaults(&rgbImage, decoder->image);
    rgbImage.format = AVIF_RGB_FORMAT_RGB;
    rgbImage.rowBytes = decoder->image->width * 3;
    rgbImage.pixels = (uint8_t*) malloc((size_t) rgbImage.rowBytes * decoder->image->height);
    if (!rgbImage.pixels) {
        set_error("out of memory");
        goto cleanup;
    }
    result = avifImageYUVToRGB(decoder->image, &rgbImage);
    if (result != AVIF_RESULT_OK) {
        snprintf(g_error, sizeof(g_error), "avifImageYUVToRGB: %s", avifResultToString(result));
        free(rgbImage.pixels);
        goto cleanup;
    }
    *out_width = (int) decoder->image->width;
    *out_height = (int) decoder->image->height;
    out = rgbImage.pixels;

cleanup:
    if (decoder) avifDecoderDestroy(decoder);
    return out;
}
