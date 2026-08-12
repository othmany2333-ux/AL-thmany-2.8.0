package com.althmany.groupmanager.shizuku

import android.graphics.BitmapFactory
import android.os.Process
import com.althmany.groupmanager.domain.VisualActionButtonPolicy
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

    override fun fastFindWidePositiveAction(): String {
        val persistent = fastUi.findWidePositiveAction()
        val persistentState = persistent
            .substringAfter("__AL_VISUAL_ACTION__=", "ERROR")
            .substringBefore(';')
            .uppercase()

        // A working persistent screenshot remains the preferred path. Only bridge/screenshot
        // unavailability falls back to shell screencap; NOT_FOUND is a valid visual answer and
        // must not be converted into a less-specific second guess.
        if (persistentState !in setOf("UNAVAILABLE", "NO_SCREENSHOT", "ERROR")) {
            return persistent
        }
        return shellScreenshotPositiveAction(persistent.take(180))
    }

    /**
     * Screenshot-only rescue for devices where the hidden persistent UiAutomation connection is
     * unavailable. Processing stays inside the Shizuku shell UserService so PNG bytes never cross
     * Binder. The existing VisualActionButtonPolicy accepts only a wide WhatsApp-green control;
     * no fixed tap coordinate and no WhatsApp restriction bypass is introduced.
     */
    private fun shellScreenshotPositiveAction(persistentDetail: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/screencap", "-p").start()
            val captured = ByteArrayOutputStream(256 * 1024)
            val errors = ByteArrayOutputStream(2 * 1024)

            val reader = thread(name = "althmany-screencap-output", isDaemon = true) {
                val buffer = ByteArray(16 * 1024)
                process.inputStream.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        val remaining = MAX_SCREENSHOT_BYTES - captured.size()
                        if (remaining > 0) captured.write(buffer, 0, minOf(count, remaining))
                        // Keep draining after the safety cap so screencap cannot block on stdout.
                    }
                }
            }
            val errorReader = thread(name = "althmany-screencap-error", isDaemon = true) {
                val buffer = ByteArray(2 * 1024)
                process.errorStream.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        val remaining = MAX_SCREENSHOT_ERROR_BYTES - errors.size()
                        if (remaining > 0) errors.write(buffer, 0, minOf(count, remaining))
                    }
                }
            }

            val finished = process.waitFor(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(150, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            reader.join(500)
            errorReader.join(300)

            if (!finished) {
                return visualActionMarker(
                    "UNAVAILABLE",
                    "source=SHELL_SCREENCAP;timeout=true;persistent=${persistentDetail.replace(';', ',')}"
                )
            }
            if (process.exitValue() != 0) {
                return visualActionMarker(
                    "UNAVAILABLE",
                    "source=SHELL_SCREENCAP;exit=${process.exitValue()};error=${errors.toString(Charsets.UTF_8.name()).take(120)}"
                )
            }

            val bytes = captured.toByteArray()
            if (bytes.isEmpty() || bytes.size >= MAX_SCREENSHOT_BYTES) {
                return visualActionMarker(
                    "UNAVAILABLE",
                    "source=SHELL_SCREENCAP;bytes=${bytes.size};invalidOrCapped=true"
                )
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return visualActionMarker(
                    "UNAVAILABLE",
                    "source=SHELL_SCREENCAP;decode=null;bytes=${bytes.size}"
                )
            try {
                val bounds = VisualActionButtonPolicy.findWidePositiveAction(
                    width = bitmap.width,
                    height = bitmap.height,
                    pixelAt = bitmap::getPixel
                ) ?: return visualActionMarker(
                    "NOT_FOUND",
                    "source=SHELL_SCREENCAP;image=${bitmap.width}x${bitmap.height}"
                )
                visualActionMarker(
                    "OK",
                    "bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom};" +
                        "image=${bitmap.width}x${bitmap.height};source=SHELL_SCREENCAP"
                )
            } finally {
                runCatching { bitmap.recycle() }
            }
        } catch (t: Throwable) {
            visualActionMarker(
                "UNAVAILABLE",
                "source=SHELL_SCREENCAP;${t.javaClass.simpleName}:${t.message.orEmpty().take(120)}"
            )
        }
    }

    private fun visualActionMarker(state: String, detail: String): String =
        "__AL_VISUAL_ACTION__=$state;$detail"

    override fun fastEventSequence(targetPackage: String): Long = fastUi.eventSequence(targetPackage)

    override fun waitForFastEvent(targetPackage: String, afterSequence: Long, timeoutMs: Int): Long =
        fastUi.waitForEvent(targetPackage, afterSequence, timeoutMs)

    override fun waitAndSnapshot(targetPackage: String, afterSequence: Long, timeoutMs: Int, maxNodes: Int): String =
        fastUi.waitAndSnapshot(targetPackage, afterSequence, timeoutMs, maxNodes)

    override fun fastUiStatus(): String = fastUi.status()

    override fun fastResetUiAutomation(): Boolean = fastUi.resetForNewRun()

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
        const val MAX_SCREENSHOT_BYTES = 16 * 1024 * 1024
        const val MAX_SCREENSHOT_ERROR_BYTES = 8 * 1024
        const val SCREENSHOT_TIMEOUT_MS = 2_500L
    }
}
