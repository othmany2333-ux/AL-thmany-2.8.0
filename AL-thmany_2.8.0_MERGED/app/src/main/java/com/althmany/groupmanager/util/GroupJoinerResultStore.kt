package com.althmany.groupmanager.util

import android.content.Context
import com.althmany.groupmanager.model.LinkStatus
import com.althmany.groupmanager.model.SessionSnapshot
import java.io.File

/** Mirrors the observable Group Joiner output model: Joined / Fail / Left. */
object GroupJoinerResultStore {
    private const val ROOT = "Group Joiner"

    fun sync(context: Context, snapshot: SessionSnapshot?) {
        if (snapshot == null) return
        runCatching {
            val root = File(context.filesDir, ROOT).apply { mkdirs() }
            val joinedDir = File(root, "Joined").apply { mkdirs() }
            val failDir = File(root, "Fail").apply { mkdirs() }
            val leftDir = File(root, "Left").apply { mkdirs() }
            val name = "${snapshot.sessionId}.txt"

            val joined = snapshot.links.filter {
                it.status == LinkStatus.JOINED || it.status == LinkStatus.REQUESTED
            }
            val failed = snapshot.links.filter {
                it.status == LinkStatus.FAILED || it.status == LinkStatus.SKIPPED
            }
            val left = snapshot.links.filter {
                it.status == LinkStatus.PENDING || it.status == LinkStatus.OPENED
            }

            File(joinedDir, name).writeText(joined.joinToString("\n") { it.url })
            File(failDir, name).writeText(failed.joinToString("\n") { it.url })
            File(leftDir, name).writeText(left.joinToString("\n") { it.url })
        }
    }
}
