/* Compiled with -maes. Only called after aes_kdf_cpu_supported(). */
#include <cpuid.h>
#include <wmmintrin.h>

#include "aes_kdf.h"

int aes_kdf_cpu_supported(void) {
    unsigned int eax, ebx, ecx, edx;
    if (!__get_cpuid(1, &eax, &ebx, &ecx, &edx)) return 0;
    return (ecx & bit_AES) != 0;
}

void aes_kdf_rounds(const uint8_t round_keys[AES_KDF_ROUND_KEYS_SIZE], uint8_t key[32], uint32_t rounds) {
    __m128i rk[15];
    for (int i = 0; i < 15; ++i) rk[i] = _mm_loadu_si128((const __m128i *)(round_keys + 16 * i));
    __m128i a = _mm_loadu_si128((const __m128i *)key);
    __m128i b = _mm_loadu_si128((const __m128i *)(key + 16));
    for (uint32_t round = 0; round < rounds; ++round) {
        a = _mm_xor_si128(a, rk[0]);
        b = _mm_xor_si128(b, rk[0]);
        for (int i = 1; i < 14; ++i) {
            a = _mm_aesenc_si128(a, rk[i]);
            b = _mm_aesenc_si128(b, rk[i]);
        }
        a = _mm_aesenclast_si128(a, rk[14]);
        b = _mm_aesenclast_si128(b, rk[14]);
    }
    _mm_storeu_si128((__m128i *)key, a);
    _mm_storeu_si128((__m128i *)(key + 16), b);
}
