package app.keeberry

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.core.net.toUri
import app.keeberry.kdbx.hexString
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONException
import org.json.JSONObject

/**
 * Biometric unlock: for each recent database it was turned on for, the master password encrypted with
 * an Android Keystore key that only a strong biometric, such as a fingerprint, can use, and the other
 * credentials the database needs. The key file is kept as a link KeeBerry keeps access to, and a
 * YubiKey is still asked each time, so biometrics stand in for the typed password alone. Each database
 * has its own key, which the system deletes for good when a new biometric is enrolled; the database
 * then unlocks with its password until biometric unlock is turned on again.
 */
internal class DeviceUnlock(context: Context) {
    /** What biometric unlock holds for a database. [password] is encrypted, or null if it has none. */
    class Saved(
        val uri: Uri,
        val iv: ByteArray,
        val password: ByteArray?,
        val keyFile: Uri?,
        val keyName: String?,
        val useYubiKey: Boolean,
    )

    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("device_unlock", Context.MODE_PRIVATE)

    /** Whether the device can protect a key with a strong biometric that is set up now. */
    fun available(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            app.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
            @Suppress("DEPRECATION")
            app.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        // Android 9 can't tell whether a fingerprint is enrolled; making the key finds out.
        else -> app.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) &&
            app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }

    fun saved(uri: Uri): Saved? {
        val item = try {
            JSONObject(preferences.getString(uri.toString(), null) ?: return null)
        } catch (_: JSONException) {
            return null
        }
        return try {
            Saved(
                uri,
                iv = decode(item.getString(IV)),
                password = item.optString(PASSWORD).takeIf(String::isNotEmpty)?.let(::decode),
                keyFile = item.optString(KEY_FILE).takeIf(String::isNotEmpty)?.toUri(),
                keyName = item.optString(KEY_NAME).takeIf(String::isNotEmpty),
                useYubiKey = item.optBoolean(YUBIKEY),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** How many databases biometric unlock is on for. */
    fun count(): Int = preferences.all.size

    /**
     * A cipher that encrypts with a new key for [uri], replacing any it had. It works once a biometric
     * prompt authenticates it. Throws [GeneralSecurityException] if the device can't make the key, for
     * example on Android 9 with no fingerprint enrolled.
     */
    fun encrypter(uri: Uri): Cipher {
        val alias = aliasOf(uri)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // Every use needs a strong biometric: the device's PIN, pattern or password doesn't unlock it.
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        generator.init(spec)
        val key = generator.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /**
     * A cipher that decrypts what [saved] holds once a biometric prompt authenticates it, or null if
     * its key is gone, as after a new fingerprint was enrolled. Biometric unlock is then turned off
     * for that database.
     */
    fun decrypter(saved: Saved): Cipher? {
        val key = try {
            keyStore().getKey(aliasOf(saved.uri), null) as? SecretKey
        } catch (_: GeneralSecurityException) {
            null
        }
        if (key == null) {
            forget(saved.uri)
            return null
        }
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, saved.iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            forget(saved.uri)
            null
        }
    }

    /**
     * Turns biometric unlock on for [uri] with [cipher], which a biometric prompt authenticated, keeping
     * access to [keyFile] across restarts. Returns false if the provider won't let KeeBerry keep it.
     */
    fun save(
        uri: Uri,
        cipher: Cipher,
        password: CharArray?,
        keyFile: Uri?,
        keyName: String?,
        useYubiKey: Boolean,
    ): Boolean {
        if (keyFile != null && !keepAccess(keyFile)) return false
        val plain = password?.let(::encode) ?: ByteArray(0)
        val sealed = try {
            cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        val item = JSONObject()
            .put(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put(YUBIKEY, useYubiKey)
        if (password != null) item.put(PASSWORD, Base64.encodeToString(sealed, Base64.NO_WRAP))
        if (keyFile != null) item.put(KEY_FILE, keyFile.toString())
        if (keyName != null) item.put(KEY_NAME, keyName)
        val previous = saved(uri)?.keyFile
        preferences.edit { putString(uri.toString(), item.toString()) }
        if (previous != null && previous != keyFile) releaseKeyFile(previous)
        return true
    }

    /** The master password [saved] holds, decrypted with [cipher] once a prompt authenticated it. */
    fun password(saved: Saved, cipher: Cipher): CharArray? {
        val sealed = saved.password ?: return null
        val plain = cipher.doFinal(sealed)
        return try {
            decodeChars(plain)
        } finally {
            plain.fill(0)
        }
    }

    /** Turns biometric unlock off for [uri], deleting its key and giving up its key file. */
    fun forget(uri: Uri) {
        val keyFile = saved(uri)?.keyFile
        preferences.edit { remove(uri.toString()) }
        deleteKey(uri)
        keyFile?.let(::releaseKeyFile)
    }

    /** Turns biometric unlock off for every database. */
    fun forgetAll() {
        preferences.all.keys.forEach { forget(it.toUri()) }
    }

    private fun deleteKey(uri: Uri) {
        try {
            keyStore().deleteEntry(aliasOf(uri))
        } catch (_: GeneralSecurityException) {
            // Already gone.
        }
    }

    private fun keepAccess(keyFile: Uri): Boolean = try {
        app.contentResolver.takePersistableUriPermission(keyFile, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        // A provider of KeeBerry's own, as in debug builds, needs no grant.
        keyFile.authority?.let { app.packageManager.resolveContentProvider(it, 0)?.packageName } == app.packageName
    }

    /** Gives up access to [keyFile] unless another database's biometric unlock or a recent database uses it. */
    private fun releaseKeyFile(keyFile: Uri) {
        val stillUsed = preferences.all.keys.any { saved(it.toUri())?.keyFile == keyFile } || RecentDatabases(app).contains(keyFile)
        if (stillUsed) return
        try {
            app.contentResolver.releasePersistableUriPermission(keyFile, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Never kept, or already gone.
        }
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV = "iv"
        const val PASSWORD = "password"
        const val KEY_FILE = "key_file"
        const val KEY_NAME = "key_name"
        const val YUBIKEY = "yubikey"

        /** One key per database, named after its link without spelling it out. */
        fun aliasOf(uri: Uri): String =
            "device_unlock_" + MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).hexString()

        fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

        /** UTF-8 bytes of [chars], without a String that would linger in memory. */
        fun encode(chars: CharArray): ByteArray {
            val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            if (buffer.hasArray()) buffer.array().fill(0)
            return bytes
        }

        fun decodeChars(bytes: ByteArray): CharArray {
            val buffer = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
            val chars = CharArray(buffer.remaining()).also(buffer::get)
            if (buffer.hasArray()) buffer.array().fill('\u0000')
            return chars
        }
    }
}
