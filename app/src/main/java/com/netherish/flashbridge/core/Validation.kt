package com.netherish.flashbridge.core

/**
 * Pure input validation helpers.
 *
 * These are deliberately free of Android dependencies so they can be unit
 * tested and called from both the UI and the USB core before any write occurs.
 */
object Validation {

    const val MAX_FILE_SIZE = 8L * 1024 * 1024 * 1024 // 8 GiB

    private val PARTITION = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
    private val HEX = Regex("[0-9A-Fa-f]{1,8}")

    /** Fastboot partition names are strictly limited to a safe character set. */
    fun isValidPartition(name: String): Boolean {
        // Never let trim() silently swallow control characters into a valid name.
        if (name.any { it.isISOControl() }) return false
        val clean = name.trim()
        if (!PARTITION.matches(clean)) return false
        return clean != "." && clean != ".." && !clean.contains("..")
    }

    fun partitionError(name: String): String? =
        if (isValidPartition(name)) null else "Invalid partition name: ${name.ifBlank { "(empty)" }}"

    /** A/B slots only. */
    fun isValidSlot(slot: String): Boolean = slot.trim().lowercase() in setOf("a", "b")

    /** Parse an unsigned 32-bit hex value, with or without a 0x prefix. */
    fun parseHex32(text: String): Int? {
        val clean = text.trim().removePrefix("0x").removePrefix("0X")
        if (!HEX.matches(clean)) return null
        return clean.toUIntOrNull(16)?.toInt()
    }

    /** Reject unknown, negative, zero and absurdly large file sizes. */
    fun isValidFileSize(size: Long): Boolean = size > 0 && size <= MAX_FILE_SIZE

    /** Fastboot commands must be single-line, printable and short. */
    fun isValidFastbootCommand(cmd: String): Boolean {
        val clean = cmd.trim()
        return clean.isNotEmpty() && clean.length <= 256 && clean.none { it == '\u0000' || it == '\n' || it == '\r' }
    }

    /** ADB shell commands: allow typical usage, reject NUL and huge payloads. */
    fun isValidShellCommand(cmd: String): Boolean {
        val clean = cmd.trim()
        return clean.isNotEmpty() && clean.length <= 1024 && clean.none { it == '\u0000' }
    }

    /** Remote paths used by `adb push` must be absolute and NUL-free. */
    fun isValidRemotePath(path: String): Boolean {
        val clean = path.trim()
        return clean.startsWith("/") && clean.length <= 1024 &&
            clean.none { it == '\u0000' || it == '\n' || it == '\r' }
    }

    /** Remove control characters before echoing user input into the session log. */
    fun logSafe(text: String): String = text.map { c ->
        if (c == '\n' || c == '\r' || c == '\t') c else if (c.code < 0x20 || c.code == 0x7F) '?' else c
    }.joinToString("")
}
