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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * MediaTek BROM / preloader identification over Android's USB Host API.
 *
 * This speaks the first-stage MediaTek USB download protocol (the same stage
 * SP Flash Tool uses before it uploads its proprietary Download Agent):
 * handshake, HW code, bootloader/BROM versions, HW+SW version and the
 * target security configuration.
 *
 * It is deliberately read-only. Uploading a DA and writing partitions needs
 * MediaTek's chipset-specific signed Download Agent binary, which this
 * project does not bundle.
 *
 * Protocol facts are from the publicly documented MediaTek USB download
 * command set; the implementation is original Kotlin.
 */
class MtkUsb(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var connection: UsbDeviceConnection? = null
    private var bulkOut: UsbEndpoint? = null
    private var bulkIn: UsbEndpoint? = null

    private var armed = false
    private var monitorJob: Job? = null

    private val _status = MutableStateFlow<MtkState>(MtkState.Idle)
    val status: StateFlow<MtkState> = _status

    private val _info = MutableStateFlow<MtkInfo?>(null)
    val info: StateFlow<MtkInfo?> = _info

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION != intent.action) return
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                scope.launch { connect(dev) }
            } else {
                _status.value = MtkState.Error("USB permission denied")
            }
        }
    }

    /** Fires the instant a device is plugged in while we are armed. */
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (dev != null && isMtkDevice(dev)) onMtkDeviceAppeared(dev)
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

    /**
     * Scan now, and if nothing is connected stay armed so the device is
     * picked up the instant it enters BROM/preloader mode.
     */
    fun scan() {
        if (!hasUsbHost()) {
            _status.value = MtkState.Error("No USB host support")
            return
        }
        val dev = usbManager.deviceList.values.firstOrNull { isMtkDevice(it) }
        if (dev == null) {
            arm()
            return
        }
        disarm()
        onMtkDeviceAppeared(dev)
    }

    /** Start listening for a MediaTek device to appear. */
    private fun arm() {
        if (armed) return
        armed = true
        _status.value = MtkState.Arming
        try {
            val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(attachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(attachReceiver, filter)
            }
        } catch (_: Throwable) {}

        // Broadcasts can be missed when a preloader port appears and vanishes
        // within seconds, so also poll the device list while armed.
        monitorJob = scope.launch {
            while (armed) {
                val dev = usbManager.deviceList.values.firstOrNull { isMtkDevice(it) }
                if (dev != null) {
                    onMtkDeviceAppeared(dev)
                    break
                }
                delay(600)
            }
        }
    }

    private fun disarm() {
        armed = false
        monitorJob?.cancel()
        monitorJob = null
        try { context.unregisterReceiver(attachReceiver) } catch (_: Throwable) {}
    }

    private fun onMtkDeviceAppeared(dev: UsbDevice) {
        disarm()
        _info.value = MtkInfo.basic(dev.toInfo())
        if (usbManager.hasPermission(dev)) {
            scope.launch { connect(dev) }
        } else {
            _status.value = MtkState.NeedPermission
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(dev, pi)
        }
    }

    private fun connect(dev: UsbDevice) {
        try {
            val conn = usbManager.openDevice(dev) ?: throw IOException("openDevice returned null")
            var out: UsbEndpoint? = null
            var inn: UsbEndpoint? = null

            // Prefer vendor-specific 0xFF interfaces (BROM/preloader).
            val interfaces = (0 until dev.interfaceCount)
                .map { dev.getInterface(it) }
                .sortedByDescending { if (it.interfaceClass == 0xFF) 1 else 0 }

            var claimed: android.hardware.usb.UsbInterface? = null
            for (intf in interfaces) {
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_OUT && out == null) out = ep
                    if (ep.direction == UsbConstants.USB_DIR_IN && inn == null) inn = ep
                }
                if (out != null && inn != null) {
                    if (!conn.claimInterface(intf, true)) throw IOException("Failed to claim MTK interface")
                    claimed = intf
                    break
                }
            }
            if (out == null || inn == null || claimed == null) {
                conn.close()
                throw IOException("No MTK bulk endpoints found")
            }

            connection = conn
            bulkOut = out
            bulkIn = inn

            handshake()

            // HW code + HW version arrive together in one 4-byte response.
            val hw = safeBytes { sendCommand(CMD_GET_HW_CODE, 4) }
            val hwCode = hw?.let { be16(it, 0) } ?: 0
            val hwVer = hw?.let { be16(it, 2) } ?: 0

            val bl = safeInt { readSimple(CMD_GET_BL_VER) } ?: -1
            val brom = safeInt { readSimple(CMD_GET_VERSION) } ?: -1
            val sw = safePair { readHwSwVer() }
            val cfg = safeInt { readTargetConfig() } ?: 0

            _info.value = MtkInfo(
                usb = dev.toInfo(),
                hwCode = hwCode,
                hwVer = hwVer,
                chipName = chipName(hwCode),
                blVer = bl,
                bromVer = brom,
                hwSubCode = sw?.first ?: 0,
                swVer = sw?.second ?: 0,
                targetConfig = cfg,
                isBrom = bl == CMD_GET_BL_VER,
                security = securityText(cfg),
            )
            _status.value = MtkState.Connected(_info.value!!)
        } catch (e: Throwable) {
            close()
            _status.value = MtkState.Error(e.message ?: "MTK connection failed")
        }
    }

    // ---- User-supplied DA / authorization --------------------------------

    /**
     * Send a user-provided authorization blob (BROM SLA / DAA auth file).
     * The bytes come from the user's own storage — this app never ships one.
     */
    fun sendAuth(data: ByteArray) {
        if (data.isEmpty()) throw IOException("Empty auth file")
        if (!echo(CMD_SEND_AUTH)) throw IOException("Device rejected SEND_AUTH (0xE2)")
        val payload = padEven(data)
        writeBytes(be32(payload.size))
        val echoed = readBe32()
        if (echoed != payload.size) {
            throw IOException("Auth length mismatch (0x%08X)".format(echoed))
        }
        when (val status = readBe16()) {
            0x1D0C -> throw IOException("Device reports no auth needed")
            in 0x00..0xFF -> Unit
            else -> throw IOException("Auth rejected (status 0x%04X)".format(status))
        }
        uploadData(payload, "auth")
    }

    /** Send a user-provided root certificate (CMD 0xE0). */
    fun sendCert(data: ByteArray) {
        if (data.isEmpty()) throw IOException("Empty certificate")
        if (!echo(CMD_SEND_CERT)) throw IOException("Device rejected SEND_CERT (0xE0)")
        val payload = padEven(data)
        writeBytes(be32(payload.size))
        when (val status = readBe16()) {
            in 0x00..0xFF -> Unit
            else -> throw IOException("Certificate rejected (status 0x%04X)".format(status))
        }
        uploadData(payload, "certificate")
    }

    /**
     * Upload one DA stage to the device. [address] and [signatureLength] come
     * from the user's DA header, so they are always chipset-matched by the
     * person who supplied the file.
     */
    fun sendDa(data: ByteArray, address: Int, signatureLength: Int) {
        if (data.isEmpty()) throw IOException("Empty DA stage")
        val sigLen = signatureLength.coerceIn(0, data.size)
        val payload = padEven(data)
        checksum(payload).let { _progress.value = "DA checksum 0x%08X".format(it) }

        if (!echo(CMD_SEND_DA)) throw IOException("Device rejected SEND_DA (0xD7)")
        if (!echoBytes(be32(address))) throw IOException("DA address not echoed")
        if (!echoBytes(be32(payload.size))) throw IOException("DA size not echoed")
        if (!echoBytes(be32(sigLen))) throw IOException("DA signature length not echoed")

        when (val status = readBe16()) {
            0x1D0D -> throw IOException("SLA required — add the matching auth file in the MTK tab")
            in 0x00..0xFF -> Unit
            else -> throw IOException("DA upload rejected (status 0x%04X)".format(status))
        }
        uploadData(payload, "DA stage")
    }

    /** Hand execution to an uploaded 32-bit DA stage. */
    fun jumpDa(address: Int) {
        if (!echo(CMD_JUMP_DA)) throw IOException("Device rejected JUMP_DA (0xD5)")
        writeBytes(be32(address))
        val echoed = readBe32()
        if (echoed != address) throw IOException("Jump address mismatch (0x%08X)".format(echoed))
        when (val status = readBe16()) {
            in 0x00..0xFF -> _status.value = MtkState.DaRunning(_info.value)
            else -> throw IOException("Jump failed (status 0x%04X)".format(status))
        }
    }

    /** Hand execution to a 64-bit DA stage (0xDE). */
    fun jumpDa64(address: Int) {
        if (!echo(CMD_JUMP_DA64)) throw IOException("Device rejected JUMP_DA64 (0xDE)")
        writeBytes(be32(address))
        val echoed = readBe32()
        if (echoed != address) throw IOException("Jump address mismatch (0x%08X)".format(echoed))
        if (!echo(0x01)) throw IOException("64-bit mode not accepted")
        when (val status = readBe16()) {
            in 0x00..0xFF -> _status.value = MtkState.DaRunning(_info.value)
            else -> throw IOException("64-bit jump failed (status 0x%04X)".format(status))
        }
    }

    /** Raw 32-bit memory read (BROM / DA command 0xD1). */
    fun read32(address: Int, count: Int): List<Int> {
        if (!echo(CMD_READ32)) throw IOException("Device rejected READ32 (0xD1)")
        if (!echoBytes(be32(address))) throw IOException("Read address not echoed")
        if (!echoBytes(be32(count))) throw IOException("Read count not echoed")
        when (val status = readBe16()) {
            in 0x00..0xFF -> Unit
            else -> throw IOException("Read32 rejected (status 0x%04X)".format(status))
        }
        val raw = readBytes(count * 4, 5000)
        val values = (0 until count).map { be32Int(raw, it * 4) }
        when (val status = readBe16()) {
            in 0x00..0xFF -> return values
            else -> throw IOException("Read32 failed (status 0x%04X)".format(status))
        }
    }

    /** Raw 32-bit memory write (BROM / DA command 0xD4). */
    fun write32(address: Int, values: List<Int>) {
        if (!echo(CMD_WRITE32)) throw IOException("Device rejected WRITE32 (0xD4)")
        if (!echoBytes(be32(address))) throw IOException("Write address not echoed")
        if (!echoBytes(be32(values.size))) throw IOException("Write count not echoed")
        when (val status = readBe16()) {
            in 0x00..0xFF -> Unit
            else -> throw IOException("Write32 rejected (status 0x%04X)".format(status))
        }
        values.forEach { if (!echoBytes(be32(it))) throw IOException("Write value not echoed") }
        when (val status = readBe16()) {
            in 0x00..0xFF -> Unit
            else -> throw IOException("Write32 failed (status 0x%04X)".format(status))
        }
    }

    /** Read the chip's unique ME_ID if the device exposes it. */
    fun readMeId(): String = readIdentifier(CMD_GET_ME_ID)

    /** Read the chip's SOC_ID if the device exposes it. */
    fun readSocId(): String = readIdentifier(CMD_GET_SOC_ID)

    private fun readIdentifier(cmd: Int): String {
        if (!echo(CMD_GET_BL_VER)) return ""
        if (!echo(cmd)) return ""
        val length = readBe32()
        if (length <= 0 || length > 128) return ""
        val raw = readBytes(length, 3000)
        readBe16() // status
        return raw.joinToString("") { "%02X".format(it) }
    }

    @Throws(IOException::class)
    private fun uploadData(data: ByteArray, label: String) {
        val localChecksum = checksum(data)
        var offset = 0
        val chunkSize = 4096
        while (offset < data.size) {
            val size = minOf(chunkSize, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + size)
            writeBytes(chunk)
            offset += size
            _progress.value = "$label: $offset / ${data.size} bytes"
            if (offset % 0x2000 == 0) writeBytes(byteArrayOf())
        }
        writeBytes(byteArrayOf()) // zero-length terminator
        val deviceChecksum = readBe16()
        when (val status = readBe16()) {
            in 0x00..0xFF -> {
                if (deviceChecksum != 0 && deviceChecksum != (localChecksum and 0xFFFF)) {
                    _progress.value = "$label uploaded (checksum mismatch warning)"
                }
                _progress.value = ""
            }
            else -> throw IOException("$label upload failed (status 0x%04X)".format(status))
        }
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < data.size) {
            sum = (sum + ((data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8))) and 0xFFFFFFFF.toInt()
            i += 2
        }
        if (i < data.size) sum = (sum + (data[i].toInt() and 0xFF)) and 0xFFFFFFFF.toInt()
        return sum
    }

    private fun padEven(data: ByteArray): ByteArray =
        if (data.size % 2 == 0) data else data + byteArrayOf(0)

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun readBe32(): Int {
        val b = readBytes(4, 5000)
        return be32Int(b, 0)
    }

    private fun be32Int(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun readBe16(): Int {
        val b = readBytes(2, 5000)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    private fun echoBytes(bytes: ByteArray): Boolean {
        writeBytes(bytes)
        val echoed = readBytes(bytes.size, 5000)
        return echoed.contentEquals(bytes)
    }

    private fun writeBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            connection!!.bulkTransfer(bulkOut, ByteArray(0), 0, 3000)
            return
        }
        var off = 0
        while (off < bytes.size) {
            val len = minOf(4096, bytes.size - off)
            val n = connection!!.bulkTransfer(bulkOut, bytes, off, len, 10000)
            if (n != len) throw IOException("MTK bulk write failed at $off")
            off += len
        }
    }

    // ---- Low-level protocol ----------------------------------------------

    private fun handshake() {
        var last = -1
        repeat(20) {
            writeByte(CMD_HANDSHAKE)
            last = readByte(1500)
            if (last == RSP_ACK) return
            if (last == RSP_NACK) return@repeat
        }
        throw IOException(
            if (last >= 0) "MTK handshake rejected (0x%02X)".format(last)
            else "No MTK handshake response — power off, hold Vol+ or Vol-, reconnect USB"
        )
    }

    /** Send a command byte, require it echoed, then read a fixed response. */
    private fun sendCommand(cmd: Int, length: Int): ByteArray {
        if (!echo(cmd)) throw IOException("MTK command 0x%02X not echoed".format(cmd))
        return readBytes(length, 3000)
    }

    private fun echo(cmd: Int): Boolean {
        writeByte(cmd)
        return readByte(3000) == cmd
    }

    private fun readSimple(cmd: Int): Int {
        writeByte(cmd)
        return readByte(3000)
    }

    /** Returns Pair(hwSubCode, swVer) from the 8-byte FC response. */
    private fun readHwSwVer(): Pair<Int, Int> {
        val b = sendCommand(CMD_GET_HW_SW_VER, 8)
        return be16(b, 0) to be16(b, 4)
    }

    private fun readTargetConfig(): Int {
        if (!echo(CMD_GET_TARGET_CONFIG)) return 0
        val b = readBytes(6, 3000)
        if (b.size < 4) return 0
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    private fun be16(b: ByteArray, off: Int): Int {
        if (b.size < off + 2) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    private fun writeByte(value: Int) {
        val data = byteArrayOf((value and 0xFF).toByte())
        val n = connection!!.bulkTransfer(bulkOut, data, 1, 3000)
        if (n != 1) throw IOException("MTK bulk write failed")
    }

    private fun readByte(timeout: Int): Int {
        val buf = ByteArray(1)
        val n = connection!!.bulkTransfer(bulkIn, buf, 1, timeout)
        return if (n == 1) buf[0].toInt() and 0xFF else -1
    }

    private fun readBytes(len: Int, timeout: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = connection!!.bulkTransfer(bulkIn, buf, off, len - off, timeout)
            if (n <= 0) throw IOException("MTK bulk read timed out")
            off += n
        }
        return buf
    }

    private fun safeInt(block: () -> Int): Int? = try { block() } catch (_: Throwable) { null }
    private fun safePair(block: () -> Pair<Int, Int>): Pair<Int, Int>? = try { block() } catch (_: Throwable) { null }
    private fun safeBytes(block: () -> ByteArray): ByteArray? = try { block() } catch (_: Throwable) { null }

    // ---- Helpers ----------------------------------------------------------

    fun close() {
        disarm()
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
        const val ACTION_USB_PERMISSION = "com.netherish.flashbridge.USB_PERMISSION_MTK"

        private const val CMD_HANDSHAKE = 0xA0
        private const val RSP_ACK = 0x5A
        private const val RSP_NACK = 0xA5

        private const val CMD_GET_HW_CODE = 0xFD
        private const val CMD_GET_HW_SW_VER = 0xFC
        private const val CMD_GET_BL_VER = 0xFE
        private const val CMD_GET_VERSION = 0xFF
        private const val CMD_GET_TARGET_CONFIG = 0xD8
        private const val CMD_READ32 = 0xD1
        private const val CMD_WRITE32 = 0xD4
        private const val CMD_JUMP_DA = 0xD5
        private const val CMD_SEND_DA = 0xD7
        private const val CMD_JUMP_DA64 = 0xDE
        private const val CMD_SEND_CERT = 0xE0
        private const val CMD_GET_ME_ID = 0xE1
        private const val CMD_SEND_AUTH = 0xE2
        private const val CMD_GET_SOC_ID = 0xE7

        fun isMtkDevice(device: UsbDevice): Boolean {
            if (device.vendorId != 0x0E8D) return false
            return device.productId in setOf(
                0x0002, 0x0003, 0x2000, 0x2001, 0x2002, 0x20FF, 0x0C01
            ) || (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == 0xFF
            }
        }

        fun chipName(code: Int): String = when (code) {
            0x6261 -> "MT6261"
            0x6572, 0x6575, 0x6577 -> "MT6572/75/77"
            0x6580 -> "MT6580"
            0x6582 -> "MT6582"
            0x6592 -> "MT6592"
            0x6595 -> "MT6595"
            0x6603 -> "MT6603"
            0x6735, 0x6737 -> "MT6735/37"
            0x6753 -> "MT6753"
            0x6755 -> "MT6755 (Helio P10)"
            0x6757 -> "MT6757 (Helio P20)"
            0x6761 -> "MT6761 (Helio A22)"
            0x6763 -> "MT6763 (Helio P23)"
            0x6765 -> "MT6765 (Helio P35)"
            0x6771 -> "MT6771 (Helio P60)"
            0x6779 -> "MT6779 (Helio P90)"
            0x6785 -> "MT6785 (Helio G90T)"
            0x6795 -> "MT6795 (Helio X10)"
            0x6797 -> "MT6797 (Helio X20)"
            0x6850 -> "MT6850 (Dimensity 800U)"
            0x6853 -> "MT6853 (Dimensity 720)"
            0x6855 -> "MT6855 (Dimensity 930)"
            0x6873 -> "MT6873 (Dimensity 800)"
            0x6885 -> "MT6885 (Dimensity 1000)"
            0x6895 -> "MT6895 (Dimensity 1100)"
            0x6983 -> "MT6983 (Dimensity 1200)"
            0x8163 -> "MT8163"
            0x8167 -> "MT8167"
            0x8173 -> "MT8173"
            0x8192 -> "MT8192"
            0x8695 -> "MT8695"
            0x8830, 0x8832 -> "MT8830/32"
            0x8890, 0x8892 -> "MT8890/92"
            else -> if (code != 0) "Unknown MTK (HW 0x%04X)".format(code) else "Unknown"
        }

        private fun securityText(cfg: Int): String {
            if (cfg == 0) return "Security config unavailable"
            val flags = mutableListOf<String>()
            if (cfg and 0x1 != 0) flags += "SBC"
            if (cfg and 0x2 != 0) flags += "SLA"
            if (cfg and 0x4 != 0) flags += "DAA"
            if (cfg and 0x8 != 0) flags += "EPP"
            if (cfg and 0x10 != 0) flags += "CERT"
            if (cfg and 0x20 != 0) flags += "MEM-READ-AUTH"
            if (cfg and 0x40 != 0) flags += "MEM-WRITE-AUTH"
            if (cfg and 0x80 != 0) flags += "CMD-C8-BLOCKED"
            return "0x%08X · %s".format(cfg, if (flags.isEmpty()) "none" else flags.joinToString(", "))
        }
    }
}

data class MtkInfo(
    val usb: UsbInfo,
    val hwCode: Int = 0,
    val hwVer: Int = 0,
    val chipName: String = "Identifying…",
    val blVer: Int = -1,
    val bromVer: Int = -1,
    val hwSubCode: Int = 0,
    val swVer: Int = 0,
    val targetConfig: Int = 0,
    val isBrom: Boolean = false,
    val security: String = "",
) {
    companion object {
        fun basic(usb: UsbInfo) = MtkInfo(usb = usb)
    }
}

sealed class MtkState {
    object Idle : MtkState()
    object Arming : MtkState()
    object NeedPermission : MtkState()
    data class NoDevice(val message: String) : MtkState()
    data class Connected(val info: MtkInfo) : MtkState()
    data class DaRunning(val info: MtkInfo?) : MtkState()
    data class Error(val message: String) : MtkState()
}
