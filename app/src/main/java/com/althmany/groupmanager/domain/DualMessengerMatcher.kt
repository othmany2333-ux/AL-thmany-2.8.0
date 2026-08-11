package com.althmany.groupmanager.domain

import java.util.Locale

/** Text matcher for Samsung/Android resolver entries used by Dual Messenger mode. */
object DualMessengerMatcher {
    fun isWhatsApp(label: CharSequence?): Boolean {
        val value = normalize(label)
        return value.contains("whatsapp") || value.contains("واتساب")
    }

    fun isExplicitDualMessenger(label: CharSequence?): Boolean {
        val value = normalize(label)
        return isWhatsApp(value) && (
            value.contains("dual messenger") ||
                value.contains("المراسل المزدوج") ||
                value.contains("واتساب المزدوج") ||
                value.contains("نسخة واتساب") ||
                value.contains("cloned whatsapp") ||
                value.contains("secondary whatsapp")
            )
    }

    private fun normalize(label: CharSequence?): String = label
        ?.toString()
        ?.trim()
        ?.replace("ـ", "")
        ?.replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        ?.replace(Regex("\\s+"), " ")
        ?.lowercase(Locale.ROOT)
        .orEmpty()
}
