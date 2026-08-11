package com.althmany.groupmanager.data

import com.althmany.groupmanager.model.GroupLink
import com.althmany.groupmanager.model.LinkResultCode
import com.althmany.groupmanager.model.LinkSource
import com.althmany.groupmanager.model.LinkStatus
import com.althmany.groupmanager.model.ParseReport
import com.althmany.groupmanager.model.SessionSnapshot
import com.althmany.groupmanager.model.SessionSummary

class GroupLinkRepository(
    private val database: GroupLinkDatabase,
    private val preferences: AppPreferences
) {
    fun loadActiveSnapshot(): SessionSnapshot? {
        val activeId = preferences.activeSessionId ?: return null
        val snapshot = database.loadSnapshot(activeId)
        if (snapshot == null) preferences.activeSessionId = null
        return snapshot
    }

    fun loadActiveDashboardSnapshot(windowSize: Int = 120): SessionSnapshot? {
        val activeId = preferences.activeSessionId ?: return null
        val snapshot = database.loadDashboardSnapshot(activeId, windowSize)
        if (snapshot == null) preferences.activeSessionId = null
        return snapshot
    }

    fun createSession(
        report: ParseReport,
        source: LinkSource,
        sourceLabel: String
    ): SessionSnapshot {
        require(report.accepted.isNotEmpty())
        val previousSessionId = preferences.activeSessionId
        val sessionId = database.createSession(report.accepted, source, sourceLabel)
        previousSessionId?.let(database::markSessionAbandoned)
        preferences.activeSessionId = sessionId
        preferences.resetAccessibilityRun(sessionId)
        return checkNotNull(database.loadDashboardSnapshot(sessionId))
    }

    fun markOpened(linkId: Long): GroupLink? = database.markOpened(linkId)

    /** Lightweight runtime methods for the Accessibility engine. */
    fun loadAutomationCurrent(sessionId: String): GroupLink? {
        if (preferences.activeSessionId != sessionId) return null
        return database.loadCurrentOpened(sessionId)
    }

    fun loadAutomationNext(sessionId: String): GroupLink? {
        if (preferences.activeSessionId != sessionId) return null
        return database.loadNextActionable(sessionId)
    }

    fun automationSessionTotal(sessionId: String): Int? {
        if (preferences.activeSessionId != sessionId) return null
        return database.sessionTotalCount(sessionId)
    }

    fun isAutomationSessionComplete(sessionId: String): Boolean? {
        if (preferences.activeSessionId != sessionId) return null
        return database.isSessionComplete(sessionId)
    }

    fun markStatus(
        linkId: Long,
        status: LinkStatus,
        resultCode: LinkResultCode? = null,
        resultDetail: String? = null
    ): Boolean = database.markStatus(linkId, status, resultCode, resultDetail)

    fun undoLastResult(): Boolean {
        val sessionId = preferences.activeSessionId ?: return false
        return database.undoLastResult(sessionId)
    }

    fun deleteLink(linkId: Long): Boolean = database.deleteLink(linkId)

    fun clearCurrentSession() {
        preferences.activeSessionId?.let(database::deleteSession)
        preferences.activeSessionId = null
        preferences.accessibilityQuickJoin = false
        preferences.resetAccessibilityRun(null)
    }

    fun history(limit: Int = 30): List<SessionSummary> =
        database.listSessionSummaries(preferences.activeSessionId, limit)

    fun deleteHistoricalSession(sessionId: String): Boolean {
        if (sessionId == preferences.activeSessionId) return false
        return database.deleteSession(sessionId)
    }

    fun clearHistory() = database.clearHistory(preferences.activeSessionId)

    val autoAdvance: Boolean
        get() = preferences.autoAdvance

    fun setAutoAdvance(value: Boolean) {
        preferences.autoAdvance = value
    }
}
