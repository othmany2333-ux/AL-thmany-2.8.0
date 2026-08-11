package com.althmany.groupmanager.domain

import com.althmany.groupmanager.model.ParseReport
import com.althmany.groupmanager.model.ParsedGroupLink

/**
 * Deterministic, offline link analyzer. It does not contact WhatsApp and does not claim to
 * validate whether an invitation is still active; it validates syntax and normalizes input.
 */
object WhatsAppLinkParser {
    const val MAX_LINKS_PER_SESSION = AutomationPolicy.MAX_LINKS_PER_SESSION
    const val MAX_INPUT_CHARS = 16_000_000

    private val domainMentionRegex = Regex(
        pattern = "(?i)(?<![A-Za-z0-9.-])(?:www\\.)?chat\\.whatsapp\\.com"
    )

    // Domain matching is case-insensitive, while the invitation code is preserved exactly.
    private val linkRegex = Regex(
        pattern = "(?<![A-Za-z0-9.-])(?:https?://)?(?i:(?:www\\.)?chat\\.whatsapp\\.com)/([A-Za-z0-9_-]{6,128})(?:[/?#][^\\s<>\\\"']*)?"
    )

    fun extract(rawInput: String): ParseReport {
        val inputTruncated = rawInput.length > MAX_INPUT_CHARS
        val (input, normalizationCount) = normalize(rawInput.take(MAX_INPUT_CHARS))

        val unique = LinkedHashMap<String, ParsedGroupLink>()
        var matchesCount = 0
        var duplicates = 0

        linkRegex.findAll(input).forEach { match ->
            matchesCount += 1
            val code = match.groupValues.getOrNull(1).orEmpty()
            if (code.isBlank()) return@forEach

            val canonical = "https://chat.whatsapp.com/$code"
            if (unique.putIfAbsent(
                    canonical,
                    ParsedGroupLink(canonicalUrl = canonical, inviteCode = code)
                ) != null
            ) {
                duplicates += 1
            }
        }

        val totalDomainMentions = domainMentionRegex.findAll(input).count()
        val invalid = (totalDomainMentions - matchesCount).coerceAtLeast(0)
        val uniqueValues = unique.values.toList()
        val accepted = uniqueValues.take(MAX_LINKS_PER_SESSION)
        val ignored = (uniqueValues.size - MAX_LINKS_PER_SESSION).coerceAtLeast(0)

        return ParseReport(
            accepted = accepted,
            totalCandidates = totalDomainMentions,
            totalUniqueValid = uniqueValues.size,
            duplicateCount = duplicates,
            invalidCount = invalid,
            ignoredBecauseOfLimit = ignored,
            normalizationCount = normalizationCount,
            inputTruncated = inputTruncated,
            qualityScore = calculateQualityScore(
                totalCandidates = totalDomainMentions,
                validUnique = uniqueValues.size,
                duplicates = duplicates,
                invalid = invalid,
                truncated = inputTruncated
            )
        )
    }

    private fun normalize(value: String): Pair<String, Int> {
        var changes = 0
        val output = StringBuilder(value.length)
        value.forEach { char ->
            when (char) {
                '\u00A0' -> {
                    output.append(' ')
                    changes += 1
                }
                '\uFF1A' -> {
                    output.append(':')
                    changes += 1
                }
                '\uFF0F' -> {
                    output.append('/')
                    changes += 1
                }
                '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
                '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
                '\u2060', '\u2066', '\u2067', '\u2068', '\u2069', '\uFEFF' -> changes += 1
                else -> output.append(char)
            }
        }
        return output.toString().replace("&amp;", "&").let { normalized ->
            normalized to (changes + if (normalized.length != output.length) 1 else 0)
        }
    }

    private fun calculateQualityScore(
        totalCandidates: Int,
        validUnique: Int,
        duplicates: Int,
        invalid: Int,
        truncated: Boolean
    ): Int {
        if (totalCandidates == 0) return 0
        val validRatio = (validUnique * 100.0 / totalCandidates).toInt()
        val duplicatePenalty = (duplicates * 4).coerceAtMost(20)
        val invalidPenalty = (invalid * 12).coerceAtMost(48)
        val truncationPenalty = if (truncated) 10 else 0
        return (validRatio - duplicatePenalty - invalidPenalty - truncationPenalty).coerceIn(0, 100)
    }
}
