package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppLinkParserTest {
    @Test
    fun extractsNormalizesAndDeduplicatesLinks() {
        val report = WhatsAppLinkParser.extract(
            """
            chat.whatsapp.com/AbC_123456
            https://chat.whatsapp.com/AbC_123456?mode=invite
            http://www.chat.whatsapp.com/Second-Code_99
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://chat.whatsapp.com/AbC_123456",
                "https://chat.whatsapp.com/Second-Code_99"
            ),
            report.accepted.map { it.canonicalUrl }
        )
        assertEquals(1, report.duplicateCount)
        assertEquals(2, report.totalUniqueValid)
    }

    @Test
    fun acceptsLargeQueueWithinConfiguredMaximum() {
        val total = 5_004
        val input = (1..total).joinToString("\n") {
            "https://chat.whatsapp.com/InviteCode$it"
        }

        val report = WhatsAppLinkParser.extract(input)

        assertEquals(total, report.accepted.size)
        assertEquals(total, report.totalUniqueValid)
        assertEquals(0, report.ignoredBecauseOfLimit)
    }

    @Test
    fun countsMalformedDomainMentions() {
        val report = WhatsAppLinkParser.extract(
            "chat.whatsapp.com/x and chat.whatsapp.com/ValidCode123"
        )

        assertEquals(1, report.accepted.size)
        assertEquals(1, report.invalidCount)
    }

    @Test
    fun rejectsLookalikeSubdomains() {
        val report = WhatsAppLinkParser.extract(
            "https://evilchat.whatsapp.com/ValidCode123 and https://chat.whatsapp.com.evil.test/ValidCode456"
        )

        assertTrue(report.accepted.isEmpty())
    }

    @Test
    fun ignoresNonGroupLinks() {
        val report = WhatsAppLinkParser.extract(
            "https://wa.me/123456 and https://example.com/chat.whatsapp.com"
        )

        assertTrue(report.accepted.isEmpty())
    }
    @Test
    fun repairsHiddenAndFullWidthCharacters() {
        val report = WhatsAppLinkParser.extract(
            "https：／／chat.whatsapp.com/AbC​123456"
        )

        assertEquals("https://chat.whatsapp.com/AbC123456", report.accepted.single().canonicalUrl)
        assertTrue(report.normalizationCount >= 3)
        assertTrue(report.qualityScore > 0)
    }

    @Test
    fun reportsInputTruncation() {
        val report = WhatsAppLinkParser.extract("x".repeat(WhatsAppLinkParser.MAX_INPUT_CHARS + 10))
        assertTrue(report.inputTruncated)
    }

}
