package com.netherish.flashbridge.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream

/**
 * Speaks the fastboot protocol directly over Android's USB Host API.
 *
 * No root and no external binaries required — we claim the device's bulk
 * USB endpoints and exchange fastboot commands the same way a PC would.
 */
class FastbootUsb(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()

    private var connection: UsbDeviceConnection? = null
    private var bulkOut: UsbEndpoint? = null
    private var bulkIn: UsbEndpoint? = null
    private var claimedInterface: UsbInterface? = null
    private var connectedDevice: UsbDevice? = null
    private var connecting = false

    private val _status = MutableStateFlow<UsbState>(UsbState.Idle)
    val status: StateFlow<UsbState> = _status

    private val _deviceInfo = MutableStateFlow<UsbInfo?>(null)
    val deviceInfo: StateFlow<UsbInfo?> = _deviceInfo

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION != intent.action) return
            val device = intent.deviceExtra()
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                connect(device)
            } else {
                _status.value = UsbState.Error("USB permission denied")
            }
        }
    }

    /** A detached device invalidates every cached reference immediately. */
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val detached = intent.deviceExtra() ?: return
            synchronized(lock) {
                val current = connectedDevice
                if (current != null && sameDevice(current, detached)) {
                    closeLocked()
                    _status.value = UsbState.Error("Device disconnected during session")
                }
            }
        }
    }

    init {
        register(permissionReceiver, ACTION_USB_PERMISSION)
        register(detachReceiver, UsbManager.ACTION_USB_DEVICE_DETACHED)
    }

    private fun register(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun hasUsbHost(): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.usb.host")

    /** Scan for a connected fastboot device and request permission if needed. */
    fun scan() {
        if (!hasUsbHost()) {
            _status.value = UsbState.Error("Device has no USB host support")
            return
        }
        synchronized(lock) {
            // Never open a second connection to a device we already own.
            val current = connectedDevice
            if (connection != null && current != null &&
                usbManager.deviceList.values.any { sameDevice(it, current) }
            ) return
        }
        val device = usbManager.deviceList.values.firstOrNull { isFastboot(it) }
        if (device == null) {
            val any = usbManager.deviceList.values.firstOrNull()
            _deviceInfo.value = any?.toInfo()
            _status.value = UsbState.NoDevice(
                if (any != null) "Device connected but not in fastboot mode" else "No USB device connected"
            )
            return
        }
        _deviceInfo.value = device.toInfo()
        if (usbManager.hasPermission(device)) {
            connect(device)
        } else {
            _status.value = UsbState.NeedPermission
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, pi)
        }
    }

    private fun connect(device: UsbDevice) {
        synchronized(lock) {
            if (connecting) return
            connecting = true
            try {
                closeLocked()
                var conn: UsbDeviceConnection? = null
                try {
                    conn = usbManager.openDevice(device) ?: throw IOException("openDevice returned null")
                    var foundOut: UsbEndpoint? = null
                    var foundIn: UsbEndpoint? = null
                    var chosen: UsbInterface? = null

                    val interfaces = (0 until device.interfaceCount)
                        .map { device.getInterface(it) }
                        .sortedByDescending { intf ->
                            if (intf.interfaceClass == 0xFF &&
                                intf.interfaceSubclass == 0x42 &&
                                intf.interfaceProtocol == 0x03) 1 else 0
                        }
                    for (intf in interfaces) {
                        for (e in 0 until intf.endpointCount) {
                            val ep = intf.getEndpoint(e)
                            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                            if (ep.direction == UsbConstants.USB_DIR_OUT && foundOut == null) foundOut = ep
                            if (ep.direction == UsbConstants.USB_DIR_IN && foundIn == null) foundIn = ep
                        }
                        if (foundOut != null && foundIn != null) {
                            if (!conn.claimInterface(intf, true)) throw IOException("Failed to claim USB interface")
                            chosen = intf
                            break
                        }
                    }
                    if (foundOut == null || foundIn == null || chosen == null) {
                        throw IOException("No bulk endpoints found")
                    }

                    connection = conn
                    bulkOut = foundOut
                    bulkIn = foundIn
                    claimedInterface = chosen
                    connectedDevice = device
                    _deviceInfo.value = device.toInfo()
                    _status.value = UsbState.Connected(device.toInfo())
                } catch (e: Throwable) {
                    try { conn?.close() } catch (_: Throwable) {}
                    connection = null
                    bulkOut = null
                    bulkIn = null
                    claimedInterface = null
                    connectedDevice = null
                    _status.value = UsbState.Error(e.message ?: "Connection failed")
                }
            } finally {
                connecting = false
            }
        }
    }

    fun isConnected(): Boolean = synchronized(lock) { connection != null }

    private fun requireConnection() {
        if (connection == null || bulkOut == null || bulkIn == null) {
            throw IOException("Not connected — run Detect first")
        }
    }

    /** Send a fastboot text command and read the first reply. */
    fun command(cmd: String, timeout: Int = 5000): FastbootReply = synchronized(lock) {
        requireConnection()
        if (cmd.length > 256 || cmd.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            throw IllegalArgumentException("Invalid fastboot command")
        }
        val conn = connection ?: throw IOException("Device disconnected")
        val out = bulkOut ?: throw IOException("Device disconnected")
        val bytes = cmd.toByteArray(Charsets.US_ASCII)
        val written = conn.bulkTransfer(out, bytes, bytes.size, timeout)
        if (written != bytes.size) throw IOException("Bulk write failed ($written/${bytes.size})")
        readReplyLocked(timeout)
    }

    /** Read a reply without sending a command (used to drain INFO lines). */
    fun readMore(timeout: Int = 5000): FastbootReply = synchronized(lock) {
        requireConnection()
        readReplyLocked(timeout)
    }

    private fun readReplyLocked(timeout: Int): FastbootReply {
        val conn = connection ?: throw IOException("Device disconnected")
        val inp = bulkIn ?: throw IOException("Device disconnected")
        val buf = ByteArray(256)
        val len = conn.bulkTransfer(inp, buf, buf.size, timeout)
        if (len < 4) throw IOException("Short fastboot reply ($len bytes)")
        val status = String(buf, 0, 4, Charsets.US_ASCII)
        val payload = if (len > 4) String(buf, 4, len - 4, Charsets.US_ASCII) else ""
        return FastbootReply(status, payload)
    }

    /** Drain INFO lines (bounded) and return the collected lines + final reply. */
    private fun drainInfo(first: FastbootReply): Pair<List<String>, FastbootReply> {
        var reply = first
        val info = mutableListOf<String>()
        var count = 0
        while (reply.status == "INFO" && count < MAX_INFO_LINES) {
            info += reply.payload.trim()
            reply = readMore()
            count++
        }
        if (reply.status == "INFO") throw IOException("Too many INFO lines from device")
        return info to reply
    }

    /** Drain INFO lines and expect OKAY. Returns collected info lines. */
    fun simple(cmd: String): List<String> {
        val (info, reply) = drainInfo(command(cmd))
        return when (reply.status) {
            "OKAY" -> info
            "FAIL" -> throw IOException(reply.payload.trim().ifEmpty { "Command failed" })
            else -> throw IOException("Unexpected reply: ${reply.status} ${reply.payload}")
        }
    }

    /** getvar — returns the variable value after draining INFO lines. */
    fun getvar(name: String): String {
        if (name.isBlank() || name.length > 128) throw IllegalArgumentException("Invalid getvar name")
        val (_, reply) = drainInfo(command("getvar:$name"))
        if (reply.status == "FAIL") throw IOException(reply.payload.trim().ifEmpty { "getvar failed" })
        return reply.payload.trim()
    }

    /** Flash an image: sends flash:<partition>, streams DATA, verifies OKAY. */
    fun flash(partition: String, input: InputStream, onProgress: (Long, Long) -> Unit) {
        if (!Validation.isValidPartition(partition)) throw IllegalArgumentException("Invalid partition name")
        val (_, reply) = drainInfo(command("flash:$partition"))
        if (reply.status == "FAIL") throw IOException(reply.payload.trim().ifEmpty { "flash rejected" })
        if (reply.status != "DATA") throw IOException("Expected DATA, got ${reply.status}")

        val total = parseDataLength(reply.payload)
        streamData(total, input, onProgress)
        val final = readMore(60000)
        if (final.status == "FAIL") throw IOException(final.payload.trim().ifEmpty { "Flash failed" })
        if (final.status != "OKAY") throw IOException("Unexpected flash result: ${final.status}")
    }

    /** `getvar all` — dump every bootloader variable. */
    fun getvarAll(): String {
        val (lines, reply) = drainInfo(command("getvar:all"))
        if (reply.status == "FAIL") throw IOException(reply.payload.trim().ifEmpty { "getvar all failed" })
        return lines.joinToString("\n")
    }

    /** Boots an image without flashing it (temporary boot). */
    fun boot(input: InputStream, onProgress: (Long, Long) -> Unit) = downloadAndExecute("boot", input, onProgress)

    private fun downloadAndExecute(cmd: String, input: InputStream, onProgress: (Long, Long) -> Unit) {
        val (_, reply) = drainInfo(command(cmd))
        if (reply.status == "FAIL") throw IOException(reply.payload.trim().ifEmpty { "$cmd rejected" })
        if (reply.status != "DATA") throw IOException("Expected DATA, got ${reply.status}")
        streamData(parseDataLength(reply.payload), input, onProgress)
        val final = readMore(60000)
        if (final.status == "FAIL") throw IOException(final.payload.trim().ifEmpty { "$cmd failed" })
        if (final.status != "OKAY") throw IOException("Unexpected $cmd result: ${final.status}")
    }

    private fun streamData(total: Long, input: InputStream, onProgress: (Long, Long) -> Unit) {
        val chunk = ByteArray(16384)
        var sent = 0L
        while (sent < total) {
            val want = minOf(chunk.size.toLong(), total - sent).toInt()
            val read = input.read(chunk, 0, want)
            if (read <= 0) throw IOException("Unexpected end of image")
            synchronized(lock) {
                val conn = connection ?: throw IOException("Device disconnected during transfer")
                val out = bulkOut ?: throw IOException("Device disconnected during transfer")
                val w = conn.bulkTransfer(out, chunk, read, 10000)
                if (w != read) throw IOException("Bulk data write failed")
            }
            sent += read
            onProgress(sent, total)
        }
    }

    private fun parseDataLength(payload: String): Long {
        val clean = payload.trim()
        if (clean.isEmpty() || clean.length > 16) throw IOException("Malformed DATA length")
        val value = clean.toLongOrNull(16) ?: throw IOException("Malformed DATA length: $clean")
        if (value <= 0 || value > Validation.MAX_FILE_SIZE) throw IOException("DATA length out of range: $value")
        return value
    }

    // ---- Simple command wrappers -----------------------------------------

    fun erase(partition: String) {
        if (!Validation.isValidPartition(partition)) throw IllegalArgumentException("Invalid partition name")
        simple("erase:$partition")
    }

    fun reboot() = simple("reboot")
    fun flashingUnlock() = simple("flashing unlock")
    fun reboot(target: String): String = simple("reboot-$target").joinToString("\n")
    fun rebootBootloader(): String = simple("reboot-bootloader").joinToString("\n")
    fun rebootRecovery(): String = simple("reboot-recovery").joinToString("\n")
    fun rebootFastboot(): String = simple("reboot-fastboot").joinToString("\n")
    fun continueBoot(): String = simple("continue").joinToString("\n")
    fun flashingLock(): String = simple("flashing lock").joinToString("\n")
    fun flashingLockCritical(): String = simple("flashing lock_critical").joinToString("\n")
    fun flashingUnlockCritical(): String = simple("flashing unlock_critical").joinToString("\n")
    fun flashingLockVendor(): String = simple("flashing lock_vendor").joinToString("\n")
    fun flashingUnlockVendor(): String = simple("flashing unlock_vendor").joinToString("\n")
    fun setActive(slot: String) {
        if (!Validation.isValidSlot(slot)) throw IllegalArgumentException("Invalid slot")
        simple("set_active:$slot")
    }
    fun format(partition: String) {
        if (!Validation.isValidPartition(partition)) throw IllegalArgumentException("Invalid partition name")
        simple("format:$partition")
    }
    fun wipe() = simple("wipe")
    fun raw(cmd: String): String = simple(cmd).joinToString("\n")
    fun oem(command: String): String {
        if (command.isBlank()) throw IllegalArgumentException("Empty OEM command")
        return simple("oem $command").joinToString("\n")
    }

    /**
     * Full post-flash factory reset. `format:userdata` is the important step:
     * it rebuilds the filesystem and drops encryption metadata, which is what
     * prevents bootloops after flashing a custom ROM over an encrypted device.
     */
    fun factoryReset(): List<String> {
        val results = mutableListOf<String>()
        results += "userdata: " + simple("format:userdata").joinToString(" ")
        try { results += "metadata: " + simple("format:metadata").joinToString(" ") }
        catch (e: Throwable) { results += "metadata: skipped (${e.message ?: "unsupported"})" }
        try { results += "cache: " + simple("erase:cache").joinToString(" ") }
        catch (e: Throwable) { results += "cache: skipped (${e.message ?: "unsupported"})" }
        return results
    }

    fun flashingGetUnlockAbility(): String = getvar("unlock_ability")
    fun deviceState(): String = getvar("state")
    fun isUnlocked(): Boolean = try {
        getvar("unlocked") == "yes" || getvar("device-state") == "unlocked"
    } catch (_: Throwable) { false }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        try { claimedInterface?.let { connection?.releaseInterface(it) } } catch (_: Throwable) {}
        try { connection?.close() } catch (_: Throwable) {}
        connection = null
        bulkIn = null
        bulkOut = null
        claimedInterface = null
        connectedDevice = null
    }

    fun dispose() {
        close()
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Throwable) {}
        try { context.unregisterReceiver(detachReceiver) } catch (_: Throwable) {}
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.netherish.flashbridge.USB_PERMISSION"
        private const val MAX_INFO_LINES = 256

        fun isFastboot(device: UsbDevice): Boolean {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == 0xFF &&
                    intf.interfaceSubclass == 0x42 &&
                    intf.interfaceProtocol == 0x03) return true
            }
            val v = device.vendorId
            val p = device.productId
            return (v == 0x18D1 && (p == 0xD00D || p == 0x4E20)) ||
                   (v == 0x0BB4 && p == 0x0C02) ||
                   (v == 0x0451 && p == 0xD00D)
        }

        internal fun sameDevice(a: UsbDevice, b: UsbDevice): Boolean =
            a.vendorId == b.vendorId && a.productId == b.productId && a.deviceName == b.deviceName

        internal fun Intent.deviceExtra(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
    }
}

data class FastbootReply(val status: String, val payload: String)

sealed class UsbState {
    object Idle : UsbState()
    object NeedPermission : UsbState()
    data class NoDevice(val message: String) : UsbState()
    data class Connected(val info: UsbInfo) : UsbState()
    data class Error(val message: String) : UsbState()
}

fun UsbDevice.toInfo() = UsbInfo(
    vendorId = vendorId,
    productId = productId,
    name = deviceName,
    manufacturer = manufacturerName,
)
