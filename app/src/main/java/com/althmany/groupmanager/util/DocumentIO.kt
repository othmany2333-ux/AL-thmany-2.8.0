package com.althmany.groupmanager.util

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

object DocumentIO {
    fun readText(
        resolver: ContentResolver,
        uri: Uri,
        maxChars: Int
    ): Result<String> = runCatching {
        val maxBytes = (maxChars.toLong() * 4L + 4L).coerceAtMost(4_000_004L).toInt()
        val bytes = resolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(8_192)
            var remaining = maxBytes
            while (remaining > 0) {
                val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toByteArray()
        } ?: error("Unable to open document")

        val (charset, offset) = detectCharset(bytes)
        bytes.copyOfRange(offset, bytes.size)
            .toString(charset)
            .take(maxChars)
    }

    fun writeText(
        resolver: ContentResolver,
        uri: Uri,
        text: String
    ): Result<Unit> = runCatching {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(text)
        } ?: error("Unable to create document")
    }

    private fun detectCharset(bytes: ByteArray): Pair<Charset, Int> = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Charsets.UTF_16LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Charsets.UTF_16BE to 2
        else -> Charsets.UTF_8 to 0
    }
}
