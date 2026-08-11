package com.althmany.groupmanager.domain

import com.althmany.groupmanager.model.GroupLink
import com.althmany.groupmanager.model.LinkSource
import com.althmany.groupmanager.model.LinkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRulesTest {
    @Test
    fun openedLinkHasPriorityOverPendingAndFailed() {
        val links = listOf(
            link(1, LinkStatus.FAILED),
            link(2, LinkStatus.PENDING),
            link(3, LinkStatus.OPENED)
        )

        assertEquals(3L, SessionRules.nextActionable(links)?.id)
    }

    @Test
    fun pendingHasPriorityOverFailed() {
        val links = listOf(
            link(1, LinkStatus.FAILED),
            link(2, LinkStatus.PENDING)
        )

        assertEquals(2L, SessionRules.nextActionable(links)?.id)
    }

    @Test
    fun completedSessionHasNoActionableLink() {
        val links = listOf(
            link(1, LinkStatus.JOINED),
            link(2, LinkStatus.REQUESTED),
            link(3, LinkStatus.SKIPPED)
        )

        assertNull(SessionRules.nextActionable(links))
        assertEquals(true, SessionRules.stats(links).isComplete)
        assertEquals(100, SessionRules.stats(links).progressPercent)
    }


    @Test
    fun failedLinkIsTerminalForAutomaticRunButCanBeReopenedManually() {
        val links = listOf(link(1, LinkStatus.FAILED))

        assertNull(SessionRules.nextActionable(links))
        assertEquals(true, SessionRules.stats(links).isComplete)
        assertEquals(100, SessionRules.stats(links).progressPercent)
    }

    private fun link(id: Long, status: LinkStatus) = GroupLink(
        id = id,
        sessionId = "session",
        position = id.toInt() - 1,
        url = "https://chat.whatsapp.com/Code$id",
        inviteCode = "Code$id",
        status = status,
        source = LinkSource.PASTE,
        importedAt = 1L,
        openedAt = null,
        completedAt = null,
        openAttempts = 0,
        resultCode = null,
        resultDetail = null
    )
}
