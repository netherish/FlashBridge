package com.netherish.flashbridge.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Regression tests for the ADB public-key wire format. */
class AdbKeyFormatTest {

    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun blobHasAospStructure() {
        val pub = keyPair().public as RSAPublicKey
        val blob = AdbKeyFormat.encodePublicKey(pub)
        assertEquals(524, blob.size)
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bb.int)                       // modulus_size_words
        assertTrue(bb.int != 0)                         // n0inv
        assertEquals(65537, blob.copyOfRange(520, 524).let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int
        })                                             // exponent
    }

    @Test
    fun blobRoundTripsThroughAospDecode() {
        val pub = keyPair().public as RSAPublicKey
        val blob = AdbKeyFormat.encodePublicKey(pub)
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        bb.int // words
        bb.int // n0inv
        val modulusLe = ByteArray(256).also { bb.get(it) }
        val modulus = java.math.BigInteger(1, modulusLe.reversedArray())
        assertEquals(pub.modulus, modulus)
        val rr = ByteArray(256).also { bb.get(it) }
        assertTrue(java.math.BigInteger(1, rr.reversedArray()).signum() > 0)
        assertEquals(65537, bb.int)
    }

    @Test
    fun checksumIsAdditiveNotCrc32() {
        assertEquals(0, AdbKeyFormat.checksum(ByteArray(0)))
        assertEquals(6, AdbKeyFormat.checksum(byteArrayOf(1, 2, 3)))
        assertEquals(255, AdbKeyFormat.checksum(byteArrayOf(-1))) // unsigned byte
        assertEquals(562, AdbKeyFormat.checksum("host::\u0000".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun payloadIsBase64StringWithNulTerminator() {
        val blob = keyPair().let { AdbKeyFormat.encodePublicKey(it.public as RSAPublicKey) }
        val payload = AdbKeyFormat.publicKeyPayload(blob)
        assertEquals(0.toByte(), payload.last())
        val text = String(payload.copyOf(payload.size - 1), Charsets.US_ASCII)
        val token = text.substringBefore(' ')
        assertTrue(text.contains(" flashbridge@localhost"))
        assertArrayEquals(blob, Base64.getDecoder().decode(token))
        assertEquals(700, token.length)
    }
}
