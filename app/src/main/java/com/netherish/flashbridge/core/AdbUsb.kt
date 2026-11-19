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
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import android.util.Base64
import java.util.zip.CRC32
import javax.crypto.Cipher

/**
 * Pure-Kotlin ADB client over Android's USB Host API.
 *
 * Implements the ADB packet protocol (CNXN handshake, RSA auth, OPEN/WRTE
 * streams) so an unrooted host phone can detect devices in ADB mode and
 * reboot them into fastboot — no external binaries or root required.
 *
 * Protocol reference: AOSP packages/modules/adb/protocol.txt
 * Public key format: AOSP libcrypto_utils/android_pubkey.c (RSAPublicKey)
 */
class AdbUsb(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var bulkOut: UsbEndpoint? = null
    private var bulkIn: UsbEndpoint? = null
    private var connected = false
    private var remoteId = 0
    private var maxData = 4096

    private val _status = MutableStateFlow<AdbState>(AdbState.Idle)
    val status: StateFlow<AdbState> = _status

    private val _deviceInfo = MutableStateFlow<UsbInfo?>(null)
    val deviceInfo: StateFlow<UsbInfo?> = _deviceInfo

    private var device: UsbDevice? = null

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
                connectAndHandshake(dev)
            } else {
                _status.value = AdbState.Error("USB permission denied")
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

    fun hasUsbHost(): Boolean = context.packageManager.hasSystemFeature("android.hardware.usb.host")

    /** Scan for an ADB device and request permission if needed. */
    fun scan() {
        if (!hasUsbHost()) {
            _status.value = AdbState.Error("No USB host support")
            return
        }
        val dev = usbManager.deviceList.values.firstOrNull { isAdbDevice(it) }
        if (dev == null) {
            _status.value = AdbState.NoDevice("No ADB device found")
            return
        }
        device = dev
        _deviceInfo.value = dev.toInfo()
        if (usbManager.hasPermission(dev)) {
            connectAndHandshake(dev)
        } else {
            _status.value = AdbState.NeedPermission
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(dev, pi)
        }
    }

    private fun connectAndHandshake(dev: UsbDevice) {
        try {
            val conn = usbManager.openDevice(dev) ?: throw IOException("openDevice returned null")
            var out: UsbEndpoint? = null
            var inn: UsbEndpoint? = null
            var adbInterface: android.hardware.usb.UsbInterface? = null

            for (i in 0 until dev.interfaceCount) {
                val intf = dev.getInterface(i)
                if (intf.interfaceClass != 0xFF || intf.interfaceSubclass != 0x42 || intf.interfaceProtocol != 0x01) continue
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_OUT && out == null) out = ep
                    if (ep.direction == UsbConstants.USB_DIR_IN && inn == null) inn = ep
                }
                if (out != null && inn != null) {
                    adbInterface = intf
                    break
                }
            }
            if (out == null || inn == null || adbInterface == null) {
                conn.close()
                throw IOException("No ADB bulk endpoints found")
            }
            if (!conn.claimInterface(adbInterface, true)) {
                conn.close()
                throw IOException("Failed to claim ADB interface")
            }
            connection = conn
            bulkOut = out
            bulkIn = inn
            connected = true

            handshake()
            _status.value = AdbState.Connected(dev.toInfo())
        } catch (e: Throwable) {
            connected = false
            _status.value = AdbState.Error(e.message ?: "ADB connection failed")
        }
    }

    fun isConnected(): Boolean = connected

    // ------------------------------------------------------------------
    // ADB handshake
    // ------------------------------------------------------------------

    private fun handshake() {
        // CNXN: version, maxdata=4096, "host::\0"
        val identity = "host::\u0000".toByteArray(Charsets.US_ASCII)
        sendPacket(AdbPacket(A_CNXN, ADB_VERSION, ADB_MAXDATA, identity))

        var reply = readPacket(8000)
        var attempt = 0
        while (reply.command == A_AUTH && reply.arg0 == AUTH_TOKEN && attempt < 3) {
            attempt++
            if (attempt == 1) {
                // Try signing with our private key
                val sig = signToken(reply.data)
                if (sig != null) {
                    sendPacket(AdbPacket(A_AUTH, AUTH_SIGNATURE, 0, sig))
                } else {
                    sendPublicKey()
                }
            } else {
                // Device rejected signature — send public key to trigger
                // the "Allow USB debugging?" prompt on the target.
                sendPublicKey()
            }
            reply = readPacket(if (attempt >= 2) 20000 else 8000)
        }
        if (reply.command != A_CNXN) {
            throw IOException("ADB auth failed: ${cmdName(reply.command)}")
        }
        // Device tells us its max payload in arg1 of its CNXN
        maxData = reply.arg1.takeIf { it in 4096..(1024 * 1024) } ?: 4096
    }

    private fun sendPublicKey() {
        val pub = publicKeyBlob()
        val withNull = ByteArray(pub.size + 1)
        System.arraycopy(pub, 0, withNull, 0, pub.size)
        withNull[pub.size] = 0
        sendPacket(AdbPacket(A_AUTH, AUTH_RSAPUBLICKEY, 0, withNull))
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Read a device property via `shell:getprop name`. */
    fun getprop(name: String): String = shell("getprop $name").trim()

    /** Run a shell command and return combined output. */
    fun shell(command: String, timeout: Int = 10000): String {
        requireConnected()
        val localId = 1
        val dest = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(AdbPacket(A_OPEN, localId, 0, dest))

        var reply = readPacket(timeout)
        if (reply.command == A_FAIL) throw IOException(String(reply.data, Charsets.UTF_8).trim())
        if (reply.command != A_OKAY) throw IOException("OPEN failed: ${cmdName(reply.command)}")
        remoteId = reply.arg0

        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val pkt = readPacket(timeout)
            when (pkt.command) {
                A_WRTE -> {
                    out.append(String(pkt.data, Charsets.UTF_8))
                    // Acknowledge
                    sendPacket(AdbPacket(A_OKAY, localId, remoteId, ByteArray(0)))
                }
                A_CLSE -> {
                    sendPacket(AdbPacket(A_CLSE, localId, remoteId, ByteArray(0)))
                    break
                }
                A_OKAY -> { /* flow control ack */ }
                else -> { /* ignore */ }
            }
        }
        return out.toString()
    }

    /** Reboot the device into the given target (e.g. "bootloader", "recovery", "download"). */
    fun reboot(target: String = "bootloader") {
        requireConnected()
        val localId = 2
        val dest = "reboot:$target\u0000".toByteArray(Charsets.US_ASCII)
        sendPacket(AdbPacket(A_OPEN, localId, 0, dest))
        // Device reboots immediately; don't block waiting for a reply.
    }

    /**
     * ADB sideload — stream a ZIP (ROM / GApps / OTA) to a device in
     * recovery mode. This is how most custom ROMs are actually installed.
     */
    fun sideload(input: InputStream, size: Long, onProgress: (Long, Long) -> Unit) {
        requireConnected()
        val localId = 5
        val dest = "sideload:\u0000".toByteArray(Charsets.US_ASCII)
        sendPacket(AdbPacket(A_OPEN, localId, 0, dest))

        var reply = readPacket(60000) // recovery can take a while to start
        if (reply.command == A_FAIL) throw IOException(String(reply.data, Charsets.UTF_8).trim())
        if (reply.command != A_OKAY) throw IOException("Sideload OPEN failed: ${cmdName(reply.command)}")
        remoteId = reply.arg0

        val chunk = ByteArray(maxData)
        var sent = 0L
        while (sent < size) {
            val want = minOf(chunk.size.toLong(), size - sent).toInt()
            val read = input.read(chunk, 0, want)
            if (read <= 0) break
            sendPacket(AdbPacket(A_WRTE, localId, remoteId, chunk.copyOf(read)))
            reply = readPacket(60000)
            if (reply.command == A_FAIL) throw IOException(String(reply.data, Charsets.UTF_8).trim())
            if (reply.command == A_CLSE) throw IOException("Device closed sideload early")
            sent += read
            onProgress(sent, size)
        }
        sendPacket(AdbPacket(A_CLSE, localId, remoteId, ByteArray(0)))
        try { readPacket(60000) } catch (_: Throwable) {}
    }

    /** Push a file to the device via the ADB `sync:` service. */
    fun push(input: InputStream, size: Long, remotePath: String, permission: Int = 420,
             onProgress: (Long, Long) -> Unit) {
        requireConnected()
        val localId = 6
        sendPacket(AdbPacket(A_OPEN, localId, 0, "sync:\u0000".toByteArray(Charsets.US_ASCII)))
        var reply = readPacket(10000)
        if (reply.command != A_OKAY) throw IOException("sync OPEN failed: ${cmdName(reply.command)}")
        remoteId = reply.arg0

        val spec = "$remotePath,$permission".toByteArray(Charsets.UTF_8)
        val sendHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put("SEND".toByteArray(Charsets.US_ASCII)).putInt(spec.size).array()
        sendPacket(AdbPacket(A_WRTE, localId, remoteId, sendHeader))
        waitOkay(localId, 10000)
        sendPacket(AdbPacket(A_WRTE, localId, remoteId, spec))
        waitOkay(localId, 10000)

        val chunk = ByteArray(64 * 1024)
        var sent = 0L
        while (sent < size) {
            val want = minOf(chunk.size.toLong(), size - sent).toInt()
            val read = input.read(chunk, 0, want)
            if (read <= 0) break
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put("DATA".toByteArray(Charsets.US_ASCII)).putInt(read).array()
            val payload = header + chunk.copyOf(read)
            sendPacket(AdbPacket(A_WRTE, localId, remoteId, payload))
            waitOkay(localId, 10000)
            sent += read
            onProgress(sent, size)
        }

        val done = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put("DONE".toByteArray(Charsets.US_ASCII))
            .putInt((System.currentTimeMillis() / 1000).toInt()).array()
        sendPacket(AdbPacket(A_WRTE, localId, remoteId, done))
        waitOkay(localId, 10000)
        sendPacket(AdbPacket(A_WRTE, localId, remoteId, "QUIT".toByteArray(Charsets.US_ASCII)))
        waitOkay(localId, 10000)
        sendPacket(AdbPacket(A_CLSE, localId, remoteId, ByteArray(0)))
    }

    private fun waitOkay(localId: Int, timeout: Int) {
        val reply = readPacket(timeout)
        if (reply.command == A_FAIL) throw IOException(String(reply.data, Charsets.UTF_8).trim())
        if (reply.command != A_OKAY) throw IOException("Expected OKAY, got ${cmdName(reply.command)}")
    }

    fun close() {
        try { connection?.close() } catch (_: Throwable) {}
        connection = null
        bulkIn = null
        bulkOut = null
        connected = false
    }

    fun dispose() {
        close()
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Throwable) {}
    }

    private fun requireConnected() {
        if (!connected || connection == null || bulkIn == null || bulkOut == null) {
            throw IllegalStateException("Not connected — scan first")
        }
    }

    // ------------------------------------------------------------------
    // Low-level packet I/O
    // ------------------------------------------------------------------

    private fun sendPacket(packet: AdbPacket) {
        val crc = CRC32().apply { update(packet.data) }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(packet.command)
            putInt(packet.arg0)
            putInt(packet.arg1)
            putInt(packet.data.size)
            putInt(crc.value.toInt())
            putInt(packet.command xor -1)
        }
        writeBulk(header.array())
        if (packet.data.isNotEmpty()) writeBulk(packet.data)
    }

    private fun readPacket(timeout: Int): AdbPacket {
        val header = ByteArray(24)
        readBulk(header, 24, timeout)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        val crc = bb.int
        val magic = bb.int
        if (magic != (cmd xor -1)) throw IOException("Bad ADB magic")
        if (len < 0 || len > 1024 * 1024) throw IOException("Bad ADB payload length: $len")
        val data = if (len > 0) ByteArray(len).also { readBulk(it, len, timeout) } else ByteArray(0)
        val actualCrc = CRC32().apply { update(data) }.value.toInt()
        if (actualCrc != crc) throw IOException("ADB CRC mismatch")
        return AdbPacket(cmd, arg0, arg1, data)
    }

    private fun writeBulk(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = connection!!.bulkTransfer(bulkOut, data, off, data.size - off, 5000)
            if (n <= 0) throw IOException("ADB bulk write failed")
            off += n
        }
    }

    private fun readBulk(buf: ByteArray, len: Int, timeout: Int) {
        var off = 0
        while (off < len) {
            val n = connection!!.bulkTransfer(bulkIn, buf, off, len - off, timeout)
            if (n <= 0) throw IOException("ADB bulk read timed out")
            off += n
        }
    }

    // ------------------------------------------------------------------
    // RSA auth
    // ------------------------------------------------------------------

    private fun getOrCreateKeyPair(): KeyPair {
        val prefs = context.getSharedPreferences("adb_auth_key", Context.MODE_PRIVATE)
        val privB64 = prefs.getString("private", null)
        val pubB64 = prefs.getString("public", null)
        if (privB64 != null && pubB64 != null) {
            val kf = KeyFactory.getInstance("RSA")
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.DEFAULT))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.DEFAULT)))
            )
        }
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        prefs.edit()
            .putString("private", Base64.encodeToString(kp.private.encoded, Base64.DEFAULT))
            .putString("public", Base64.encodeToString(kp.public.encoded, Base64.DEFAULT))
            .apply()
        return kp
    }

    /** Sign the AUTH token like AOSP's RSA_sign(NID_sha1, token, 20, ...). */
    private fun signToken(token: ByteArray): ByteArray? {
        return try {
            val kp = getOrCreateKeyPair()
            val digestInfo = SHA1_PREFIX + token
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, kp.private)
            cipher.doFinal(digestInfo)
        } catch (e: Throwable) {
            null
        }
    }

    /** Build ADB's custom 524-byte RSAPublicKey blob from our RSA key. */
    private fun publicKeyBlob(): ByteArray {
        val kp = getOrCreateKeyPair()
        val pub = kp.public
        val n = (pub as java.security.interfaces.RSAPublicKey).modulus
        val e = pub.publicExponent.toInt()

        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32).toInt()
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BITS * 2).mod(n) // 2^4096 mod N

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MODULUS_WORDS)
        buf.putInt(n0inv)
        buf.put(toLE(n, MODULUS_SIZE))
        buf.put(toLE(rr, MODULUS_SIZE))
        buf.putInt(e)
        return buf.array()
    }

    private fun toLE(value: BigInteger, size: Int): ByteArray {
        var be = value.toByteArray()
        var start = 0
        while (start < be.size - 1 && be[start] == 0.toByte()) start++
        be = be.copyOfRange(start, be.size)
        val out = ByteArray(size)
        val copyLen = minOf(size, be.size)
        for (i in 0 until copyLen) out[i] = be[be.size - 1 - i]
        return out
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.netherish.flashbridge.USB_PERMISSION_ADB"

        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_FAIL = 0x4c414146

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        private const val ADB_VERSION = 0x01000000
        private const val ADB_MAXDATA = 4096

        private const val MODULUS_SIZE = 256
        private const val MODULUS_BITS = 2048
        private const val MODULUS_WORDS = MODULUS_SIZE / 4

        private val SHA1_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        fun isAdbDevice(device: UsbDevice): Boolean {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == 0xFF && intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01) {
                    return true
                }
            }
            return false
        }

        private fun cmdName(cmd: Int): String = when (cmd) {
            A_CNXN -> "CNXN"
            A_AUTH -> "AUTH"
            A_OPEN -> "OPEN"
            A_OKAY -> "OKAY"
            A_CLSE -> "CLSE"
            A_WRTE -> "WRTE"
            A_FAIL -> "FAIL"
            else -> "0x${Integer.toHexString(cmd)}"
        }
    }
}

data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

sealed class AdbState {
    object Idle : AdbState()
    object NeedPermission : AdbState()
    data class NoDevice(val message: String) : AdbState()
    data class Connected(val info: UsbInfo) : AdbState()
    data class Error(val message: String) : AdbState()
}
