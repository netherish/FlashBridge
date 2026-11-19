package com.netherish.flashbridge.core

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Parser for user-supplied MediaTek Download Agent files.
 *
 * Nothing is bundled — the user picks their own DA from storage. We read the
 * documented 0x6C-byte header and the DA entry table so the app can recognise
 * the protocol version, the chipsets it supports, and the load addresses of
 * each stage.
 *
 * Header layout (from the public MediaTek DA documentation):
 *   0x00 magic        "MTK_DOWNLOAD_AGENT"
 *   0x20 file id      "MTK_AllInOne_DA_v3" (XFlash) / "MTK_DA_v6" (XML)
 *   0x60 version
 *   0x64 magic number 0x99886622
 *   0x68 number of SoC entries
 *   0x6C entries       0xDC bytes each (V5/V6), 0xD8 (legacy V3)
 */
object MtkDa {

    enum class Protocol(val label: String) {
        V3("Legacy (V3)"),
        V5("XFlash (V5)"),
        V6("XML (V6)"),
        UNKNOWN("Unknown"),
    }

    data class Region(
        val offset: Int,
        val length: Int,
        val address: Int,
        val regionLength: Int,
        val signatureLength: Int,
    )

    data class Entry(
        val hwCode: Int,
        val hwSubCode: Int,
        val hwVersion: Int,
        val regionIndex: Int,
        val regions: List<Region>,
    )

    data class DaFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val sha256: String,
        val fileId: String,
        val version: Int,
        val daMagic: Int,
        val protocol: Protocol,
        val entries: List<Entry>,
    ) {
        val supportedCodes: List<Int> get() = entries.map { it.hwCode }
        fun supports(hwCode: Int): Boolean = entries.any { it.hwCode == hwCode }
    }

    data class AuthFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val sha256: String,
        val kind: String,
    )

    fun parseDa(context: Context, uri: Uri): DaFile {
        val resolver = context.contentResolver
        val name = FileUtils.name(context, uri)
        val size = FileUtils.size(context, uri)
        val head = resolver.openInputStream(uri)?.use { readUpTo(it, 1 shl 20) }
            ?: throw IllegalArgumentException("Cannot open DA file")
        val sha = resolver.openInputStream(uri)?.use { FileUtils.hash(it, "SHA-256") } ?: ""

        if (head.size < 0x6C) throw IllegalArgumentException("File too small to be a Download Agent")

        val magic = ascii(head, 0, 0x12)
        if (!magic.contains("MTK_DOWNLOAD_AGENT", ignoreCase = true)) {
            throw IllegalArgumentException("Not a MediaTek DA (magic: ${magic.ifEmpty { "none" }})")
        }

        val fileId = ascii(head, 0x20, 0x40)
        val version = u32(head, 0x60)
        val daMagic = u32(head, 0x64)
        val socCount = u32(head, 0x68)

        val protocol = when {
            fileId.contains("v6", true) || fileId.contains("chimaera", true) -> Protocol.V6
            fileId.contains("v3", true) || fileId.contains("allinone", true) -> Protocol.V5
            else -> Protocol.V3
        }
        val entrySize = if (protocol == Protocol.V3) 0xD8 else 0xDC

        val entries = mutableListOf<Entry>()
        for (i in 0 until minOf(socCount, 128)) {
            val base = 0x6C + i * entrySize
            if (base + 0x14 > head.size) break
            if (u16(head, base) != 0xDADA) continue
            val hwCode = u16(head, base + 0x02)
            val hwSub = u16(head, base + 0x04)
            val hwVer = u16(head, base + 0x06)
            val regionIndex = u16(head, base + 0x10)
            val regionCount = u16(head, base + 0x12)
            val regions = mutableListOf<Region>()
            for (r in 0 until minOf(regionCount, 8)) {
                val rb = base + 0x14 + r * 0x20
                if (rb + 0x14 > head.size) break
                regions += Region(
                    offset = u32(head, rb),
                    length = u32(head, rb + 0x04),
                    address = u32(head, rb + 0x08),
                    regionLength = u32(head, rb + 0x0C),
                    signatureLength = u32(head, rb + 0x10),
                )
            }
            entries += Entry(hwCode, hwSub, hwVer, regionIndex, regions)
        }

        if (entries.isEmpty()) throw IllegalArgumentException("DA has no valid chipset entries")

        return DaFile(uri, name, size, sha, fileId, version, daMagic, protocol, entries)
    }

    fun parseAuth(context: Context, uri: Uri): AuthFile {
        val name = FileUtils.name(context, uri)
        val size = FileUtils.size(context, uri)
        val sha = context.contentResolver.openInputStream(uri)?.use { FileUtils.hash(it, "SHA-256") } ?: ""
        val kind = when {
            name.contains("sla", true) -> "SLA authorization"
            name.contains("auth", true) -> "Authorization"
            name.contains("cert", true) -> "Certificate"
            else -> "Custom auth file"
        }
        return AuthFile(uri, name, size, sha, kind)
    }

    /** Extract one DA stage (region) from the user's file as raw bytes. */
    fun readRegion(context: Context, uri: Uri, region: Region): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val skipped = skipFully(input, region.offset.toLong())
            if (skipped != region.offset.toLong()) throw IllegalArgumentException("DA region offset out of range")
            return readUpTo(input, region.length)
        }
        throw IllegalArgumentException("Cannot open DA file")
    }

    // ---- Byte helpers ----------------------------------------------------

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8))

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun ascii(b: ByteArray, off: Int, maxLen: Int): String {
        if (off >= b.size) return ""
        val end = minOf(off + maxLen, b.size)
        var last = end
        while (last > off && b[last - 1].toInt() == 0) last--
        return String(b, off, last - off, Charsets.US_ASCII).trim()
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun skipFully(input: InputStream, count: Long): Long {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
        return count - remaining
    }
}
