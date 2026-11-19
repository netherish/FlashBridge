package com.netherish.flashbridge.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Pure encoding helpers for ADB RSA authentication, kept free of Android
 * dependencies so they can be unit-tested.
 *
 * References:
 * - AOSP system/core/libcrypto_utils/android_pubkey.cpp (RSAPublicKey)
 * - AOSP packages/modules/adb/crypto/rsa_2048_key.cpp (CalculatePublicKey)
 * - AOSP packages/modules/adb/client/auth.cpp (send_auth_publickey)
 */
internal object AdbKeyFormat {

    const val MODULUS_SIZE = 256
    const val ENCODED_SIZE = 3 * Int.SIZE_BITS / 8 + 2 * MODULUS_SIZE // 524

    private const val MODULUS_BITS = 2048
    private const val MODULUS_WORDS = MODULUS_SIZE / 4

    /**
     * AOSP adb packet checksum: simple sum of unsigned payload bytes, stored
     * in the `data_crc` header field. Not CRC-32 — using CRC-32 here makes
     * every packet fail verification on the device.
     *
     * Reference: AOSP packages/modules/adb/adb.cpp calculate_apacket_checksum()
     */
    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    /** Android's custom 524-byte little-endian RSAPublicKey structure. */
    fun encodePublicKey(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        require(n.bitLength() == MODULUS_BITS) { "ADB requires a 2048-bit RSA key" }

        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32).toInt()
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BITS * 2).mod(n)

        val buf = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MODULUS_WORDS)
        buf.putInt(n0inv)
        buf.put(toLE(n, MODULUS_SIZE))
        buf.put(toLE(rr, MODULUS_SIZE))
        buf.putInt(pub.publicExponent.toInt())
        return buf.array()
    }

    /**
     * Wire payload for AUTH_RSAPUBLICKEY. AOSP's adbd reads this as a
     * NUL-terminated C string: base64(blob) + " " + user@host. The framework
     * base64-decodes the first token to derive the fingerprint shown in the
     * "Allow USB debugging?" dialog. Sending the raw binary blob makes the
     * dialog never appear.
     */
    fun publicKeyPayload(blob: ByteArray, host: String = "flashbridge@localhost"): ByteArray {
        require(blob.size == ENCODED_SIZE) { "Unexpected key blob size: ${blob.size}" }
        val b64 = Base64.getEncoder().encodeToString(blob)
        return "$b64 $host\u0000".toByteArray(Charsets.US_ASCII)
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
}
