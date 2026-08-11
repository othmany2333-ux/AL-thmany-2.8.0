package com.althmany.groupmanager.domain

/**
 * Pure exact-user proof for the inexpensive post-Join ActivityManager shortcut.
 *
 * A package match by itself is intentionally insufficient because the same WhatsApp package may
 * exist in Personal, Work and Secure Folder. The resumed line must contain the expected Android
 * user token and a known conversation activity hint. Unknown/obfuscated activities fall back to
 * the semantic UI hierarchy path instead of being guessed.
 */
object ShizukuActivityProofPolicy {
    private val conversationActivityHints = setOf(
        ".Conversation",
        "ConversationActivity",
        "GroupConversation"
    )

    fun findJoinedConversationProof(
        resumedActivityLines: Sequence<String>,
        targetPackage: String,
        androidUserId: Int
    ): String? {
        if (targetPackage.isBlank() || androidUserId < 0) return null
        val userPattern = Regex("(^|[^A-Za-z0-9_])u$androidUserId([^0-9]|$)")
        return resumedActivityLines.firstOrNull { line ->
            containsExactPackage(line, targetPackage) &&
                userPattern.containsMatchIn(line) &&
                conversationActivityHints.any { line.contains(it, ignoreCase = true) }
        }
    }

    fun containsExactPackage(value: String, targetPackage: String): Boolean {
        if (targetPackage.isBlank()) return false
        return Regex(
            "(^|[^A-Za-z0-9_.])" + Regex.escape(targetPackage) + "([^A-Za-z0-9_.]|$)"
        ).containsMatchIn(value)
    }
}
