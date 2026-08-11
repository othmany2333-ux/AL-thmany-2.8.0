package com.althmany.groupmanager.util

import com.althmany.groupmanager.domain.SessionRules
import com.althmany.groupmanager.model.GroupLink
import com.althmany.groupmanager.model.LinkResultCode
import com.althmany.groupmanager.model.LinkSource
import com.althmany.groupmanager.model.LinkStatus
import com.althmany.groupmanager.model.SessionSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReportFormatterTest {
    @Test
    fun csvEscapesValuesAndIncludesStatus() {
        val links = listOf(
            GroupLink(
                id = 1,
                sessionId = "s",
                position = 0,
                url = "https://chat.whatsapp.com/Code123456",
                inviteCode = "Code123456",
                status = LinkStatus.JOINED,
                source = LinkSource.FILE,
                importedAt = 1_000,
                openedAt = 2_000,
                completedAt = 3_000,
                openAttempts = 1,
                resultCode = LinkResultCode.JOIN_ACTION_COMPLETED,
                resultDetail = "verified"
            )
        )
        val snapshot = SessionSnapshot("s", links, SessionRules.stats(links), canUndoLastResult = false)

        val csv = SessionReportFormatter.toCsv(snapshot)

        assertTrue(csv.contains("position,status,result_code,result_detail,url"))
        assertTrue(csv.contains("JOINED"))
        assertTrue(csv.contains("JOIN_ACTION_COMPLETED"))
        assertTrue(csv.contains("https://chat.whatsapp.com/Code123456"))
    }
}
