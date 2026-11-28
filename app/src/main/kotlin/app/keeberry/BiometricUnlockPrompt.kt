package app.keeberry

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import javax.crypto.Cipher

/** How a biometric prompt ended. */
internal sealed interface PromptResult {
    /** The biometric matched, and [cipher] can now be used once. */
    class Succeeded(val cipher: Cipher) : PromptResult

    /** The user dismissed the prompt, or KeeBerry left the screen. */
    data object Cancelled : PromptResult

    /** The prompt gave up, with [message] saying why in words for the form. */
    class Failed(val message: Int) : PromptResult
}

/**
 * Shows the system's biometric prompt, which authorizes [cipher] for one use with a strong biometric.
 * The device's PIN, pattern or password isn't offered: the key needs a biometric. [onResult] runs on
 * the main thread once the prompt ends, and [signal] dismisses it early.
 */
internal fun Activity.showBiometricPrompt(
    title: String,
    subtitle: String?,
    /** What the button that closes the prompt says. */
    dismiss: String,
    cipher: Cipher,
    signal: CancellationSignal,
    onResult: (PromptResult) -> Unit,
) {
    val executor = mainExecutor
    val builder = BiometricPrompt.Builder(this)
        .setTitle(title)
        .setNegativeButton(dismiss, executor) { _, _ -> onResult(PromptResult.Cancelled) }
    if (subtitle != null) builder.setSubtitle(subtitle)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setConfirmationRequired(false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    }
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val authorized = result.cryptoObject?.cipher
            onResult(if (authorized != null) PromptResult.Succeeded(authorized) else PromptResult.Failed(R.string.device_unlock_failed))
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onResult(
                when (errorCode) {
                    BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                    // The dismiss button reports through its own listener instead.
                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> PromptResult.Cancelled
                    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
                    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> PromptResult.Failed(R.string.device_unlock_lockout)
                    else -> PromptResult.Failed(R.string.device_unlock_failed)
                },
            )
        }

        // A biometric that didn't match: the prompt says so and lets the user try again.
        override fun onAuthenticationFailed() {}
    }
    builder.build().authenticate(BiometricPrompt.CryptoObject(cipher), signal, executor, callback)
}
