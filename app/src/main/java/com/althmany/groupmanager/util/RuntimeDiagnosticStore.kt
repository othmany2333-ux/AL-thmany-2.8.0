package com.althmany.groupmanager.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small rotating diagnostic journal for runtime decisions. It never stores message contents. */
object RuntimeDiagnosticStore {
    private const val FILE_NAME = "runtime-diagnostics.log"
    private const val BACKUP_NAME = "runtime-diagnostics.prev.log"
    private const val MAX_BYTES = 256 * 1024L
    private val lock = Any()

    fun append(context: Context, category: String, detail: String) {
        synchronized(lock) {
            val dir = context.filesDir ?: return
            val file = File(dir, FILE_NAME)
            rotateIfNeeded(file, File(dir, BACKUP_NAME))
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
            val safeCategory = sanitize(category, 40)
            val safeDetail = sanitize(detail, 420)
            file.appendText("$timestamp | $safeCategory | $safeDetail\n", Charsets.UTF_8)
        }
    }

    fun readRecent(context: Context, maxChars: Int = 12_000): String = synchronized(lock) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@synchronized ""
        val text = file.readText(Charsets.UTF_8)
        if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun clear(context: Context) {
        synchronized(lock) {
            File(context.filesDir, FILE_NAME).delete()
            File(context.filesDir, BACKUP_NAME).delete()
        }
    }

    private fun rotateIfNeeded(file: File, backup: File) {
        if (!file.exists() || file.length() < MAX_BYTES) return
        backup.delete()
        file.renameTo(backup)
    }

    private fun sanitize(value: String, max: Int): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(max)
}
