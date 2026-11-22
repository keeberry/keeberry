cmake_minimum_required(VERSION 4.1)
project(keyberry C)

# The reference Argon2 that KeePassXC links, see argon2/README.md for its source and local changes,
# and AES-KDF on the CPU's AES instructions.
add_library(keyberry_crypto SHARED
        argon2_jni.c
        aes_kdf_jni.c
        argon2/src/argon2.c
        argon2/src/core.c
        argon2/src/encoding.c
        argon2/src/ref.c
        argon2/src/thread.c
        argon2/src/blake2/blake2b.c)

# Each ABI's AES-KDF loop is the only code built with its AES instructions, and it runs only after a
# runtime check, so devices without them still load the library and fall back to Java.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_sources(keyberry_crypto PRIVATE aes_kdf_arm64.c)
    set_source_files_properties(aes_kdf_arm64.c PROPERTIES COMPILE_OPTIONS "-march=armv8-a+crypto")
elseif(ANDROID_ABI STREQUAL "x86_64")
    target_sources(keyberry_crypto PRIVATE aes_kdf_x86_64.c)
    set_source_files_properties(aes_kdf_x86_64.c PROPERTIES COMPILE_OPTIONS "-maes")
else()
    message(FATAL_ERROR "No AES-KDF implementation for ${ANDROID_ABI}")
endif()

target_include_directories(keyberry_crypto PRIVATE argon2/include argon2/src)
set_target_properties(keyberry_crypto PROPERTIES
        C_STANDARD 11
        C_STANDARD_REQUIRED ON
        # Export only the JNI entry points.
        C_VISIBILITY_PRESET hidden)
# Debug builds otherwise compile with -O0, which makes unlocking several times slower.
target_compile_options(keyberry_crypto PRIVATE -O3 -Wall)
