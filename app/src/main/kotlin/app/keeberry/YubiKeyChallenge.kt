package app.keeberry

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class YubiKeyException(val messageId: Int) : IOException()

/**
 * A single challenge for the current unlock or new database. [onPrompt] runs on the main thread,
 * and gets [afterResponse] once the key has answered or the wait has ended. NFC discovery follows
 * the activity passed to [attach], which changes when the screen is recreated.
 */
internal class YubiKeyChallenge(
    context: Context,
    private val slot: Slot,
    private val afterResponse: Int,
    private val onPrompt: (Int) -> Unit,
) : Closeable, DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val hasUsbHost = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    private val hasNfc = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
    private val manager = YubiKitManager(appContext)
    private val device = CompletableFuture<YubiKeyDevice>()
    private val closed = AtomicBoolean()
    private val touchPrompted = AtomicBoolean()

    // Used on the main thread only.
    private var activity: ComponentActivity? = null
    private var discovering = false
    private var usbListening = false
    private var nfcActivity: Activity? = null
    private var nfcOff = false
    private var watchingNfc = false

    // Quick Settings doesn't pause the activity, so NFC turned on there is only seen through this.
    private val nfcState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF)) {
                NfcAdapter.STATE_ON -> activity
                    ?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                    ?.let(::startNfc)
                NfcAdapter.STATE_OFF -> if (!closed.get() && !nfcOff) {
                    stopNfc()
                    showNfcOff()
                }
            }
        }
    }

    private val command = object : CommandState() {
        override fun onKeepAliveStatus(status: Byte) {
            if (status == CommandState.STATUS_UPNEEDED && touchPrompted.compareAndSet(false, true)) {
                main.post { if (!closed.get()) onPrompt(R.string.database_yubikey_touch) }
            }
        }
    }

    /** Listens for NFC on [activity] from now on. Called on the main thread. */
    fun attach(activity: ComponentActivity) {
        if (closed.get() || this.activity === activity) return
        this.activity?.lifecycle?.removeObserver(this)
        stopNfc()
        this.activity = activity
        // Adding an observer replays the lifecycle, so a resumed activity starts NFC right away.
        if (discovering) activity.lifecycle.addObserver(this)
    }

    fun respond(challenge: ByteArray): ByteArray {
        main.post(::startDiscovery)
        try {
            val key = awaitDevice()
            if (closed.get()) throw YubiKeyException(R.string.database_yubikey_failed)
            val response = when {
                key.supportsConnection(OtpConnection::class.java) ->
                    key.openConnection(OtpConnection::class.java).use { connection ->
                        YubiOtpSession(connection).calculateHmacSha1(slot, challenge, command)
                    }
                key.supportsConnection(SmartCardConnection::class.java) ->
                    key.openConnection(SmartCardConnection::class.java).use { connection ->
                        YubiOtpSession(connection).calculateHmacSha1(slot, challenge, command)
                    }
                else -> throw YubiKeyException(R.string.database_yubikey_failed)
            }
            if (closed.get()) {
                response.fill(0)
                throw YubiKeyException(R.string.database_yubikey_failed)
            }
            return response
        } catch (error: YubiKeyException) {
            throw error
        } catch (_: Exception) {
            throw YubiKeyException(R.string.database_yubikey_failed)
        } finally {
            close()
            main.post { onPrompt(afterResponse) }
        }
    }

    private fun awaitDevice(): YubiKeyDevice = try {
        device.get(45, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        throw YubiKeyException(R.string.database_yubikey_timeout)
    } catch (error: ExecutionException) {
        throw (error.cause as? YubiKeyException ?: YubiKeyException(R.string.database_yubikey_failed))
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw YubiKeyException(R.string.database_yubikey_failed)
    }

    private fun startDiscovery() {
        if (closed.get()) return
        discovering = true
        onPrompt(R.string.database_yubikey_waiting)
        if (hasUsbHost) {
            try {
                manager.startUsbDiscovery(UsbConfiguration()) { device.complete(it) }
                usbListening = true
            } catch (_: SecurityException) {
                manager.stopUsbDiscovery()
            }
        }
        if (!usbListening && !hasNfc) {
            device.completeExceptionally(YubiKeyException(R.string.database_yubikey_unavailable))
            return
        }
        if (hasNfc) {
            ContextCompat.registerReceiver(appContext, nfcState,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            watchingNfc = true
        }
        // Adding the observer to a resumed activity calls onResume, which starts NFC.
        activity?.lifecycle?.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        (owner as? Activity)?.let(::startNfc)
    }

    private fun startNfc(target: Activity) {
        if (closed.get() || device.isDone || nfcActivity != null || !hasNfc) return
        try {
            val configuration = NfcConfiguration().skipNdefCheck(true).timeout(5000)
            manager.startNfcDiscovery(configuration, target) { device.complete(it) }
            nfcActivity = target
            if (nfcOff) {
                nfcOff = false
                onPrompt(R.string.database_yubikey_waiting)
            }
        } catch (error: NfcNotAvailable) {
            // A key can still connect by USB. Without USB, the wait continues until NFC is turned on.
            if (error.isDisabled) showNfcOff()
        }
    }

    private fun showNfcOff() {
        nfcOff = true
        onPrompt(if (usbListening) R.string.database_yubikey_nfc_off else R.string.database_yubikey_nfc_off_only)
    }

    override fun onPause(owner: LifecycleOwner) = stopNfc()

    private fun stopNfc() {
        val target = nfcActivity ?: return
        nfcActivity = null
        manager.stopNfcDiscovery(target)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        command.cancel()
        device.completeExceptionally(YubiKeyException(R.string.database_yubikey_failed))
        if (Looper.myLooper() == Looper.getMainLooper()) stopDiscovery() else main.post(::stopDiscovery)
    }

    private fun stopDiscovery() {
        activity?.lifecycle?.removeObserver(this)
        activity = null
        if (watchingNfc) {
            watchingNfc = false
            appContext.unregisterReceiver(nfcState)
        }
        stopNfc()
        if (usbListening) {
            usbListening = false
            manager.stopUsbDiscovery()
        }
    }
}
