package com.netherish.flashbridge.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.security.MessageDigest

/** File helpers: display names, sizes and checksums for image verification. */
object FileUtils {

    fun name(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "image.img"
    }

    fun size(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
        }
        return -1L
    }

    /** Human-readable byte count. */
    fun readable(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /** Compute a hash over a stream, optionally reporting progress. */
    fun hash(input: InputStream, algorithm: String, onProgress: ((Long) -> Unit)? = null): String {
        val md = MessageDigest.getInstance(algorithm)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
            total += n
            onProgress?.invoke(total)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
