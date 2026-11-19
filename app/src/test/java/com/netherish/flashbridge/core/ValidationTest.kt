package com.netherish.flashbridge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for pure input validation used before any USB write occurs. */
class ValidationTest {

    @Test
    fun validPartitionNames() {
        listOf("boot", "system_a", "vendor.img", "userdata", "metadata-1", "a").forEach {
            assertTrue("'$it' should be valid", Validation.isValidPartition(it))
        }
    }

    @Test
    fun invalidPartitionNames() {
        listOf("", " ", ".", "..", "../boot", "boot/a", "boot\\a", "boot;rm", "boot\n",
            "a".repeat(65), "boot\u0000").forEach {
            assertFalse("'$it' should be rejected", Validation.isValidPartition(it))
            assertTrue(Validation.partitionError(it)?.contains("Invalid partition") == true)
        }
        assertNull(Validation.partitionError("boot"))
    }

    @Test
    fun slotsAreAbOnly() {
        assertTrue(Validation.isValidSlot("a"))
        assertTrue(Validation.isValidSlot(" B "))
        assertFalse(Validation.isValidSlot("c"))
        assertFalse(Validation.isValidSlot(""))
        assertFalse(Validation.isValidSlot("all"))
    }

    @Test
    fun hexParsing() {
        org.junit.Assert.assertEquals(0x20000000, Validation.parseHex32("0x20000000"))
        org.junit.Assert.assertEquals(-1, Validation.parseHex32("FFFFFFFF"))
        listOf("", "0x", "gg", "0x123456789", "-1", "12 34").forEach {
            assertNull("'$it' should not parse", Validation.parseHex32(it))
        }
    }

    @Test
    fun fileSizeBounds() {
        assertFalse(Validation.isValidFileSize(0))
        assertFalse(Validation.isValidFileSize(-1))
        assertFalse(Validation.isValidFileSize(Validation.MAX_FILE_SIZE + 1))
        assertTrue(Validation.isValidFileSize(1))
        assertTrue(Validation.isValidFileSize(Validation.MAX_FILE_SIZE))
    }

    @Test
    fun commandAndPathValidation() {
        assertTrue(Validation.isValidFastbootCommand("getvar:product"))
        assertFalse(Validation.isValidFastbootCommand("oem unlock\nreboot"))
        assertFalse(Validation.isValidFastbootCommand(""))
        assertFalse(Validation.isValidFastbootCommand("a".repeat(257)))
        assertTrue(Validation.isValidShellCommand("getprop ro.product.model"))
        assertFalse(Validation.isValidShellCommand("echo hi\u0000"))
        assertTrue(Validation.isValidRemotePath("/sdcard/Download/a.zip"))
        assertFalse(Validation.isValidRemotePath("relative/path"))
        assertFalse(Validation.isValidRemotePath("/sdcard/a\u0000b"))
    }

    @Test
    fun logSafeStripsControlCharacters() {
        val safe = Validation.logSafe("boot\u0000\nname\r\t!")
        assertFalse(safe.contains('\u0000'))
        assertTrue(safe.contains('\n'))
        assertTrue(safe.contains('\t'))
        assertTrue(safe.contains('!'))
    }
}
