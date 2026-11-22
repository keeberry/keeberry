#include <jni.h>
#include <stdint.h>

#include "aes_kdf.h"
#include "core.h"

#define ROTL8(x, shift) ((uint8_t)(((x) << (shift)) | ((x) >> (8 - (shift)))))

/* Builds the AES S-box from the field inverse and affine map, rather than a typed-in table. */
static void make_sbox(uint8_t sbox[256]) {
    uint8_t p = 1, q = 1;
    do {
        /* p walks the field multiplying by 3, and q by its inverse, so p * q stays 1. */
        p = (uint8_t)(p ^ (p << 1) ^ ((p & 0x80) ? 0x1b : 0));
        q ^= (uint8_t)(q << 1);
        q ^= (uint8_t)(q << 2);
        q ^= (uint8_t)(q << 4);
        q ^= (q & 0x80) ? 0x09 : 0;
        sbox[p] = (uint8_t)(q ^ ROTL8(q, 1) ^ ROTL8(q, 2) ^ ROTL8(q, 3) ^ ROTL8(q, 4) ^ 0x63);
    } while (p != 1);
    sbox[0] = 0x63;
}

/* The AES-256 key schedule from FIPS 197, section 5.2. Runs once per slice, so speed is moot. */
static void expand_key(const uint8_t key[32], uint8_t round_keys[AES_KDF_ROUND_KEYS_SIZE]) {
    uint8_t sbox[256];
    make_sbox(sbox);
    for (int i = 0; i < 32; ++i) round_keys[i] = key[i];
    uint8_t rcon = 1;
    for (int word = 8; word < 60; ++word) {
        uint8_t t[4];
        for (int i = 0; i < 4; ++i) t[i] = round_keys[4 * (word - 1) + i];
        if (word % 8 == 0) {
            uint8_t first = t[0];
            t[0] = (uint8_t)(sbox[t[1]] ^ rcon);
            t[1] = sbox[t[2]];
            t[2] = sbox[t[3]];
            t[3] = sbox[first];
            rcon = (uint8_t)((rcon << 1) ^ ((rcon & 0x80) ? 0x1b : 0));
        } else if (word % 8 == 4) {
            for (int i = 0; i < 4; ++i) t[i] = sbox[t[i]];
        }
        for (int i = 0; i < 4; ++i) {
            round_keys[4 * word + i] = (uint8_t)(round_keys[4 * (word - 8) + i] ^ t[i]);
        }
    }
}

JNIEXPORT jboolean JNICALL
Java_app_keeberry_kdbx_AesKdfJni_supported(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return aes_kdf_cpu_supported() ? JNI_TRUE : JNI_FALSE;
}

/* Applies `rounds` AES-KDF rounds to the 32-byte `key` in place under the 32-byte `seed`. */
JNIEXPORT jboolean JNICALL
Java_app_keeberry_kdbx_AesKdfJni_transform(JNIEnv *env, jclass clazz, jbyteArray seed, jbyteArray key,
                                           jint rounds) {
    (void)clazz;
    if (seed == NULL || key == NULL || rounds < 0 || !aes_kdf_cpu_supported() ||
        (*env)->GetArrayLength(env, seed) != 32 || (*env)->GetArrayLength(env, key) != 32) {
        return JNI_FALSE;
    }
    uint8_t seed_bytes[32], state[32], round_keys[AES_KDF_ROUND_KEYS_SIZE];
    (*env)->GetByteArrayRegion(env, seed, 0, 32, (jbyte *)seed_bytes);
    (*env)->GetByteArrayRegion(env, key, 0, 32, (jbyte *)state);
    expand_key(seed_bytes, round_keys);
    aes_kdf_rounds(round_keys, state, (uint32_t)rounds);
    (*env)->SetByteArrayRegion(env, key, 0, 32, (const jbyte *)state);
    secure_wipe_memory(seed_bytes, sizeof seed_bytes);
    secure_wipe_memory(state, sizeof state);
    secure_wipe_memory(round_keys, sizeof round_keys);
    return JNI_TRUE;
}
