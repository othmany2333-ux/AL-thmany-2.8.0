package com.althmany.groupmanager.domain

import com.althmany.groupmanager.model.GroupLink
import com.althmany.groupmanager.model.LinkStatus
import com.althmany.groupmanager.model.SessionStats

object SessionRules {
    fun currentOpened(links: List<GroupLink>): GroupLink? =
        links.firstOrNull { it.status == LinkStatus.OPENED }

    fun nextActionable(links: List<GroupLink>): GroupLink? =
        currentOpened(links)
            ?: links.firstOrNull { it.status == LinkStatus.PENDING }

    fun stats(links: List<GroupLink>): SessionStats {
        val joined = links.count {
            it.status == LinkStatus.JOINED &&
                it.resultCode in setOf(
                    com.althmany.groupmanager.model.LinkResultCode.JOIN_ACTION_COMPLETED,
                    com.althmany.groupmanager.model.LinkResultCode.MANUAL_JOINED
                )
        }
        val requested = links.count {
            it.status == LinkStatus.REQUESTED &&
                it.resultCode == com.althmany.groupmanager.model.LinkResultCode.REQUEST_SENT
        }
        val alreadyMember = links.count {
            it.status == LinkStatus.JOINED &&
                it.resultCode == com.althmany.groupmanager.model.LinkResultCode.ALREADY_MEMBER
        }
        val skipped = links.count { it.status == LinkStatus.SKIPPED } + alreadyMember
        val unverifiedTerminal = links.count {
            (it.status == LinkStatus.JOINED &&
                it.resultCode !in setOf(
                    com.althmany.groupmanager.model.LinkResultCode.JOIN_ACTION_COMPLETED,
                    com.althmany.groupmanager.model.LinkResultCode.MANUAL_JOINED,
                    com.althmany.groupmanager.model.LinkResultCode.ALREADY_MEMBER
                )) ||
                (it.status == LinkStatus.REQUESTED &&
                    it.resultCode != com.althmany.groupmanager.model.LinkResultCode.REQUEST_SENT)
        }
        return SessionStats(
            total = links.size,
            pending = links.count { it.status == LinkStatus.PENDING },
            opened = links.count { it.status == LinkStatus.OPENED },
            joined = joined,
            requested = requested,
            skipped = skipped,
            failed = links.count { it.status == LinkStatus.FAILED } + unverifiedTerminal
        )
    }
}
