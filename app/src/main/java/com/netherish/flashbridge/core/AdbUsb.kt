package com.netherish.flashbridge.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

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
    private val lock = Any()

    private var connection: UsbDeviceConnection? = null
    private var bulkOut: UsbEndpoint? = null
    private var bulkIn: UsbEndpoint? = null
    private var claimedInterface: UsbInterface? = null
    private var connectedDevice: UsbDevice? = null
    private var connecting = false
    private var connected = false
    private var remoteId = 0
    private var maxData = 4096

    /** Used only when the Android keystore is unavailable; never written to disk. */
    private var ephemeralKey: KeyPair? = null

    private val _status = MutableStateFlow<AdbState>(AdbState.Idle)
    val status: StateFlow<AdbState> = _status

    private val _deviceInfo = MutableStateFlow<UsbInfo?>(null)
    val deviceInfo: StateFlow<UsbInfo?> = _deviceInfo

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION != intent.action) return
            val dev = intent.deviceExtra()
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                connectAndHandshake(dev)
            } else {
                _status.value = AdbState.Error("USB permission denied")
            }
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val detached = intent.deviceExtra() ?: return
            synchronized(lock) {
                val current = connectedDevice
                if (current != null && FastbootUsb.sameDevice(current, detached)) {
                    closeLocked()
                    _status.value = AdbState.Error("Device disconnected during session")
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

    fun hasUsbHost(): Boolean = context.packageManager.hasSystemFeature("android.hardware.usb.host")

    /** Scan for an ADB device and request permission if needed. */
    fun scan() {
        if (!hasUsbHost()) {
            _status.value = AdbState.Error("No USB host support")
            return
        }
        synchronized(lock) {
            val current = connectedDevice
            if (connected && current != null &&
                usbManager.deviceList.values.any { FastbootUsb.sameDevice(it, current) }
            ) return
        }
        val dev = usbManager.deviceList.values.firstOrNull { isAdbDevice(it) }
        if (dev == null) {
            _status.value = AdbState.NoDevice("No ADB device found")
            return
        }
        _deviceInfo.value = dev.toInfo()
        if (usbManager.hasPermission(dev)) {
            connectAndHandshake(dev)
        } else {
            _status.value = AdbState.NeedPermission
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(dev, pi)
        }
    }

    private fun connectAndHandshake(dev: UsbDevice) {
        synchronized(lock) {
            if (connecting) return
            connecting = true
            var conn: UsbDeviceConnection? = null
            try {
                closeLocked()
                conn = usbManager.openDevice(dev) ?: throw IOException("openDevice returned null")
                var out: UsbEndpoint? = null
                var inn: UsbEndpoint? = null
                var adbInterface: UsbInterface? = null

                for (i in 0 until dev.interfaceCount) {
                    val intf = dev.getInterface(i)
                    if (intf.interfaceClass != 0xFF || intf.interfaceSubclass != 0x42 ||
                        intf.interfaceProtocol != 0x01) continue
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
                    throw IOException("No ADB bulk endpoints found")
                }
                if (!conn.claimInterface(adbInterface, true)) {
                    throw IOException("Failed to claim ADB interface")
                }

                connection = conn
                bulkOut = out
                bulkIn = inn
                claimedInterface = adbInterface
                connectedDevice = dev
                connected = true

                handshake()
                _status.value = AdbState.Connected(dev.toInfo())
            } catch (e: Throwable) {
                closeLocked()
                _status.value = AdbState.Error(e.message ?: "ADB connection failed")
            } finally {
                connecting = false
            }
        }
    }

    fun isConnected(): Boolean = synchronized(lock) { connected }

    // ------------------------------------------------------------------
    // ADB handshake
    // ------------------------------------------------------------------

    private fun handshake() {
        val identity = "host::\u0000".toByteArray(Charsets.US_ASCII)
        sendPacket(AdbPacket(A_CNXN, ADB_VERSION, ADB_MAXDATA, identity))
        Log.d(TAG, "handshake: sent CNXN, awaiting reply")

        var reply = readPacket(8000)
        var attempt = 0
        while (reply.command == A_AUTH && reply.arg0 == AUTH_TOKEN && attempt < 3) {
            attempt++
            Log.d(TAG, "handshake: AUTH token attempt=$attempt tokenLen=${reply.data.size}")
            if (attempt == 1) {
                val sig = signToken(reply.data)
                if (sig != null) {
                    Log.d(TAG, "handshake: sending signature len=${sig.size}")
                    sendPacket(AdbPacket(A_AUTH, AUTH_SIGNATURE, 0, sig))
                } else {
                    Log.w(TAG, "handshake: signing failed, sending public key early")
                    sendPublicKey()
                }
            } else {
                // Device rejected signature — send the public key so the
                // standard "Allow USB debugging?" prompt appears on the target.
                Log.d(TAG, "handshake: sending public key (attempt=$attempt)")
                sendPublicKey()
            }
            reply = readPacket(if (attempt >= 2) 20000 else 8000)
            Log.d(TAG, "handshake: reply cmd=${cmdName(reply.command)} arg0=${reply.arg0} len=${reply.data.size}")
        }
        if (reply.command != A_CNXN) {
            Log.e(TAG, "handshake: auth failed cmd=${cmdName(reply.command)} arg0=${reply.arg0}")
            throw IOException("ADB auth failed: ${cmdName(reply.command)}")
        }
        Log.d(TAG, "handshake: authorized")
        maxData = reply.arg1.takeIf { it in 4096..(1024 * 1024) } ?: 4096
    }

    /**
     * AOSP sends the public key as a base64-encoded, NUL-terminated string
     * ("<base64> user@host"), not the raw 524-byte binary blob. adbd reads the
     * payload as a C string and the framework parses/decodes it to show the
     * "Allow USB debugging?" prompt. Sending raw bytes made the prompt never
     * appear.
     */
    private fun sendPublicKey() {
        val payload = AdbKeyFormat.publicKeyPayload(publicKeyBlob())
        Log.d(TAG, "sendPublicKey: payload=${payload.size}")
        sendPacket(AdbPacket(A_AUTH, AUTH_RSAPUBLICKEY, 0, payload))
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Read a device property via `shell:getprop name`. */
    fun getprop(name: String): String {
        if (name.isBlank() || name.length > 128 || name.any { it == '\u0000' }) {
            throw IllegalArgumentException("Invalid property name")
        }
        return shell("getprop $name").trim()
    }

    /** Run a shell command and return combined output. */
    fun shell(command: String, timeout: Int = 10000): String = synchronized(lock) {
        requireConnected()
        if (!Validation.isValidShellCommand(command)) throw IllegalArgumentException("Invalid shell command")
        val localId = 1
        val dest = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(AdbPacket(A_OPEN, localId, 0, dest))

        var reply = readPacket(timeout)
        if (reply.command == A_FAIL) throw IOException(String(reply.data, Charsets.UTF_8).trim())
        if (reply.command != A_OKAY) throw IOException("OPEN failed: ${cmdName(reply.command)}")
        remoteId = reply.arg0

        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + timeout.coerceAtMost(60000)
        var packets = 0
        while (System.currentTimeMillis() < deadline && packets < MAX_SHELL_PACKETS) {
            val pkt = readPacket(timeout)
            packets++
            when (pkt.command) {
                A_WRTE -> {
                    out.append(String(pkt.data, Charsets.UTF_8))
                    sendPacket(AdbPacket(A_OKAY, localId, remoteId, ByteArray(0)))
                }
                A_CLSE -> {
                    sendPacket(AdbPacket(A_CLSE, localId, remoteId, ByteArray(0)))
                    break
                }
                A_OKAY -> Unit
                else -> Unit
            }
        }
        out.toString()
    }

    /** Reboot the device into the given target (e.g. "bootloader", "recovery"). */
    fun reboot(target: String = "bootloader") = synchronized(lock) {
        requireConnected()
        if (target.length > 64 || target.any { it == '\u0000' || it == '\n' }) {
            throw IllegalArgumentException("Invalid reboot target")
        }
        val localId = 2
        val dest = "reboot:$target\u0000".toByteArray(Charsets.US_ASCII)
        sendPacket(AdbPacket(A_OPEN, localId, 0, dest))
        // Device reboots immediately; don't block waiting for a reply.
    }

    /** Stream an update ZIP to a device in recovery mode. */
    fun sideload(input: InputStream, size: Long, onProgress: (Long, Long) -> Unit) = synchronized(lock) {
        requireTransfer(size)
        val localId = 5
        val dest = "sideload:\u0000".toByteArray(Charsets.US_ASCII)
        sendPacket(AdbPacket(A_OPEN, localId, 0, dest))

        var reply = readPacket(60000)
        if (reply.command == A_FAIL) throw IOException(String(reply.data, Charsets.UTF_8).trim())
        if (reply.command != A_OKAY) throw IOException("Sideload OPEN failed: ${cmdName(reply.command)}")
        remoteId = reply.arg0

        val chunk = ByteArray(maxData)
        var sent = 0L
        while (sent < size) {
            val want = minOf(chunk.size.toLong(), size - sent).toInt()
            val read = input.read(chunk, 0, want)
            if (read <= 0) throw IOException("Unexpected end of ZIP — expected $size bytes, got $sent")
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
             onProgress: (Long, Long) -> Unit) = synchronized(lock) {
        requireTransfer(size)
        if (!Validation.isValidRemotePath(remotePath)) throw IllegalArgumentException("Invalid remote path")
        val localId = 6
        sendPacket(AdbPacket(A_OPEN, localId, 0, "sync:\u0000".toByteArray(Charsets.US_ASCII)))
        var reply = readPacket(10000)
        if (reply.command == A_FAIL) throw IOException(String(reply.data, Charsets.UTF_8).trim())
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
            if (read <= 0) throw IOException("Unexpected end of file — expected $size bytes, got $sent")
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

    private fun requireTransfer(size: Long) {
        requireConnected()
        if (!Validation.isValidFileSize(size)) throw IOException("Invalid file size: $size")
    }

    private fun waitOkay(localId: Int, timeout: Int) {
        val reply = readPacket(timeout)
        if (reply.command == A_FAIL) throw IOException(String(reply.data, Charsets.UTF_8).trim())
        if (reply.command != A_OKAY) throw IOException("Expected OKAY, got ${cmdName(reply.command)}")
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        try { claimedInterface?.let { connection?.releaseInterface(it) } } catch (_: Throwable) {}
        try { connection?.close() } catch (_: Throwable) {}
        connection = null
        bulkIn = null
        bulkOut = null
        claimedInterface = null
        connectedDevice = null
        connected = false
    }

    fun dispose() {
        close()
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Throwable) {}
        try { context.unregisterReceiver(detachReceiver) } catch (_: Throwable) {}
    }

    private fun requireConnected() {
        if (!connected || connection == null || bulkIn == null || bulkOut == null) {
            throw IOException("Not connected — scan first")
        }
    }

    // ------------------------------------------------------------------
    // Low-level packet I/O
    // ------------------------------------------------------------------

    private fun sendPacket(packet: AdbPacket) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(packet.command)
            putInt(packet.arg0)
            putInt(packet.arg1)
            putInt(packet.data.size)
            putInt(AdbKeyFormat.checksum(packet.data))
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
        val actualCrc = AdbKeyFormat.checksum(data)
        if (actualCrc != crc) throw IOException("ADB checksum mismatch")
        return AdbPacket(cmd, arg0, arg1, data)
    }

    private fun writeBulk(data: ByteArray) {
        val conn = connection ?: throw IOException("Device disconnected")
        val out = bulkOut ?: throw IOException("Device disconnected")
        var off = 0
        while (off < data.size) {
            val n = conn.bulkTransfer(out, data, off, data.size - off, 5000)
            if (n <= 0) throw IOException("ADB bulk write failed")
            off += n
        }
    }

    private fun readBulk(buf: ByteArray, len: Int, timeout: Int) {
        val conn = connection ?: throw IOException("Device disconnected")
        val inp = bulkIn ?: throw IOException("Device disconnected")
        var off = 0
        while (off < len) {
            val n = conn.bulkTransfer(inp, buf, off, len - off, timeout)
            if (n <= 0) throw IOException("ADB bulk read timed out")
            off += n
        }
    }

    // ------------------------------------------------------------------
    // RSA auth key storage
    // ------------------------------------------------------------------

    private fun securePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "adb_auth_key_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Move any legacy plaintext key into encrypted storage, then erase it. */
    private fun migrateLegacyKey(secure: SharedPreferences) {
        val legacy = context.getSharedPreferences("adb_auth_key", Context.MODE_PRIVATE)
        val priv = legacy.getString("private", null)
        val pub = legacy.getString("public", null)
        if (priv != null && pub != null) {
            secure.edit().putString("private", priv).putString("public", pub).apply()
            legacy.edit().clear().apply()
        }
    }

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun getOrCreateKeyPair(): KeyPair = try {
        val secure = securePrefs()
        migrateLegacyKey(secure)
        val privB64 = secure.getString("private", null)
        val pubB64 = secure.getString("public", null)
        if (privB64 != null && pubB64 != null) {
            val kf = KeyFactory.getInstance("RSA")
            KeyPair(
                kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.DEFAULT))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.DEFAULT))),
            )
        } else {
            val kp = generateKeyPair()
            secure.edit()
                .putString("private", Base64.encodeToString(kp.private.encoded, Base64.DEFAULT))
                .putString("public", Base64.encodeToString(kp.public.encoded, Base64.DEFAULT))
                .apply()
            kp
        }
    } catch (_: Throwable) {
        // Keystore unavailable: use an ephemeral key for this process only.
        // Never fall back to plaintext storage on disk.
        ephemeralKey ?: generateKeyPair().also { ephemeralKey = it }
    }

    /** Sign the AUTH token like AOSP's RSA_sign(NID_sha1, token, 20, ...). */
    private fun signToken(token: ByteArray): ByteArray? = try {
        val kp = getOrCreateKeyPair()
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, kp.private)
        cipher.doFinal(SHA1_PREFIX + token)
    } catch (_: Throwable) {
        null
    }

    /** Build ADB's custom 524-byte RSAPublicKey blob from our RSA key. */
    private fun publicKeyBlob(): ByteArray =
        AdbKeyFormat.encodePublicKey(getOrCreateKeyPair().public as java.security.interfaces.RSAPublicKey)

    companion object {
        private const val TAG = "FlashBridgeADB"
        const val ACTION_USB_PERMISSION = "com.netherish.flashbridge.USB_PERMISSION_ADB"
        private const val MAX_SHELL_PACKETS = 4096

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

        private val SHA1_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        fun isAdbDevice(device: UsbDevice): Boolean = (0 until device.interfaceCount).any { i ->
            val intf = device.getInterface(i)
            intf.interfaceClass == 0xFF && intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01
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

        internal fun Intent.deviceExtra(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(UsbManager.EXTRA_DEVICE)
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
