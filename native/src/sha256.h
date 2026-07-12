#ifndef SC_SHA256_H
#define SC_SHA256_H
#include <stddef.h>
#include <stdint.h>

/* Minimal SHA-256 (public-domain style). Produces a 32-byte digest. */
void sc_sha256(const uint8_t *data, size_t len, uint8_t out[32]);

#endif
