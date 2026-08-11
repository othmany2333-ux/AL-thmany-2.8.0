package com.althmany.groupmanager.util

import com.althmany.groupmanager.model.GroupLink
import com.althmany.groupmanager.model.SessionSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object SessionReportFormatter {
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault())

    private val fileFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneId.systemDefault())

    fun suggestedFileName(now: Long = System.currentTimeMillis()): String =
        "whatsapp-group-session-${fileFormatter.format(Instant.ofEpochMilli(now))}.csv"

    fun toCsv(snapshot: SessionSnapshot): String = buildString {
        append('\uFEFF')
        appendLine("position,status,result_code,result_detail,url,open_attempts,imported_at,opened_at,completed_at")
        snapshot.links.forEach { link ->
            appendLine(
                listOf(
                    (link.position + 1).toString(),
                    link.status.name,
                    link.resultCode?.name.orEmpty(),
                    link.resultDetail.orEmpty(),
                    link.url,
                    link.openAttempts.toString(),
                    formatTime(link.importedAt),
                    formatNullableTime(link.openedAt),
                    formatNullableTime(link.completedAt)
                ).joinToString(",", transform = ::escapeCsv)
            )
        }
    }

    fun toShareText(snapshot: SessionSnapshot): String = buildString {
        appendLine("WhatsApp Group Link Manager")
        appendLine("Session: ${snapshot.sessionId}")
        appendLine("Total: ${snapshot.stats.total}")
        appendLine("Joined: ${snapshot.stats.joined}")
        appendLine("Requested: ${snapshot.stats.requested}")
        appendLine("Skipped: ${snapshot.stats.skipped}")
        appendLine("Failed: ${snapshot.stats.failed}")
        appendLine()
        snapshot.links.forEach { link ->
            val result = link.resultCode?.name?.let { " [$it]" }.orEmpty()
            val detail = link.resultDetail?.let { " — $it" }.orEmpty()
            appendLine("${link.position + 1}. ${link.status.name}$result — ${link.url}$detail")
        }
    }.trimEnd()

    private fun formatTime(value: Long): String =
        timestampFormatter.format(Instant.ofEpochMilli(value))

    private fun formatNullableTime(value: Long?): String = value?.let(::formatTime).orEmpty()

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '\n' || it == '\r' || it == '\"' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }
}
