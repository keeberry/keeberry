#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "argon2.h"
#include "core.h"

#define KEY_LENGTH 32

typedef struct {
    JNIEnv *env;
    jobject cancel;
    jmethodID run;
} cancel_check;

/* Runs on the JNI thread between slices. An exception from run() stays pending, so Kotlin sees it
 * as soon as hash() returns. */
static int cancel_requested(void *data) {
    cancel_check *check = data;
    JNIEnv *env = check->env;
    (*env)->CallVoidMethod(env, check->cancel, check->run);
    return (*env)->ExceptionCheck(env);
}

/* Copies a Java array into native memory that can be wiped. An empty or null array gives NULL. */
static int copy_bytes(JNIEnv *env, jbyteArray array, uint8_t **bytes, uint32_t *length) {
    *bytes = NULL;
    *length = 0;
    if (array == NULL) return 1;
    jsize size = (*env)->GetArrayLength(env, array);
    if (size == 0) return 1;
    *bytes = malloc((size_t)size);
    if (*bytes == NULL) return 0;
    (*env)->GetByteArrayRegion(env, array, 0, size, (jbyte *)*bytes);
    *length = (uint32_t)size;
    return 1;
}

static void wipe_and_free(uint8_t *bytes, uint32_t length) {
    if (bytes == NULL) return;
    secure_wipe_memory(bytes, length);
    free(bytes);
}

/* Writes the 32-byte Argon2 output to `output` and returns an argon2.h error code. The caller has
 * already checked the parameters against the KDBX rules and the app's memory budget. */
JNIEXPORT jint JNICALL
Java_app_keeberry_kdbx_Argon2Jni_hash(JNIEnv *env, jclass clazz, jint type, jint version,
                                      jint passes, jint memory_kib, jint lanes, jbyteArray password,
                                      jbyteArray salt, jbyteArray secret, jbyteArray additional,
                                      jbyteArray output, jobject cancel) {
    (void)clazz;
    uint8_t *pwd, *salt_bytes, *secret_bytes, *ad;
    uint32_t pwd_length, salt_length, secret_length, ad_length;
    uint8_t out[KEY_LENGTH];
    int rc = ARGON2_MEMORY_ALLOCATION_ERROR;

    /* Each call runs even if an earlier one failed, so every pointer is set before cleanup. */
    int copied = copy_bytes(env, password, &pwd, &pwd_length);
    copied &= copy_bytes(env, salt, &salt_bytes, &salt_length);
    copied &= copy_bytes(env, secret, &secret_bytes, &secret_length);
    copied &= copy_bytes(env, additional, &ad, &ad_length);

    jclass runnable = (*env)->FindClass(env, "java/lang/Runnable");
    jmethodID run = runnable == NULL ? NULL : (*env)->GetMethodID(env, runnable, "run", "()V");
    if (copied && run != NULL) {
        cancel_check check = {env, cancel, run};
        argon2_context context = {
            .out = out,
            .outlen = KEY_LENGTH,
            .pwd = pwd,
            .pwdlen = pwd_length,
            .salt = salt_bytes,
            .saltlen = salt_length,
            .secret = secret_bytes,
            .secretlen = secret_length,
            .ad = ad,
            .adlen = ad_length,
            .t_cost = (uint32_t)passes,
            .m_cost = (uint32_t)memory_kib,
            .lanes = (uint32_t)lanes,
            .threads = (uint32_t)lanes,
            .version = (uint32_t)version,
            .allocate_cbk = NULL,
            .free_cbk = NULL,
            /* Wipe the key and secret once they are hashed rather than after the last pass. */
            .flags = ARGON2_FLAG_CLEAR_PASSWORD | ARGON2_FLAG_CLEAR_SECRET,
            .abort_cbk = cancel_requested,
            .abort_data = &check,
        };
        rc = argon2_ctx(&context, (argon2_type)type);
        if (rc == ARGON2_OK) {
            (*env)->SetByteArrayRegion(env, output, 0, KEY_LENGTH, (const jbyte *)out);
        }
    }

    secure_wipe_memory(out, sizeof out);
    wipe_and_free(pwd, pwd_length);
    wipe_and_free(salt_bytes, salt_length);
    wipe_and_free(secret_bytes, secret_length);
    wipe_and_free(ad, ad_length);
    return rc;
}
