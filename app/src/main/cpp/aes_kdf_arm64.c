/* Compiled with -march=armv8-a+crypto. Only called after aes_kdf_cpu_supported(). */
#include <arm_neon.h>
#include <asm/hwcap.h>
#include <sys/auxv.h>

#include "aes_kdf.h"

int aes_kdf_cpu_supported(void) {
    return (getauxval(AT_HWCAP) & HWCAP_AES) != 0;
}

void aes_kdf_rounds(const uint8_t round_keys[AES_KDF_ROUND_KEYS_SIZE], uint8_t key[32], uint32_t rounds) {
    uint8x16_t rk[15];
    for (int i = 0; i < 15; ++i) rk[i] = vld1q_u8(round_keys + 16 * i);
    uint8x16_t a = vld1q_u8(key);
    uint8x16_t b = vld1q_u8(key + 16);
    /* AESE adds the round key before SubBytes and ShiftRows, so the last key is added after. The
     * two halves are independent, which lets the CPU overlap their instructions. */
    for (uint32_t round = 0; round < rounds; ++round) {
        for (int i = 0; i < 13; ++i) {
            a = vaesmcq_u8(vaeseq_u8(a, rk[i]));
            b = vaesmcq_u8(vaeseq_u8(b, rk[i]));
        }
        a = veorq_u8(vaeseq_u8(a, rk[13]), rk[14]);
        b = veorq_u8(vaeseq_u8(b, rk[13]), rk[14]);
    }
    vst1q_u8(key, a);
    vst1q_u8(key + 16, b);
}
