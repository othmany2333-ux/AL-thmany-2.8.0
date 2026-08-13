package com.althmany.groupmanager.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import com.althmany.groupmanager.BuildConfig
import com.althmany.groupmanager.domain.ShizukuBounds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Runtime bridge to the official Shizuku API. */
object ShizukuBridge {
    const val PERMISSION_REQUEST_CODE = 9202

    data class Status(
        val binderAlive: Boolean,
        val permissionGranted: Boolean,
        val serverUid: Int?,
        val userServiceBound: Boolean
    ) {
        val ready: Boolean get() = binderAlive && permissionGranted
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String
    ) {
        val success: Boolean get() = exitCode == 0
    }

    @Volatile private var remote: IShizukuShellService? = null
    @Volatile private var binding: CompletableDeferred<Boolean>? = null
    private val connectionLock = Any()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val candidate = binder?.takeIf { it.pingBinder() }?.let(IShizukuShellService.Stub::asInterface)
            remote = candidate
            synchronized(connectionLock) {
                binding?.complete(candidate != null)
                binding = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    fun status(): Status {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = alive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val uid = if (alive) runCatching { Shizuku.getUid() }.getOrNull() else null
        return Status(alive, granted, uid, remote?.asBinder()?.pingBinder() == true)
    }

    fun requestPermission(): Boolean {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return false
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) ==
            PackageManager.PERMISSION_GRANTED
        ) return true
        return runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) return false
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    suspend fun ensureBound(context: Context, timeoutMs: Long = 4_500L): Boolean {
        val current = remote
        if (current != null && current.asBinder().pingBinder()) return true
        if (!status().ready) return false

        val deferred = synchronized(connectionLock) {
            val existing = binding
            if (existing != null) {
                existing
            } else {
                CompletableDeferred<Boolean>().also { created ->
                    binding = created
                    val args = userServiceArgs(context.applicationContext)
                    val started = runCatching {
                        Shizuku.bindUserService(args, serviceConnection)
                        true
                    }.getOrDefault(false)
                    if (!started) {
                        created.complete(false)
                        binding = null
                    }
                }
            }
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
    }

    data class FastUiResult(
        val state: String,
        val detail: String,
        val xml: String
    ) {
        val success: Boolean get() = state == "OK" && (xml.contains("<hierarchy") || xml.startsWith("__AL_FAST_COMPACT__="))
        val unavailable: Boolean get() = state == "UNAVAILABLE"
    }

    data class FastUiFrame(
        val sequence: Long,
        val eventTriggered: Boolean,
        val result: FastUiResult
    )

    data class VisualActionResult(
        val state: String,
        val detail: String,
        val bounds: ShizukuBounds?,
        val imageWidth: Int,
        val imageHeight: Int
    ) {
        val found: Boolean get() = state == "OK" && bounds?.valid == true && imageWidth > 0 && imageHeight > 0

        fun scaleTo(displayWidth: Int, displayHeight: Int): ShizukuBounds? {
            val source = bounds ?: return null
            if (!found || displayWidth <= 0 || displayHeight <= 0) return null
            return ShizukuBounds(
                left = source.left * displayWidth / imageWidth,
                top = source.top * displayHeight / imageHeight,
                right = source.right * displayWidth / imageWidth,
                bottom = source.bottom * displayHeight / imageHeight
            )
        }
    }

    suspend fun fastSnapshot(
        context: Context,
        targetPackage: String,
        maxNodes: Int = 1_200
    ): FastUiResult = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext FastUiResult("UNAVAILABLE", "user service unavailable", "")
        val raw = runCatching { remote?.fastSnapshot(targetPackage, maxNodes) }.getOrNull()
            ?: return@withContext FastUiResult("UNAVAILABLE", "user service disconnected", "")
        parseFastUi(raw)
    }

    suspend fun fastTap(context: Context, x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext false
        runCatching { remote?.fastTap(x, y) == true }.getOrDefault(false)
    }

    suspend fun fastClickNode(context: Context, targetPackage: String, x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext false
        runCatching { remote?.fastClickNode(targetPackage, x, y) == true }.getOrDefault(false)
    }

    suspend fun fastSwipe(
        context: Context,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int = 140
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext false
        runCatching { remote?.fastSwipe(startX, startY, endX, endY, durationMs) == true }.getOrDefault(false)
    }

    suspend fun fastBack(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext false
        runCatching { remote?.fastBack() == true }.getOrDefault(false)
    }

    suspend fun fastFindWidePositiveAction(context: Context): VisualActionResult = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) {
            return@withContext VisualActionResult("UNAVAILABLE", "user service unavailable", null, 0, 0)
        }
        val raw = runCatching { remote?.fastFindWidePositiveAction().orEmpty() }.getOrDefault("")
        parseVisualAction(raw)
    }

    suspend fun fastEventSequence(context: Context, targetPackage: String): Long = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext 0L
        runCatching { remote?.fastEventSequence(targetPackage) ?: 0L }.getOrDefault(0L)
    }

    suspend fun waitForFastEvent(
        context: Context,
        targetPackage: String,
        afterSequence: Long,
        timeoutMs: Int
    ): Long = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext afterSequence
        runCatching { remote?.waitForFastEvent(targetPackage, afterSequence, timeoutMs) ?: afterSequence }
            .getOrDefault(afterSequence)
    }

    suspend fun waitAndSnapshot(
        context: Context,
        targetPackage: String,
        afterSequence: Long,
        timeoutMs: Int,
        maxNodes: Int = 1_200
    ): FastUiFrame = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) {
            return@withContext FastUiFrame(afterSequence, false, FastUiResult("UNAVAILABLE", "user service unavailable", ""))
        }
        val raw = runCatching { remote?.waitAndSnapshot(targetPackage, afterSequence, timeoutMs, maxNodes) }.getOrNull()
            ?: return@withContext FastUiFrame(afterSequence, false, FastUiResult("UNAVAILABLE", "user service disconnected", ""))
        parseFastFrame(raw, afterSequence)
    }

    suspend fun fastUiStatus(context: Context): String = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext "UNAVAILABLE:user service unavailable"
        runCatching { remote?.fastUiStatus().orEmpty() }.getOrDefault("UNAVAILABLE:remote error")
    }

    suspend fun fastResetUiAutomation(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext false
        runCatching { remote?.fastResetUiAutomation() == true }.getOrDefault(false)
    }

    suspend fun restartUserService(
        context: Context,
        timeoutMs: Long = 4_500L
    ): Boolean = withContext(Dispatchers.IO) {
        if (!status().ready) return@withContext false
        val appContext = context.applicationContext
        val args = userServiceArgs(appContext)
        runCatching {
            Shizuku::class.java.methods
                .firstOrNull { it.name == "unbindUserService" && it.parameterTypes.size == 3 }
                ?.invoke(null, args, serviceConnection, true)
        }
        synchronized(connectionLock) {
            remote = null
            binding?.cancel()
            binding = null
        }
        delay(120L)
        ensureBound(appContext, timeoutMs)
    }

    suspend fun execute(
        context: Context,
        command: String,
        timeoutMs: Int = 6_000
    ): ShellResult = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext ShellResult(126, "Shizuku user service is unavailable")
        val value = runCatching { remote?.execute(command, timeoutMs) }
            .getOrNull()
            ?: return@withContext ShellResult(126, "Shizuku user service disconnected")
        parseResult(value)
    }

    suspend fun probe(context: Context, targetPackage: String? = null): String {
        val before = status()
        if (!before.binderAlive) return "binder=OFF"
        if (!before.permissionGranted) return "binder=ON; permission=DENIED; uid=${before.serverUid ?: -1}"
        if (!ensureBound(context, 4_000L)) return "binder=ON; permission=GRANTED; userService=FAILED"

        val id = execute(context, "id", 3_000)
        val serviceUid = runCatching { remote?.serviceUid() }.getOrNull()
        val display = execute(context, "wm size", 2_500)
        val safeTarget = targetPackage?.takeIf { PACKAGE_NAME.matches(it) }
        val processUid = Process.myUid()
        val appPackage = BuildConfig.APPLICATION_ID
        val profileProbe = if (safeTarget != null && PACKAGE_NAME.matches(appPackage)) {
            execute(
                context,
                "for u in \$(pm list users | sed -n 's/.*UserInfo{\\([0-9][0-9]*\\):.*/\\1/p'); do " +
                    "line=\$(pm list packages -U --user \$u ${shellQuote(appPackage)} 2>/dev/null | head -n1); " +
                    "echo \"\$line\" | grep -q ${shellQuote("package:$appPackage")} || continue; " +
                    "echo \"\$line\" | grep -Eq ${shellQuote("uid:${processUid}([^0-9]|$)")} || continue; " +
                    "if pm list packages --user \$u ${shellQuote(safeTarget)} 2>/dev/null | grep -q ${shellQuote("package:$safeTarget")}; then echo PROFILE_USER=\$u,TARGET_SAME_USER=YES; else echo PROFILE_USER=\$u,TARGET_SAME_USER=NO; fi; break; done",
                4_000
            ).output.trim().replace('\n', '/')
        } else "PROFILE_USER=UNKNOWN"
        val dumpCommand = buildString {
            append("uiautomator dump --compressed /data/local/tmp/althmany_probe.xml >/dev/null 2>&1; ")
            append("if test -s /data/local/tmp/althmany_probe.xml; then echo UI_DUMP_OK; ")
            if (safeTarget != null) {
                append("grep -q ")
                append(shellQuote("package=\"$safeTarget\""))
                append(" /data/local/tmp/althmany_probe.xml && echo TARGET_UI_VISIBLE || echo TARGET_UI_NOT_VISIBLE; ")
            }
            append("else echo UI_DUMP_EMPTY; fi; rm -f /data/local/tmp/althmany_probe.xml")
        }
        val dump = execute(context, dumpCommand, 6_000)
        val persistent = if (safeTarget != null) fastSnapshot(context, safeTarget, 160) else FastUiResult("SKIPPED", "no target", "")
        return "binder=ON; permission=GRANTED; serverUid=${before.serverUid ?: -1}; " +
            "serviceUid=${serviceUid ?: -1}; shell=${if (id.success) "OK" else "FAIL(${id.exitCode})"}; " +
            "profile=${profileProbe.take(80)}; " +
            "display=${display.output.lineSequence().lastOrNull { it.contains("size", true) }?.trim().orEmpty().take(40)}; " +
            "persistentUI=${persistent.state}:${persistent.detail.take(72)}; " +
            "ui=${dump.output.trim().replace('\n', '/').take(100)}"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context, ShizukuShellUserService::class.java))
            .daemon(false)
            .processNameSuffix("althmany_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private fun parseFastFrame(raw: String, fallbackSequence: Long): FastUiFrame {
        val first = raw.lineSequence().firstOrNull().orEmpty()
        if (!first.startsWith("__AL_FAST_FRAME__=")) {
            return FastUiFrame(fallbackSequence, false, FastUiResult("ERROR", first.take(180), ""))
        }
        val header = first.substringAfter("__AL_FAST_FRAME__=")
        val parts = header.split(';')
        val sequence = parts.firstOrNull()?.trim()?.toLongOrNull() ?: fallbackSequence
        val eventTriggered = parts.firstOrNull { it.startsWith("event=") }?.substringAfter('=') == "1"
        val state = parts.firstOrNull { it.startsWith("state=") }?.substringAfter('=')?.trim()?.uppercase() ?: "ERROR"
        val detail = parts.filterNot { it == parts.firstOrNull() || it.startsWith("event=") || it.startsWith("state=") }
            .joinToString(";")
            .trim()
        val xml = raw.substringAfter('\n', "")
        return FastUiFrame(sequence, eventTriggered, FastUiResult(state, detail, xml))
    }

    private fun parseFastUi(raw: String): FastUiResult {
        val first = raw.lineSequence().firstOrNull().orEmpty()
        if (!first.startsWith("__AL_FAST_UI__=")) return FastUiResult("ERROR", first.take(180), "")
        val header = first.substringAfter("__AL_FAST_UI__=")
        val state = header.substringBefore(';').trim().uppercase()
        val detail = header.substringAfter(';', "").trim()
        val xml = raw.substringAfter('\n', "")
        return FastUiResult(state, detail, xml)
    }

    private fun parseVisualAction(raw: String): VisualActionResult {
        if (!raw.startsWith("__AL_VISUAL_ACTION__=")) {
            return VisualActionResult("ERROR", raw.take(180), null, 0, 0)
        }
        val header = raw.substringAfter("__AL_VISUAL_ACTION__=")
        val state = header.substringBefore(';').trim().uppercase()
        val detail = header.substringAfter(';', "").trim()
        val boundsValues = VISUAL_BOUNDS.find(detail)?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)
        val imageValues = VISUAL_IMAGE.find(detail)?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)
        val bounds = if (boundsValues?.size == 4) {
            ShizukuBounds(boundsValues[0], boundsValues[1], boundsValues[2], boundsValues[3])
        } else null
        return VisualActionResult(
            state = state,
            detail = detail,
            bounds = bounds,
            imageWidth = imageValues?.getOrNull(0) ?: 0,
            imageHeight = imageValues?.getOrNull(1) ?: 0
        )
    }

    private fun parseResult(raw: String): ShellResult {
        val first = raw.lineSequence().firstOrNull().orEmpty()
        val code = first.substringAfter("__AL_EXIT__=", "125").toIntOrNull() ?: 125
        val output = raw.substringAfter('\n', "")
        return ShellResult(code, output)
    }

    private val VISUAL_BOUNDS = Regex("bounds=(\\d+),(\\d+),(\\d+),(\\d+)")
    private val VISUAL_IMAGE = Regex("image=(\\d+)x(\\d+)")
}
