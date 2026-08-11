package com.althmany.groupmanager.shizuku

import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Code executed by Shizuku as shell/root. It intentionally exposes only a tiny command runner to
 * AL-thmany's own process. No network listener, exported Android service, or arbitrary external IPC
 * endpoint is created.
 */
class ShizukuShellUserService : IShizukuShellService.Stub() {
    private val fastUi = PersistentUiAutomationBridge()

    override fun serviceUid(): Int = Process.myUid()

    override fun fastSnapshot(targetPackage: String, maxNodes: Int): String =
        fastUi.snapshot(targetPackage, maxNodes)

    override fun fastTap(x: Int, y: Int): Boolean = fastUi.tap(x, y)

    override fun fastClickNode(targetPackage: String, x: Int, y: Int): Boolean =
        fastUi.clickNode(targetPackage, x, y)

    override fun fastSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean =
        fastUi.swipe(startX, startY, endX, endY, durationMs)

    override fun fastBack(): Boolean = fastUi.back()

    override fun fastFindWidePositiveAction(): String = fastUi.findWidePositiveAction()

    override fun fastEventSequence(targetPackage: String): Long = fastUi.eventSequence(targetPackage)

    override fun waitForFastEvent(targetPackage: String, afterSequence: Long, timeoutMs: Int): Long =
        fastUi.waitForEvent(targetPackage, afterSequence, timeoutMs)

    override fun waitAndSnapshot(targetPackage: String, afterSequence: Long, timeoutMs: Int, maxNodes: Int): String =
        fastUi.waitAndSnapshot(targetPackage, afterSequence, timeoutMs, maxNodes)

    override fun fastUiStatus(): String = fastUi.status()

    override fun execute(command: String, timeoutMs: Int): String {
        val safeTimeout = timeoutMs.coerceIn(500, 15_000)
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            // Drain stdout while the child is running. UIAutomator XML can be larger than the OS
            // pipe buffer; waiting before reading can deadlock a perfectly healthy dump command.
            val captured = ByteArrayOutputStream(minOf(MAX_BINDER_TEXT, 64 * 1024))
            val reader = thread(name = "althmany-shizuku-output", isDaemon = true) {
                val buffer = ByteArray(8 * 1024)
                process.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val remaining = MAX_BINDER_TEXT - captured.size()
                        if (remaining > 0) captured.write(buffer, 0, minOf(read, remaining))
                        // Continue draining after the cap so the child can never block on stdout.
                    }
                }
            }

            val finished = process.waitFor(safeTimeout.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            reader.join(700)

            val output = captured.toString(Charsets.UTF_8.name())
            if (!finished) {
                "__AL_EXIT__=124\n${output.take(MAX_BINDER_TEXT - 64)}\ncommand timed out"
            } else {
                "__AL_EXIT__=${process.exitValue()}\n$output".take(MAX_BINDER_TEXT)
            }
        } catch (t: Throwable) {
            "__AL_EXIT__=125\n${t.javaClass.simpleName}: ${t.message.orEmpty()}".take(MAX_BINDER_TEXT)
        }
    }

    private companion object {
        const val MAX_BINDER_TEXT = 700_000
    }
}
