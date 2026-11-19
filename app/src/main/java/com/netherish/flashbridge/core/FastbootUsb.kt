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
    private var connection: UsbDeviceConnection? = null
    private var bulkOut: UsbEndpoint? = null
    private var bulkIn: UsbEndpoint? = null

    private val _status = MutableStateFlow<UsbState>(UsbState.Idle)
    val status: StateFlow<UsbState> = _status

    private val _deviceInfo = MutableStateFlow<UsbInfo?>(null)
    val deviceInfo: StateFlow<UsbInfo?> = _deviceInfo

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION != intent.action) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                connect(device)
            } else {
                _status.value = UsbState.Error("USB permission denied")
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
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
        try {
            val conn = usbManager.openDevice(device) ?: throw IOException("openDevice returned null")
            var foundOut: UsbEndpoint? = null
            var foundIn: UsbEndpoint? = null
            // Prefer the standard fastboot interface (0xFF/0x42/0x03) over any
            // other bulk interface the device might expose.
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
                    break
                }
            }
            if (foundOut == null || foundIn == null) {
                conn.close()
                throw IOException("No bulk endpoints found")
            }
            connection = conn
            bulkOut = foundOut
            bulkIn = foundIn
            _status.value = UsbState.Connected(device.toInfo())
        } catch (e: Throwable) {
            _status.value = UsbState.Error(e.message ?: "Connection failed")
        }
    }

    fun isConnected(): Boolean = connection != null

    private fun requireConnection() {
        if (connection == null || bulkOut == null || bulkIn == null) {
            throw IllegalStateException("Not connected — run Detect first")
        }
    }

    /** Send a fastboot text command and read the first reply. */
    fun command(cmd: String, timeout: Int = 5000): FastbootReply {
        requireConnection()
        val bytes = cmd.toByteArray(Charsets.US_ASCII)
        val written = connection!!.bulkTransfer(bulkOut, bytes, bytes.size, timeout)
        if (written != bytes.size) throw IOException("Bulk write failed ($written/${bytes.size})")
        return readReply(timeout)
    }

    /** Read a reply without sending a command (used to drain INFO lines). */
    fun readMore(timeout: Int = 5000): FastbootReply = readReply(timeout)

    private fun readReply(timeout: Int): FastbootReply {
        val buf = ByteArray(256)
        val len = connection!!.bulkTransfer(bulkIn, buf, buf.size, timeout)
        if (len < 4) throw IOException("Short fastboot reply ($len bytes)")
        val status = String(buf, 0, 4, Charsets.US_ASCII)
        val payload = if (len > 4) String(buf, 4, len - 4, Charsets.US_ASCII) else ""
        return FastbootReply(status, payload)
    }

    /** Drain INFO lines and expect OKAY. Returns collected info lines. */
    fun simple(cmd: String): List<String> {
        val info = mutableListOf<String>()
        var reply = command(cmd)
        while (reply.status == "INFO") {
            info += reply.payload.trim()
            reply = readMore()
        }
        when (reply.status) {
            "OKAY" -> return info
            "FAIL" -> throw IOException(reply.payload.trim().ifEmpty { "Command failed" })
            else -> throw IOException("Unexpected reply: ${reply.status} ${reply.payload}")
        }
    }

    /** getvar — returns the variable value after draining INFO lines. */
    fun getvar(name: String): String {
        var reply = command("getvar:$name")
        while (reply.status == "INFO") reply = readMore()
        if (reply.status == "FAIL") throw IOException(reply.payload.trim().ifEmpty { "getvar failed" })
        return reply.payload.trim()
    }

    /** Flash an image: sends flash:<partition>, streams DATA, verifies OKAY. */
    fun flash(partition: String, input: InputStream, onProgress: (Long, Long) -> Unit) {
        var reply = command("flash:$partition")
        while (reply.status == "INFO") reply = readMore()
        if (reply.status == "FAIL") throw IOException(reply.payload.trim().ifEmpty { "flash rejected" })
        if (reply.status != "DATA") throw IOException("Expected DATA, got ${reply.status}")

        val total = reply.payload.trim().toLong(16)
        val chunk = ByteArray(16384)
        var sent = 0L
        while (sent < total) {
            val want = minOf(chunk.size.toLong(), total - sent).toInt()
            val read = input.read(chunk, 0, want)
            if (read <= 0) throw IOException("Unexpected end of image")
            val w = connection!!.bulkTransfer(bulkOut, chunk, read, 10000)
            if (w != read) throw IOException("Bulk data write failed")
            sent += read
            onProgress(sent, total)
        }

        val final = readMore(60000)
        if (final.status == "FAIL") throw IOException(final.payload.trim().ifEmpty { "Flash failed" })
        if (final.status != "OKAY") throw IOException("Unexpected flash result: ${final.status}")
    }

    fun erase(partition: String) {
        val r = command("erase:$partition")
        if (r.status == "FAIL") throw IOException(r.payload.trim().ifEmpty { "Erase failed" })
    }

    fun reboot() {
        val r = command("reboot")
        if (r.status == "FAIL") throw IOException(r.payload.trim())
    }

    fun flashingUnlock() {
        val r = command("flashing unlock")
        if (r.status == "FAIL") throw IOException(r.payload.trim().ifEmpty { "Unlock failed" })
    }

    // ---- Extended fastboot command suite --------------------------------

    /** Run any raw fastboot command (advanced users) and return every INFO line. */
    fun raw(cmd: String): String = simple(cmd).joinToString("\n")

    /** `getvar all` — dump every bootloader variable. */
    fun getvarAll(): String {
        var reply = command("getvar:all")
        val lines = mutableListOf<String>()
        while (reply.status == "INFO") {
            lines += reply.payload.trim()
            reply = readMore()
        }
        if (reply.status == "FAIL") throw IOException(reply.payload.trim().ifEmpty { "getvar all failed" })
        return lines.joinToString("\n")
    }

    /** Boots an image without flashing it (temporary boot). */
    fun boot(input: InputStream, onProgress: (Long, Long) -> Unit) = downloadAndExecute("boot", input, onProgress)

    /** Flash and immediately boot (flash: + reboot). */
    fun flashAndBoot(partition: String, input: InputStream, onProgress: (Long, Long) -> Unit) {
        flash(partition, input, onProgress)
        reboot()
    }

    private fun downloadAndExecute(cmd: String, input: InputStream, onProgress: (Long, Long) -> Unit) {
        var reply = command(cmd)
        while (reply.status == "INFO") reply = readMore()
        if (reply.status == "FAIL") throw IOException(reply.payload.trim().ifEmpty { "$cmd rejected" })
        if (reply.status != "DATA") throw IOException("Expected DATA, got ${reply.status}")
        streamData(reply.payload.trim().toLong(16), input, onProgress)
        val final = readMore(60000)
        if (final.status == "FAIL") throw IOException(final.payload.trim().ifEmpty { "$cmd failed" })
    }

    private fun streamData(total: Long, input: InputStream, onProgress: (Long, Long) -> Unit) {
        val chunk = ByteArray(16384)
        var sent = 0L
        while (sent < total) {
            val want = minOf(chunk.size.toLong(), total - sent).toInt()
            val read = input.read(chunk, 0, want)
            if (read <= 0) throw IOException("Unexpected end of image")
            val w = connection!!.bulkTransfer(bulkOut, chunk, read, 10000)
            if (w != read) throw IOException("Bulk data write failed")
            sent += read
            onProgress(sent, total)
        }
    }

    fun reboot(target: String) = simple("reboot-$target")
    fun rebootBootloader() = simple("reboot-bootloader")
    fun rebootRecovery() = simple("reboot-recovery")
    fun rebootFastboot() = simple("reboot-fastboot")
    fun continueBoot() = simple("continue")
    fun flashingLock() = simple("flashing lock")
    fun flashingLockCritical() = simple("flashing lock_critical")
    fun flashingUnlockCritical() = simple("flashing unlock_critical")
    fun flashingLockVendor() = simple("flashing lock_vendor")
    fun flashingUnlockVendor() = simple("flashing unlock_vendor")
    fun setActive(slot: String) = simple("set_active:$slot")
    fun format(partition: String) = simple("format:$partition")
    fun wipe() = simple("wipe")
    fun oem(command: String): String = simple("oem $command").joinToString("\n")

    /**
     * Full post-flash factory reset. `format:userdata` is the important step:
     * it rebuilds the filesystem and drops encryption metadata, which is what
     * prevents bootloops after flashing a custom ROM over an encrypted device.
     * Metadata/cache are best-effort because not every device exposes them.
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

    fun close() {
        try { connection?.close() } catch (_: Throwable) {}
        connection = null
        bulkIn = null
        bulkOut = null
    }

    fun dispose() {
        close()
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Throwable) {}
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.netherish.flashbridge.USB_PERMISSION"

        fun isFastboot(device: UsbDevice): Boolean {
            // Standard fastboot interface: vendor-specific class 0xFF,
            // subclass 0x42, protocol 0x03 (ADB uses protocol 0x01).
            // This works for virtually every standard fastboot device
            // regardless of vendor ID.
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == 0xFF &&
                    intf.interfaceSubclass == 0x42 &&
                    intf.interfaceProtocol == 0x03) {
                    return true
                }
            }
            // Fallback for devices with non-standard interface descriptors
            val v = device.vendorId
            val p = device.productId
            return (v == 0x18D1 && (p == 0xD00D || p == 0x4E20)) ||
                   (v == 0x0BB4 && p == 0x0C02) ||
                   (v == 0x0451 && p == 0xD00D)
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
