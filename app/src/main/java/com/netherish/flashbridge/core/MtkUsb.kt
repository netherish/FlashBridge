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
import kotlinx.coroutines.SupervisorJob
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

    private val _status = MutableStateFlow<MtkState>(MtkState.Idle)
    val status: StateFlow<MtkState> = _status

    private val _info = MutableStateFlow<MtkInfo?>(null)
    val info: StateFlow<MtkInfo?> = _info

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

    /** Scan for a MediaTek BROM/preloader device. */
    fun scan() {
        if (!hasUsbHost()) {
            _status.value = MtkState.Error("No USB host support")
            return
        }
        val dev = usbManager.deviceList.values.firstOrNull { isMtkDevice(it) }
        if (dev == null) {
            _status.value = MtkState.NoDevice("No MediaTek BROM/preloader device found")
            return
        }
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
    object NeedPermission : MtkState()
    data class NoDevice(val message: String) : MtkState()
    data class Connected(val info: MtkInfo) : MtkState()
    data class Error(val message: String) : MtkState()
}
