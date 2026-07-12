/* Interop test: verify a Java-issued .lic (base64-decoded to raw bytes) with the C gate. */
#include <stdio.h>
#include <stdlib.h>
#include "../src/license.h"

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s <license.bin> <package>\n", argv[0]); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("open"); return 2; }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char *buf = (unsigned char *) malloc(n > 0 ? n : 1);
    if (fread(buf, 1, n, f) != (size_t) n) { fprintf(stderr, "read error\n"); return 2; }
    fclose(f);

    int r = sc_license_init(buf, (size_t) n, argv[2]);
    const char *msg =
        r == 0 ? "OK (licensed)" :
        r == -1 ? "MALFORMED" :
        r == -2 ? "BAD SIGNATURE" :
        r == -3 ? "PACKAGE MISMATCH" :
        r == -4 ? "NOT YET VALID" :
        r == -5 ? "EXPIRED" : "?";
    printf("sc_license_init(pkg=\"%s\") -> %d  %s   (sc_license_ok=%d)\n", argv[2], r, msg, sc_license_ok());
    free(buf);
    return r == 0 ? 0 : 1;
}
