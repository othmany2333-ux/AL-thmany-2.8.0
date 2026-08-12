package com.althmany.groupmanager.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.LinkedHashSet
import java.util.zip.ZipInputStream

object DocumentIO {
    private val WHATSAPP_INVITE_REGEX = Regex(
        "https?://(?:chat\\.)?whatsapp\\.com/[A-Za-z0-9_-]+",
        RegexOption.IGNORE_CASE
    )
    private const val MAX_LEGACY_XLS_BYTES = 12 * 1024 * 1024
    private const val MAX_XLSX_SCAN_CHARS = 64L * 1024L * 1024L
    private const val STREAM_BUFFER_CHARS = 8_192
    private const val REGEX_CARRY_CHARS = 384

    fun readText(resolver: ContentResolver, uri: Uri, maxChars: Int): Result<String> = runCatching {
        require(maxChars > 0)
        val name = displayName(resolver, uri).lowercase()
        val mime = resolver.getType(uri)?.lowercase().orEmpty()
        when {
            isOpenXmlSpreadsheet(name, mime) -> readOpenXmlSpreadsheetLinks(resolver, uri, maxChars)
            isLegacySpreadsheet(name, mime) -> readLegacySpreadsheetLinks(resolver, uri, maxChars)
            else -> readPlainText(resolver, uri, maxChars)
        }.take(maxChars)
    }

    fun writeText(resolver: ContentResolver, uri: Uri, text: String): Result<Unit> = runCatching {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: error("Unable to create document")
    }

    private fun isOpenXmlSpreadsheet(name: String, mime: String): Boolean =
        name.endsWith(".xlsx") || name.endsWith(".xlsm") ||
            mime.contains("spreadsheetml") || mime.contains("macroenabled")

    private fun isLegacySpreadsheet(name: String, mime: String): Boolean =
        name.endsWith(".xls") || mime == "application/vnd.ms-excel"

    private fun displayName(resolver: ContentResolver, uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun readPlainText(resolver: ContentResolver, uri: Uri, maxChars: Int): String {
        val raw = resolver.openInputStream(uri) ?: error("Unable to open document")
        BufferedInputStream(raw, 16 * 1024).use { stream ->
            stream.mark(4)
            val prefix = ByteArray(3)
            val count = stream.read(prefix)
            stream.reset()
            val (charset, bomBytes) = detectCharset(prefix, count)
            var skipped = 0
            while (skipped < bomBytes && stream.read() >= 0) skipped++
            val reader = InputStreamReader(stream, charset)
            val output = StringBuilder(minOf(maxChars, 64 * 1024))
            val buffer = CharArray(STREAM_BUFFER_CHARS)
            while (output.length < maxChars) {
                val n = reader.read(buffer, 0, minOf(buffer.size, maxChars - output.length))
                if (n < 0) break
                output.append(buffer, 0, n)
            }
            return output.toString()
        }
    }

    private fun readOpenXmlSpreadsheetLinks(
        resolver: ContentResolver,
        uri: Uri,
        maxChars: Int
    ): String {
        val found = LinkedHashSet<String>()
        var outputChars = 0
        var scannedChars = 0L
        val raw = resolver.openInputStream(uri) ?: error("Unable to open spreadsheet")
        ZipInputStream(BufferedInputStream(raw, 32 * 1024)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && scannedChars < MAX_XLSX_SCAN_CHARS && outputChars < maxChars) {
                val name = entry.name.lowercase()
                val searchable = !entry.isDirectory &&
                    (name.endsWith(".xml") || name.endsWith(".rels") || name.endsWith(".txt"))
                if (searchable) {
                    val reader = InputStreamReader(zip, Charsets.UTF_8)
                    val buffer = CharArray(STREAM_BUFFER_CHARS)
                    var carry = ""
                    while (scannedChars < MAX_XLSX_SCAN_CHARS && outputChars < maxChars) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        scannedChars += n
                        val chunk = carry + String(buffer, 0, n)
                        outputChars += collectInviteLinks(chunk, found, maxChars - outputChars)
                        carry = chunk.takeLast(REGEX_CARRY_CHARS)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return found.joinToString("\n").take(maxChars)
    }

    private fun readLegacySpreadsheetLinks(
        resolver: ContentResolver,
        uri: Uri,
        maxChars: Int
    ): String {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(64 * 1024)
            val buffer = ByteArray(16 * 1024)
            var remaining = MAX_LEGACY_XLS_BYTES
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (n < 0) break
                output.write(buffer, 0, n)
                remaining -= n
            }
            output.toByteArray()
        } ?: error("Unable to open spreadsheet")

        val found = LinkedHashSet<String>()
        var outputChars = 0
        for (charset in listOf(Charsets.ISO_8859_1, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            if (outputChars >= maxChars) break
            outputChars += collectInviteLinks(bytes.toString(charset), found, maxChars - outputChars)
        }
        return found.joinToString("\n").take(maxChars)
    }

    private fun collectInviteLinks(
        text: String,
        output: LinkedHashSet<String>,
        remainingChars: Int
    ): Int {
        if (remainingChars <= 0) return 0
        var added = 0
        for (match in WHATSAPP_INVITE_REGEX.findAll(text)) {
            val link = match.value
            if (link.length + added > remainingChars) break
            if (output.add(link)) added += link.length + 1
        }
        return added
    }

    private fun detectCharset(prefix: ByteArray, count: Int): Pair<Charset, Int> = when {
        count >= 3 && prefix[0] == 0xEF.toByte() &&
            prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        count >= 2 && prefix[0] == 0xFF.toByte() &&
            prefix[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        count >= 2 && prefix[0] == 0xFE.toByte() &&
            prefix[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> Charsets.UTF_8 to 0
    }
}
