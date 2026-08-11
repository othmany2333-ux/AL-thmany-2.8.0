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

    fun stats(links: List<GroupLink>): SessionStats = SessionStats(
        total = links.size,
        pending = links.count { it.status == LinkStatus.PENDING },
        opened = links.count { it.status == LinkStatus.OPENED },
        joined = links.count { it.status == LinkStatus.JOINED },
        requested = links.count { it.status == LinkStatus.REQUESTED },
        skipped = links.count { it.status == LinkStatus.SKIPPED },
        failed = links.count { it.status == LinkStatus.FAILED }
    )
}
