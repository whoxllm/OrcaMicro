/* SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Minimal stand-in for OpenSSL's <openssl/md5.h>. libslic3r uses OpenSSL only for MD5
 * checksums, so the Android engine ships this small RFC 1321 implementation instead of
 * cross-compiling all of OpenSSL. Only the legacy MD5_* API that libslic3r calls is provided.
 */
#ifndef ORCAMICRO_OPENSSL_MD5_H
#define ORCAMICRO_OPENSSL_MD5_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MD5_DIGEST_LENGTH 16
#define MD5_CBLOCK 64

typedef struct MD5state_st {
    uint32_t state[4];
    uint64_t length;            /* total bytes processed */
    unsigned char buffer[MD5_CBLOCK];
    size_t buffered;            /* bytes waiting in buffer */
} MD5_CTX;

int MD5_Init(MD5_CTX *ctx);
int MD5_Update(MD5_CTX *ctx, const void *data, size_t len);
int MD5_Final(unsigned char *md, MD5_CTX *ctx);
unsigned char *MD5(const unsigned char *data, size_t len, unsigned char *md);

#ifdef __cplusplus
}
#endif

#endif /* ORCAMICRO_OPENSSL_MD5_H */
