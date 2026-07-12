/*
 * License verification for the SCData native library. Parses the canonical .lic blob
 * (see Java LicenseFormat), verifies the ECDSA P-256 signature with the embedded public key
 * (micro-ecc), enforces the licensed package name and validity window, and latches a
 * process-wide "licensed" flag that the library's value methods check.
 */
#include "license.h"
#include "sha256.h"
#include "license_pubkey.h"          /* defines SC_LICENSE_PUBKEY[64] */
#include "../third_party/micro-ecc/uECC.h"

#include <string.h>
#include <time.h>

static const unsigned char LIC_MAGIC[4] = { 'T', 'L', 'I', 'C' };
#define LIC_VERSION 1
#define LIC_SIG_LEN 64

static int g_licensed = 0;

/* Bounds-checked big-endian readers over the blob. */
typedef struct { const unsigned char *p; size_t len; size_t pos; int err; } cur_t;

static unsigned read_u8(cur_t *c) {
    if (c->err || c->pos + 1 > c->len) { c->err = 1; return 0; }
    return c->p[c->pos++];
}
static unsigned read_u16(cur_t *c) {
    if (c->err || c->pos + 2 > c->len) { c->err = 1; return 0; }
    unsigned v = ((unsigned)c->p[c->pos] << 8) | c->p[c->pos+1];
    c->pos += 2; return v;
}
static unsigned long long read_u64(cur_t *c) {
    if (c->err || c->pos + 8 > c->len) { c->err = 1; return 0; }
    unsigned long long v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | c->p[c->pos + i];
    c->pos += 8; return v;
}
/* Reads a u16-length string field; returns pointer+len (not NUL-terminated). */
static const unsigned char *read_str(cur_t *c, unsigned *out_len) {
    unsigned n = read_u16(c);
    if (c->err || c->pos + n > c->len) { c->err = 1; *out_len = 0; return 0; }
    const unsigned char *s = c->p + c->pos;
    c->pos += n; *out_len = n; return s;
}

int sc_license_init(const unsigned char *lic, size_t lic_len, const char *calling_package) {
    g_licensed = 0;
    if (!lic || !calling_package || lic_len < 7) return SC_LIC_ERR_MALFORMED;

    cur_t c = { lic, lic_len, 0, 0 };
    if (memcmp(lic, LIC_MAGIC, 4) != 0) return SC_LIC_ERR_MALFORMED;
    c.pos = 4;
    if (read_u8(&c) != LIC_VERSION) return SC_LIC_ERR_MALFORMED;

    unsigned pkg_len; const unsigned char *pkg = read_str(&c, &pkg_len);
    unsigned t; read_str(&c, &t); /* licensedTo */
    read_str(&c, &t);            /* city */
    read_str(&c, &t);            /* state */
    read_str(&c, &t);            /* country */
    unsigned long long valid_from = read_u64(&c);
    unsigned long long valid_till = read_u64(&c);
    unsigned no_date_check = read_u8(&c);
    if (c.err) return SC_LIC_ERR_MALFORMED;

    size_t signed_len = c.pos;               /* signature covers bytes [0, signed_len) */

    unsigned sig_len = read_u16(&c);
    if (c.err || sig_len != LIC_SIG_LEN || c.pos + LIC_SIG_LEN > lic_len) return SC_LIC_ERR_MALFORMED;
    const unsigned char *sig = lic + c.pos;

    /* 1) signature over the signed region */
    unsigned char hash[32];
    sc_sha256(lic, signed_len, hash);
    if (!uECC_verify(SC_LICENSE_PUBKEY, hash, sizeof(hash), sig, uECC_secp256r1())) {
        return SC_LIC_ERR_SIGNATURE;
    }

    /* 2) package binding: licensed package must equal the calling package */
    size_t call_len = strlen(calling_package);
    if (pkg_len != call_len || memcmp(pkg, calling_package, call_len) != 0) {
        return SC_LIC_ERR_PACKAGE;
    }

    /* 3) validity window (unless disabled by the license) */
    if (!no_date_check) {
        unsigned long long now = (unsigned long long) time(NULL);
        if (now < valid_from) return SC_LIC_ERR_NOT_YET;
        if (now > valid_till) return SC_LIC_ERR_EXPIRED;
    }

    g_licensed = 1;
    return SC_LIC_OK;
}

int sc_license_ok(void) {
    return g_licensed;
}
