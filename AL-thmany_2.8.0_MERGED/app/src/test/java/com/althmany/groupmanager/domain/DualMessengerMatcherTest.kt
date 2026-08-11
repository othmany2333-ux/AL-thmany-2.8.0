package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualMessengerMatcherTest {
    @Test
    fun recognizesSamsungDualMessengerLabels() {
        assertTrue(DualMessengerMatcher.isExplicitDualMessenger("WhatsApp — Dual Messenger"))
        assertTrue(DualMessengerMatcher.isExplicitDualMessenger("واتساب المزدوج عبر المراسل المزدوج"))
        assertTrue(DualMessengerMatcher.isWhatsApp("واتساب"))
    }

    @Test
    fun doesNotTreatPrimaryLabelAsExplicitDual() {
        assertFalse(DualMessengerMatcher.isExplicitDualMessenger("WhatsApp"))
        assertFalse(DualMessengerMatcher.isWhatsApp("Telegram"))
    }
}
