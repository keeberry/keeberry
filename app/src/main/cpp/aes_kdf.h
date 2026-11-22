#ifndef KEEBERRY_AES_KDF_H
#define KEEBERRY_AES_KDF_H

#include <stdint.h>

/* AES-256 has 15 round keys of 16 bytes. */
#define AES_KDF_ROUND_KEYS_SIZE 240

/* Nonzero when this CPU has the AES instructions that aes_kdf_rounds() uses. */
int aes_kdf_cpu_supported(void);

/* Encrypts both 16-byte halves of `key` in place with AES-256 under `round_keys`, `rounds` times.
 * Only call it when aes_kdf_cpu_supported() returned nonzero. Each ABI implements it in its own
 * file, compiled with that ABI's AES instructions enabled. */
void aes_kdf_rounds(const uint8_t round_keys[AES_KDF_ROUND_KEYS_SIZE], uint8_t key[32], uint32_t rounds);

#endif
