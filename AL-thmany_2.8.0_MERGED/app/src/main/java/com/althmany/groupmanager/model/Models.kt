package com.althmany.groupmanager.model

enum class LinkStatus {
    PENDING,
    OPENED,
    JOINED,
    REQUESTED,
    SKIPPED,
    FAILED
}

enum class LinkResultCode {
    JOIN_ACTION_COMPLETED,
    REQUEST_SENT,
    ALREADY_MEMBER,
    GROUP_FULL,
    INVALID_OR_EXPIRED,
    REMOVED_OR_BANNED,
    WHATSAPP_REJECTED,
    RESTRICTED,
    OPEN_FAILED,
    BROWSER_FALLBACK,
    UNKNOWN_SCREEN,
    ACTION_TIMEOUT,
    USER_SKIPPED,
    MANUAL_JOINED
}

enum class LinkSource {
    PASTE,
    CLIPBOARD,
    FILE,
    SHARE
}

enum class SessionStatus {
    ACTIVE,
    COMPLETED,
    ABANDONED
}

enum class PreferredTarget {
    AUTO,
    PERSONAL,
    BUSINESS,
    CLONED,
    BROWSER
}

enum class AutomationBackend {
    AUTO,
    ACCESSIBILITY,
    SHIZUKU
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class ParsedGroupLink(
    val canonicalUrl: String,
    val inviteCode: String
)

data class ParseReport(
    val accepted: List<ParsedGroupLink>,
    val totalCandidates: Int,
    val totalUniqueValid: Int,
    val duplicateCount: Int,
    val invalidCount: Int,
    val ignoredBecauseOfLimit: Int,
    val normalizationCount: Int,
    val inputTruncated: Boolean,
    val qualityScore: Int
)

data class GroupLink(
    val id: Long,
    val sessionId: String,
    val position: Int,
    val url: String,
    val inviteCode: String,
    val status: LinkStatus,
    val source: LinkSource,
    val importedAt: Long,
    val openedAt: Long?,
    val completedAt: Long?,
    val openAttempts: Int,
    val resultCode: LinkResultCode?,
    val resultDetail: String?
)

data class SessionSummary(
    val id: String,
    val createdAt: Long,
    val completedAt: Long?,
    val sourceLabel: String,
    val totalCount: Int,
    val joinedCount: Int,
    val requestedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val status: SessionStatus,
    val isActive: Boolean
)

data class SessionStats(
    val total: Int,
    val pending: Int,
    val opened: Int,
    val joined: Int,
    val requested: Int,
    val skipped: Int,
    val failed: Int
) {
    val completed: Int get() = joined + requested + skipped + failed
    val remaining: Int get() = pending + opened
    val progressPercent: Int
        get() = if (total == 0) 0 else ((completed * 100f) / total).toInt().coerceIn(0, 100)
    val isComplete: Boolean get() = total > 0 && remaining == 0
}

data class SessionSnapshot(
    val sessionId: String,
    val links: List<GroupLink>,
    val stats: SessionStats,
    val canUndoLastResult: Boolean
)
