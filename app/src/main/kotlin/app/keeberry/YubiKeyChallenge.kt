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
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.application.TimeoutException
import com.yubico.yubikit.core.otp.OtpConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.yubiotp.Slot
import com.yubico.yubikit.yubiotp.YubiOtpSession
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class YubiKeyException(val messageId: Int) : IOException()

/** A YubiKey, by its serial number when it shows one, and the slot of it that answered. */
internal data class YubiKeyChoice(val serial: Int?, val slot: Slot)

/**
 * A single challenge for the current unlock or new database. [onPrompt] runs on the main thread,
 * and gets [afterResponse] once the key has answered or the wait has ended. NFC discovery follows
 * the activity passed to [attach], which changes when the screen is recreated.
 *
 * Nobody picks a slot: the key's challenge-response slot answers, and when both slots are one, the
 * slot in [preferred] or else slot 2. With [keepAlternate], the other one's response is kept too,
 * for [alternate]. When several keys are connected by USB, the one in [preferred] is used if it
 * shows up. [used] says which key and slot answered.
 */
internal class YubiKeyChallenge(
    context: Context,
    private val preferred: YubiKeyChoice?,
    private val afterResponse: Int,
    private val keepAlternate: Boolean = false,
    private val onPrompt: (Int) -> Unit,
) : Closeable, DefaultLifecycleObserver {
    private class Alternate(val choice: YubiKeyChoice, val challenge: ByteArray, val response: ByteArray) {
        fun wipe() {
            challenge.fill(0)
            response.fill(0)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val hasUsbHost = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    private val hasNfc = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
    private val manager = YubiKitManager(appContext)
    /** Keys as they connect, or a [YubiKeyException] that ends the wait. */
    private val devices = LinkedBlockingQueue<Any>()
    private val found = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val touchPrompted = AtomicBoolean()
    private var alternate: Alternate? = null

    @Volatile
    var used: YubiKeyChoice? = null
        private set

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
            val key = chooseDevice()
            found.set(true)
            if (closed.get()) throw YubiKeyException(R.string.database_yubikey_failed)
            val response = answer(key, challenge)
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
            stopWaiting()
            main.post { onPrompt(afterResponse) }
        }
    }

    /** Whether [alternate] has a response from the other slot. */
    val hasAlternate: Boolean get() = synchronized(this) { alternate != null }

    /**
     * The other slot's response to [challenge], for a database that didn't unlock with the first
     * one, or null if there is none. Handed out once, and [used] names that slot from then on.
     */
    fun alternate(challenge: ByteArray): ByteArray? = synchronized(this) {
        val other = alternate ?: return null
        alternate = null
        try {
            if (!other.challenge.contentEquals(challenge)) return null
            used = other.choice
            return other.response.copyOf()
        } finally {
            other.wipe()
        }
    }

    /**
     * The first key to connect. If [preferred] names another one and it came by USB, waits a moment
     * for that one too, since several keys can be plugged in at once.
     */
    private fun chooseDevice(): YubiKeyDevice {
        val first = nextDevice(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            ?: throw YubiKeyException(R.string.database_yubikey_timeout)
        val wanted = preferred?.serial ?: return first
        if (first !is UsbYubiKeyDevice || serialOf(first).let { it == null || it == wanted }) return first
        val until = SystemClock.uptimeMillis() + OTHER_KEY_MILLIS
        while (true) {
            val next = nextDevice(until - SystemClock.uptimeMillis()) ?: return first
            // A key held to the phone is the one the user means.
            if (next !is UsbYubiKeyDevice || serialOf(next) == wanted) return next
        }
    }

    /** The next key to connect within [millis], or null if none did. */
    private fun nextDevice(millis: Long): YubiKeyDevice? {
        if (millis <= 0) return null
        val next = try {
            devices.poll(millis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw YubiKeyException(R.string.database_yubikey_failed)
        }
        if (next is YubiKeyException) throw next
        return next as YubiKeyDevice?
    }

    private fun serialOf(key: YubiKeyDevice): Int? = try {
        withSession(key, ::serialOf)
    } catch (_: Exception) {
        null
    }

    // A key can be set to keep its serial number to itself.
    private fun serialOf(session: YubiOtpSession): Int? = try {
        session.serialNumber
    } catch (_: CommandException) {
        null
    }

    private fun <T> withSession(key: YubiKeyDevice, use: (YubiOtpSession) -> T): T = when {
        key.supportsConnection(OtpConnection::class.java) ->
            key.openConnection(OtpConnection::class.java).use { use(YubiOtpSession(it)) }
        key.supportsConnection(SmartCardConnection::class.java) ->
            key.openConnection(SmartCardConnection::class.java).use { use(YubiOtpSession(it)) }
        else -> throw YubiKeyException(R.string.database_yubikey_failed)
    }

    /** Asks each slot that could be a challenge-response one in turn, until one answers. */
    private fun answer(key: YubiKeyDevice, challenge: ByteArray): ByteArray = withSession(key) { session ->
        val serial = serialOf(session)
        val slots = challengeSlots(session)
        for ((index, slot) in slots.withIndex()) {
            val response = try {
                session.calculateHmacSha1(slot, challenge, command)
            } catch (error: TimeoutException) {
                // No touch in time, or cancelled: the slot is the right kind, so the other won't do.
                throw error
            } catch (_: CommandException) {
                // Not set up for HMAC-SHA1 challenge-response after all.
                continue
            }
            used = YubiKeyChoice(serial, slot)
            if (keepAlternate) keepAlternate(session, serial, slots.drop(index + 1), challenge)
            return@withSession response
        }
        throw YubiKeyException(R.string.database_yubikey_no_slot)
    }

    /**
     * The slots to try, the likely challenge-response ones in the order [preferred], slot 2 and
     * slot 1. Only a challenge-response slot is both set up and not set off by a touch. A key too
     * old to say that has both tried, which ends quickly for a slot of another kind.
     */
    private fun challengeSlots(session: YubiOtpSession): List<Slot> {
        val state = session.configurationState
        val order = listOfNotNull(preferred?.slot, Slot.TWO, Slot.ONE).distinct()
        val likely = order.filter { slot ->
            val configured = try {
                state.isConfigured(slot)
            } catch (_: UnsupportedOperationException) {
                true
            }
            val touchTriggered = try {
                state.isTouchTriggered(slot)
            } catch (_: UnsupportedOperationException) {
                false
            }
            configured && !touchTriggered
        }
        return likely.ifEmpty { order }
    }

    /** Keeps the response of the first of [slots] that answers, for [alternate]. */
    private fun keepAlternate(session: YubiOtpSession, serial: Int?, slots: List<Slot>, challenge: ByteArray) {
        for (slot in slots) {
            // This slot may want its own touch.
            touchPrompted.set(false)
            val response = try {
                session.calculateHmacSha1(slot, challenge, command)
            } catch (_: Exception) {
                // The first response still stands.
                if (closed.get()) return
                continue
            }
            synchronized(this) {
                alternate?.wipe()
                alternate = Alternate(YubiKeyChoice(serial, slot), challenge.copyOf(), response)
            }
            return
        }
    }

    private fun startDiscovery() {
        if (closed.get()) return
        discovering = true
        onPrompt(R.string.database_yubikey_waiting)
        if (hasUsbHost) {
            try {
                manager.startUsbDiscovery(UsbConfiguration()) { devices.offer(it) }
                usbListening = true
            } catch (_: SecurityException) {
                manager.stopUsbDiscovery()
            }
        }
        if (!usbListening && !hasNfc) {
            devices.offer(YubiKeyException(R.string.database_yubikey_unavailable))
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
        if (closed.get() || found.get() || nfcActivity != null || !hasNfc) return
        try {
            val configuration = NfcConfiguration().skipNdefCheck(true).timeout(5000)
            manager.startNfcDiscovery(configuration, target) { devices.offer(it) }
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

    /** Ends the wait for a key, but keeps [alternate] for a retry. */
    private fun stopWaiting() {
        if (!closed.compareAndSet(false, true)) return
        command.cancel()
        devices.offer(YubiKeyException(R.string.database_yubikey_failed))
        if (Looper.myLooper() == Looper.getMainLooper()) stopDiscovery() else main.post(::stopDiscovery)
    }

    override fun close() {
        stopWaiting()
        synchronized(this) {
            alternate?.wipe()
            alternate = null
        }
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

    private companion object {
        const val WAIT_SECONDS = 45L
        // Long enough for keys already plugged in to be reported, one after another.
        const val OTHER_KEY_MILLIS = 3000L
    }
}
