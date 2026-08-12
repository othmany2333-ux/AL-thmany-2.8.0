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

            File(joinedDir, name).bufferedWriter(Charsets.UTF_8).use { joined ->
                File(failDir, name).bufferedWriter(Charsets.UTF_8).use { failed ->
                    File(leftDir, name).bufferedWriter(Charsets.UTF_8).use { left ->
                        var joinedFirst = true
                        var failedFirst = true
                        var leftFirst = true
                        snapshot.links.forEach { link ->
                            when (link.status) {
                                LinkStatus.JOINED, LinkStatus.REQUESTED -> {
                                    if (!joinedFirst) joined.newLine()
                                    joined.write(link.url)
                                    joinedFirst = false
                                }
                                LinkStatus.FAILED, LinkStatus.SKIPPED -> {
                                    if (!failedFirst) failed.newLine()
                                    failed.write(link.url)
                                    failedFirst = false
                                }
                                LinkStatus.PENDING, LinkStatus.OPENED -> {
                                    if (!leftFirst) left.newLine()
                                    left.write(link.url)
                                    leftFirst = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
