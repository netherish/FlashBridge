package com.netherish.flashbridge.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for the AOSP fastboot download command format. */
class FastbootProtocolTest {

    @Test
    fun downloadCommandUsesEightLowercaseHexDigits() {
        assertEquals("download:00000001", FastbootUsb.downloadCommand(1))
        assertEquals("download:00000200", FastbootUsb.downloadCommand(512))
        assertEquals("download:00010000", FastbootUsb.downloadCommand(65536))
        assertEquals("download:ffffffff", FastbootUsb.downloadCommand(0xFFFFFFFFL))
    }

    @Test
    fun downloadCommandDoesNotImposeAnImageSizeLimit() {
        assertEquals("download:100000000", FastbootUsb.downloadCommand(0x100000000L))
    }
}
