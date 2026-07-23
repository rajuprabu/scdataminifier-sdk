/*
 * scdec - license-gated, decode-only image core (mobile SDK).
 *
 * ONE source, two build flavours chosen by -DSCDEC_FMT:
 *   SCDEC_FMT==1  flavour A   SCDEC_FMT==2  flavour B
 *
 * Neither flavour names its image format anywhere a shipped artifact can reveal it:
 *  - the public API (scdec.h) and exported symbols are format-neutral,
 *  - error strings never name a codec,
 *  - the container-shell constants this file must emit to rebuild a decodable file (which
 *    would otherwise appear verbatim in the binary's read-only data) are stored XOR-masked
 *    and unmasked into a scratch buffer only at decode time.
 * The build (visibility=hidden, --exclude-libs ALL, strip, decode-only dependencies) keeps
 * the linked decode library's own symbols and encoder strings out of the artifact.
 */
#include "scdec.h"
#include "../../native/src/license.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if !defined(SCDEC_FMT)
#error "define SCDEC_FMT=1 (flavour A) or 2 (flavour B)"
#endif

#if SCDEC_FMT == 1
#include <webp/decode.h>
#elif SCDEC_FMT == 2
#include <avif/avif.h>
#else
#error "SCDEC_FMT must be 1 or 2"
#endif

#if defined(_MSC_VER)
#define SCDEC_TLS __declspec(thread)
#else
#define SCDEC_TLS __thread
#endif

static SCDEC_TLS char g_err[128];
static void set_err(const char *m) { snprintf(g_err, sizeof(g_err), "%s", m ? m : "error"); }
const char *scdec_error(void) { return g_err; }

void scdec_free(uint8_t *p) { free(p); }

int scdec_license(const uint8_t *lic, int lic_len, const char *pkg) {
    if (!lic || lic_len <= 0 || !pkg) return SCDEC_LIC_MALFORMED;
    return sc_license_init(lic, (size_t) lic_len, pkg);
}
int scdec_licensed(void) { return sc_license_ok(); }

/* XOR mask for the stored container constants; unmasked only into a scratch buffer. */
#define MK 0x5A
static void unmask(unsigned char *dst, const unsigned char *src, int n) {
    for (int i = 0; i < n; i++) dst[i] = (unsigned char) (src[i] ^ MK);
}

/* ---- container constants (stored XOR-masked; see generator in the repo history) ---- */
#if SCDEC_FMT == 1
static const unsigned char O_RIFF[4] = {0x08,0x13,0x1c,0x1c};
static const unsigned char O_WEBP[4] = {0x0d,0x1f,0x18,0x0a};
static const unsigned char O_VP8[4]  = {0x0c,0x0a,0x62,0x7a};
#else
static const unsigned char O_FTYP[32] = {0x5a,0x5a,0x5a,0x7a,0x3c,0x2e,0x23,0x2a,0x3b,0x2c,0x33,0x3c,0x5a,0x5a,0x5a,0x5a,0x3b,0x2c,0x33,0x3c,0x37,0x33,0x3c,0x6b,0x37,0x33,0x3b,0x3c,0x17,0x1b,0x6b,0x18};
static const unsigned char O_HDLR[33] = {0x5a,0x5a,0x5a,0x7b,0x32,0x3e,0x36,0x28,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x2a,0x33,0x39,0x2e,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a};
static const unsigned char O_PITM[14] = {0x5a,0x5a,0x5a,0x54,0x2a,0x33,0x2e,0x37,0x5a,0x5a,0x5a,0x5a,0x5a,0x5b};
static const unsigned char O_IINF[40] = {0x5a,0x5a,0x5a,0x72,0x33,0x33,0x34,0x3c,0x5a,0x5a,0x5a,0x5a,0x5a,0x5b,0x5a,0x5a,0x5a,0x40,0x33,0x34,0x3c,0x3f,0x58,0x5a,0x5a,0x5a,0x5a,0x5b,0x5a,0x5a,0x3b,0x2c,0x6a,0x6b,0x19,0x35,0x36,0x35,0x28,0x5a};
static const unsigned char O_PIXI[16] = {0x5a,0x5a,0x5a,0x4a,0x2a,0x33,0x22,0x33,0x5a,0x5a,0x5a,0x5a,0x59,0x52,0x52,0x52};
static const unsigned char O_COLR[19] = {0x5a,0x5a,0x5a,0x49,0x39,0x35,0x36,0x28,0x34,0x39,0x36,0x22,0x5a,0x5b,0x5a,0x57,0x5a,0x5c,0xda};
static const unsigned char O_IPMA[23] = {0x5a,0x5a,0x5a,0x4d,0x33,0x2a,0x37,0x3b,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5a,0x5b,0x5a,0x5b,0x5e,0x5b,0x58,0xd9,0x5e};
static const unsigned char O_ILOC[22] = {0x5a,0x5a,0x5a,0x44,0x33,0x36,0x35,0x39,0x5a,0x5a,0x5a,0x5a,0x1e,0x5a,0x5a,0x5b,0x5a,0x5b,0x5a,0x5a,0x5a,0x5b};
static const unsigned char O_META[4] = {0x37,0x3f,0x2e,0x3b};
static const unsigned char O_MDAT[4] = {0x37,0x3e,0x3b,0x2e};
static const unsigned char O_ISPE[4] = {0x33,0x29,0x2a,0x3f};
static const unsigned char O_AV1C[4] = {0x3b,0x2c,0x6b,0x19};
static const unsigned char O_IPCO[4] = {0x33,0x2a,0x39,0x35};
static const unsigned char O_IPRP[4] = {0x33,0x2a,0x28,0x2a};
#endif

/* Parse the 1-byte image descriptor: bits1-4 type, bit5 header-present, bits6-8 version. */
static int descriptor(const uint8_t *value, int len, int *type, int *hdr, const uint8_t **data, int *dlen) {
    if (!value || len < 2) return -1;
    int b = value[0] & 0xFF;
    *type = b & 0x0F;
    *hdr = (b & 0x10) != 0;
    *data = value + 1;
    *dlen = len - 1;
    return 0;
}

/* ==================== flavour A ==================== */
#if SCDEC_FMT == 1

/* Rebuild the full file from the version-1 headerless payload (raw frame bitstream). */
static uint8_t *rebuild(const uint8_t *payload, int plen, int *out_len) {
    int pad = plen & 1;
    int total = 20 + plen + pad;
    uint8_t *f = (uint8_t *) malloc(total);
    if (!f) return NULL;
    unsigned char t4[4];
    int p = 0;
    unmask(t4, O_RIFF, 4); memcpy(f + p, t4, 4); p += 4;
    int riff = 4 + 8 + plen + pad;
    f[p++] = (uint8_t) riff; f[p++] = (uint8_t) (riff >> 8); f[p++] = (uint8_t) (riff >> 16); f[p++] = (uint8_t) (riff >> 24);
    unmask(t4, O_WEBP, 4); memcpy(f + p, t4, 4); p += 4;
    unmask(t4, O_VP8, 4);  memcpy(f + p, t4, 4); p += 4;
    f[p++] = (uint8_t) plen; f[p++] = (uint8_t) (plen >> 8); f[p++] = (uint8_t) (plen >> 16); f[p++] = (uint8_t) (plen >> 24);
    memcpy(f + p, payload, plen); p += plen;
    if (pad) f[p++] = 0;
    *out_len = total;
    return f;
}

static uint8_t *decode_file(const uint8_t *file, int flen, int *w, int *h) {
    return WebPDecodeRGB(file, (size_t) flen, w, h);
}
static int info_file(const uint8_t *file, int flen, int *w, int *h) {
    return WebPGetInfo(file, (size_t) flen, w, h) ? 0 : -1;
}
static void free_dec(uint8_t *p) { WebPFree(p); }

/* ==================== flavour B ==================== */
#else

/* Rebuild the full file from [w u16][h u16][cfgLen u8][cfg][payload]. */
static uint8_t *rebuild(const uint8_t *d, int dlen, int *out_len) {
    if (dlen < 5) return NULL;
    int width = (d[0] << 8) | d[1];
    int height = (d[2] << 8) | d[3];
    int cfgLen = d[4];
    if (5 + cfgLen > dlen) return NULL;
    const uint8_t *cfg = d + 5;
    const uint8_t *payload = d + 5 + cfgLen;
    int payLen = dlen - 5 - cfgLen;

    unsigned char FTYP[32], HDLR[33], PITM[14], IINF[40], PIXI[16], COLR[19], IPMA[23], ILOC[22];
    unsigned char META[4], MDAT[4], ISPE[4], AV1C[4], IPCO[4], IPRP[4];
    unmask(FTYP,O_FTYP,32); unmask(HDLR,O_HDLR,33); unmask(PITM,O_PITM,14); unmask(IINF,O_IINF,40);
    unmask(PIXI,O_PIXI,16); unmask(COLR,O_COLR,19); unmask(IPMA,O_IPMA,23); unmask(ILOC,O_ILOC,22);
    unmask(META,O_META,4); unmask(MDAT,O_MDAT,4); unmask(ISPE,O_ISPE,4);
    unmask(AV1C,O_AV1C,4); unmask(IPCO,O_IPCO,4); unmask(IPRP,O_IPRP,4);

    int ispeSize = 8 + 4 + 4 + 4;          /* box + 00000000 + w + h */
    int av1cSize = 8 + cfgLen;
    int ipcoSize = 8 + ispeSize + 16 + av1cSize + 19; /* PIXI=16, COLR=19 */
    int iprpSize = 8 + ipcoSize + 23;                 /* IPMA=23 */
    int metaSize = 12 + 33 + 14 + 30 + 40 + iprpSize; /* hdlr pitm iloc(30) iinf iprp */
    int shellSize = 32 + metaSize + 8;                /* ftyp + meta + mdat header */
    int total = shellSize + payLen;

    uint8_t *f = (uint8_t *) malloc(total);
    if (!f) return NULL;
    int p = 0;
    #define PUT(buf,n) do { memcpy(f+p,(buf),(n)); p+=(n); } while(0)
    #define PUT_U32(v) do { f[p++]=(uint8_t)((v)>>24); f[p++]=(uint8_t)((v)>>16); f[p++]=(uint8_t)((v)>>8); f[p++]=(uint8_t)(v); } while(0)
    PUT(FTYP,32);
    PUT_U32(metaSize); PUT(META,4); PUT_U32(0);
    PUT(HDLR,33); PUT(PITM,14);
    PUT(ILOC,22); PUT_U32(shellSize); PUT_U32(payLen);
    PUT(IINF,40);
    /* iprp { ipco { ispe pixi av1C colr } ipma } */
    PUT_U32(iprpSize); PUT(IPRP,4);
    PUT_U32(ipcoSize); PUT(IPCO,4);
    PUT_U32(ispeSize); PUT(ISPE,4); PUT_U32(0); PUT_U32(width); PUT_U32(height);
    PUT(PIXI,16);
    PUT_U32(av1cSize); PUT(AV1C,4); PUT(cfg,cfgLen);
    PUT(COLR,19);
    PUT(IPMA,23);
    /* mdat */
    PUT_U32(8 + payLen); PUT(MDAT,4); PUT(payload,payLen);
    #undef PUT
    #undef PUT_U32
    *out_len = total;
    return f;
}

static uint8_t *decode_file(const uint8_t *file, int flen, int *w, int *h) {
    avifDecoder *dec = avifDecoderCreate();
    if (!dec) return NULL;
    uint8_t *out = NULL;
    if (avifDecoderSetIOMemory(dec, file, (size_t) flen) != AVIF_RESULT_OK) goto done;
    if (avifDecoderParse(dec) != AVIF_RESULT_OK) goto done;
    if (avifDecoderNextImage(dec) != AVIF_RESULT_OK) goto done;
    {
        avifRGBImage rgb;
        avifRGBImageSetDefaults(&rgb, dec->image);
        rgb.format = AVIF_RGB_FORMAT_RGB;
        rgb.depth = 8;
        rgb.rowBytes = dec->image->width * 3;
        rgb.pixels = (uint8_t *) malloc((size_t) rgb.rowBytes * dec->image->height);
        if (!rgb.pixels) goto done;
        if (avifImageYUVToRGB(dec->image, &rgb) != AVIF_RESULT_OK) { free(rgb.pixels); goto done; }
        *w = (int) dec->image->width;
        *h = (int) dec->image->height;
        out = rgb.pixels;
    }
done:
    avifDecoderDestroy(dec);
    return out;
}
/* dims are in the value's first 4 bytes; no decode needed for info */
static int info_file(const uint8_t *file, int flen, int *w, int *h) { (void)file;(void)flen;(void)w;(void)h; return -1; }
static void free_dec(uint8_t *p) { free(p); }
#endif

/* ==================== shared entry points ==================== */

int scdec_info(const uint8_t *value, int value_len, int *out_w, int *out_h) {
    if (!sc_license_ok()) { set_err("not licensed"); return -1; }
    int type, hdr, dlen; const uint8_t *data;
    if (descriptor(value, value_len, &type, &hdr, &data, &dlen) != 0) { set_err("bad value"); return -1; }
#if SCDEC_FMT == 2
    if (!hdr && dlen >= 4) { *out_w = (data[0] << 8) | data[1]; *out_h = (data[2] << 8) | data[3]; return 0; }
#endif
    int flen; uint8_t *file = hdr ? NULL : rebuild(data, dlen, &flen);
    const uint8_t *use = hdr ? data : file;
    int ulen = hdr ? dlen : flen;
    if (!use) { set_err("rebuild failed"); return -1; }
    int r = info_file(use, ulen, out_w, out_h);
    free(file);
    if (r != 0) set_err("info failed");
    return r;
}

uint8_t *scdec_open(const uint8_t *value, int value_len, int *out_w, int *out_h) {
    if (!sc_license_ok()) { set_err("not licensed"); return NULL; }
    int type, hdr, dlen; const uint8_t *data;
    if (descriptor(value, value_len, &type, &hdr, &data, &dlen) != 0) { set_err("bad value"); return NULL; }

    int flen; uint8_t *file = hdr ? NULL : rebuild(data, dlen, &flen);
    const uint8_t *use = hdr ? data : file;
    int ulen = hdr ? dlen : flen;
    if (!use) { set_err("rebuild failed"); return NULL; }

    int w = 0, h = 0;
    uint8_t *raw = decode_file(use, ulen, &w, &h);
    free(file);
    if (!raw) { set_err("decode failed"); return NULL; }

    /* copy into a plain malloc buffer so scdec_free() is uniform across flavours */
    uint8_t *out = (uint8_t *) malloc((size_t) w * h * 3);
    if (!out) { free_dec(raw); set_err("out of memory"); return NULL; }
    memcpy(out, raw, (size_t) w * h * 3);
    free_dec(raw);
    *out_w = w; *out_h = h;
    return out;
}
