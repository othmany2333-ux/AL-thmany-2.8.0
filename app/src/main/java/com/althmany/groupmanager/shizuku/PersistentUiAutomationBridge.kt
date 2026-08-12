package com.althmany.groupmanager.shizuku

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.althmany.groupmanager.domain.ShizukuFastUiPolicy
import com.althmany.groupmanager.domain.VisualActionButtonPolicy

/**
 * Optional persistent UiAutomation session used by the Shizuku UserService.
 *
 * Android's `uiautomator dump` command creates a new command process and a new automation connection
 * for every snapshot. That is reliable but much slower than an AccessibilityService event/tree
 * lookup. This bridge attempts to keep one shell-owned UiAutomation connection alive and reads the
 * active AccessibilityNodeInfo tree directly. If the platform blocks the hidden connection
 * constructor/connect entry point, callers get UNAVAILABLE and transparently fall back to the
 * command-based dump path. No Knox/DPC boundary is bypassed: only the UI exposed to shell
 * UiAutomation is returned.
 */
internal class PersistentUiAutomationBridge {
    @Volatile private var automation: UiAutomation? = null
    @Volatile private var unavailableReason: String? = null
    private val lock = Any()
    private var thread: HandlerThread? = null
    private val eventSequence = AtomicLong(0L)
    private val eventMonitor = java.lang.Object()
    private val packageEventSequence = ConcurrentHashMap<String, Long>()
    private val clickCacheLock = Any()
    private val cachedClickableNodes = ArrayList<CachedClickableNode>(48)

    fun status(): String = when {
        automation != null -> "READY"
        unavailableReason != null -> "UNAVAILABLE:${unavailableReason.orEmpty().take(160)}"
        else -> "UNKNOWN"
    }

    fun snapshot(targetPackage: String, maxNodes: Int): String {
        val ui = ensureConnected() ?: return marker("UNAVAILABLE", unavailableReason.orEmpty())
        val root = runCatching { ui.rootInActiveWindow }.getOrNull()
            ?: return marker("NO_ROOT", "rootInActiveWindow=null")
        return try {
            val cap = maxNodes.coerceIn(64, MAX_NODES)
            val rootPackage = root.packageName?.toString().orEmpty()
            val packed = serializeCompactTree(root, targetPackage, cap)
            marker("OK", "nodes=${packed.second};pkg=$rootPackage;format=compact") + "\n" + packed.first
        } catch (t: Throwable) {
            runCatching { root.recycle() }
            marker("ERROR", "${t.javaClass.simpleName}:${t.message.orEmpty()}")
        }
    }

    fun eventSequence(targetPackage: String): Long =
        packageEventSequence[targetPackage].orEmptySequence()

    fun waitForEvent(targetPackage: String, afterSequence: Long, timeoutMs: Int): Long {
        ensureConnected() ?: return afterSequence
        val timeout = timeoutMs.coerceIn(1, 500).toLong()
        val deadline = SystemClock.elapsedRealtime() + timeout
        synchronized(eventMonitor) {
            while (true) {
                val current = packageEventSequence[targetPackage].orEmptySequence()
                if (current > afterSequence) return current
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return afterSequence
                runCatching { eventMonitor.wait(remaining) }
            }
        }
    }

    /**
     * Event-coalesced frame: wait for the next target-package event (or the bounded fallback
     * timeout), then read the active tree immediately in the same shell-owned UiAutomation
     * process. This removes a Binder round trip compared with waitForEvent()+snapshot().
     */
    fun waitAndSnapshot(targetPackage: String, afterSequence: Long, timeoutMs: Int, maxNodes: Int): String {
        val ui = ensureConnected() ?: return frameMarker(afterSequence, false, "UNAVAILABLE", unavailableReason.orEmpty())
        val before = packageEventSequence[targetPackage].orEmptySequence()
        val sequence = waitForEvent(targetPackage, afterSequence, timeoutMs)
        val eventTriggered = sequence > afterSequence || before > afterSequence
        if (eventTriggered && ShizukuFastUiPolicy.EVENT_TREE_COALESCE_MS > 0L) {
            SystemClock.sleep(ShizukuFastUiPolicy.EVENT_TREE_COALESCE_MS)
        }
        val root = runCatching { ui.rootInActiveWindow }.getOrNull()
            ?: return frameMarker(sequence, eventTriggered, "NO_ROOT", "rootInActiveWindow=null")
        return try {
            val cap = maxNodes.coerceIn(64, MAX_NODES)
            val rootPackage = root.packageName?.toString().orEmpty()
            val packed = serializeCompactTree(root, targetPackage, cap)
            frameMarker(sequence, eventTriggered, "OK", "nodes=${packed.second};pkg=$rootPackage;format=compact") + "\n" + packed.first
        } catch (t: Throwable) {
            runCatching { root.recycle() }
            frameMarker(sequence, eventTriggered, "ERROR", "${t.javaClass.simpleName}:${t.message.orEmpty()}")
        }
    }

    fun tap(x: Int, y: Int): Boolean {
        val ui = ensureConnected() ?: return false
        if (x <= 0 || y <= 0) return false
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try {
            val downOk = ui.injectInputEvent(down, true)
            if (downOk) SystemClock.sleep(ShizukuFastUiPolicy.GESTURE_DURATION_MS)
            val upOk = ui.injectInputEvent(up, true)
            downOk && upOk
        } catch (_: Throwable) {
            false
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /**
     * Visual compatibility path for Samsung profiles where the shell UiAutomation hierarchy is
     * scoped to the owner Launcher even though Work WhatsApp is the resumed activity. The caller
     * must prove the exact user/package foreground first. This method only returns the bounds of a
     * wide green action control; it never injects input by itself.
     */
    fun findWidePositiveAction(): String {
        val ui = ensureConnected() ?: return visualMarker("UNAVAILABLE", unavailableReason.orEmpty())
        val bitmap = runCatching { ui.takeScreenshot() }.getOrNull()
            ?: return visualMarker("NO_SCREENSHOT", "UiAutomation.takeScreenshot returned null")
        return try {
            val bounds = VisualActionButtonPolicy.findWidePositiveAction(
                width = bitmap.width,
                height = bitmap.height,
                pixelAt = bitmap::getPixel
            ) ?: return visualMarker("NOT_FOUND", "image=${bitmap.width}x${bitmap.height}")
            visualMarker(
                "OK",
                "bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom};image=${bitmap.width}x${bitmap.height}"
            )
        } catch (t: Throwable) {
            visualMarker("ERROR", "${t.javaClass.simpleName}:${t.message.orEmpty()}")
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    /**
     * Prefer AccessibilityNodeInfo.ACTION_CLICK on the exact clickable target-package node under
     * the chosen point. This is faster and more precise than coordinate injection. Coordinate tap
     * remains the caller's fallback for custom views that do not expose ACTION_CLICK.
     */
    fun clickNode(targetPackage: String, x: Int, y: Int): Boolean {
        val ui = ensureConnected() ?: return false
        if (targetPackage.isBlank() || x <= 0 || y <= 0) return false
        val cached = synchronized(clickCacheLock) {
            cachedClickableNodes
                .asSequence()
                .filter { it.packageName == targetPackage && it.bounds.contains(x, y) }
                .minByOrNull { it.area }
                ?.node
                ?.let { AccessibilityNodeInfo.obtain(it) }
        }
        if (cached != null) {
            val clicked = runCatching { cached.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            runCatching { cached.recycle() }
            if (clicked) { clearClickCache(); return true }
        }
        val root = runCatching { ui.rootInActiveWindow }.getOrNull() ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val pkg = node.packageName?.toString().orEmpty()
                val rect = Rect()
                runCatching { node.getBoundsInScreen(rect) }
                val contains = rect.contains(x, y)
                if (pkg == targetPackage && node.isEnabled && node.isClickable && contains) {
                    val area = rect.width().toLong().coerceAtLeast(1L) * rect.height().toLong().coerceAtLeast(1L)
                    if (area < bestArea) {
                        best?.let { runCatching { it.recycle() } }
                        best = AccessibilityNodeInfo.obtain(node)
                        bestArea = area
                    }
                }
                val childCount = node.childCount.coerceAtMost(80)
                for (index in 0 until childCount) {
                    runCatching { node.getChild(index) }.getOrNull()?.let(queue::add)
                }
                runCatching { node.recycle() }
            }
            val clicked = runCatching { best?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }.getOrDefault(false)
            if (clicked) clearClickCache()
            return clicked
        } finally {
            best?.let { runCatching { it.recycle() } }
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().recycle() }
        }
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Boolean {
        val ui = ensureConnected() ?: return false
        if (startX <= 0 || startY <= 0 || endX <= 0 || endY <= 0) return false
        val duration = durationMs.coerceIn(ShizukuFastUiPolicy.GESTURE_DURATION_MS.toInt(), 500)
        val downTime = SystemClock.uptimeMillis()
        val events = ArrayList<MotionEvent>(8)
        fun event(time: Long, action: Int, x: Float, y: Float): MotionEvent =
            MotionEvent.obtain(downTime, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        return try {
            events += event(downTime, MotionEvent.ACTION_DOWN, startX.toFloat(), startY.toFloat())
            val steps = 4
            for (i in 1..steps) {
                val f = i.toFloat() / (steps + 1).toFloat()
                val x = startX + (endX - startX) * f
                val y = startY + (endY - startY) * f
                events += event(downTime + duration * i / (steps + 1), MotionEvent.ACTION_MOVE, x, y)
            }
            events += event(downTime + duration, MotionEvent.ACTION_UP, endX.toFloat(), endY.toFloat())
            events.all { ui.injectInputEvent(it, true) }
        } catch (_: Throwable) {
            false
        } finally {
            events.forEach { it.recycle() }
        }
    }

    fun back(): Boolean {
        val ui = ensureConnected() ?: return false
        return runCatching { ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
            .getOrDefault(false)
    }

    fun destroy() {
        clearClickCache()
        synchronized(lock) {
            val current = automation
            automation = null
            if (current != null) {
                runCatching {
                    val method = UiAutomation::class.java.methods.firstOrNull { it.name == "destroy" && it.parameterCount == 0 }
                    method?.invoke(current)
                }
            }
            runCatching { thread?.quitSafely() }
            thread = null
        }
    }

    /**
     * New explicit-run boundary. Clear stale UiAutomation connection/error/event state so a second
     * run behaves like the first run after a reboot without rebooting the phone or Shizuku.
     */
    fun resetForNewRun(): Boolean {
        clearClickCache()
        synchronized(lock) {
            val current = automation
            automation = null
            if (current != null) {
                runCatching {
                    val method = UiAutomation::class.java.methods.firstOrNull {
                        it.name == "destroy" && it.parameterCount == 0
                    }
                    method?.invoke(current)
                }
            }
            runCatching { thread?.quitSafely() }
            thread = null
            unavailableReason = null
            eventSequence.set(0L)
            packageEventSequence.clear()
        }
        return ensureConnected() != null
    }

    private fun ensureConnected(): UiAutomation? {
        automation?.let { return it }
        if (unavailableReason != null) return null
        synchronized(lock) {
            automation?.let { return it }
            if (unavailableReason != null) return null
            return try {
                val worker = HandlerThread("althmany-fast-uia").also { it.start() }
                thread = worker
                val connectionClass = Class.forName("android.app.UiAutomationConnection")
                val connection = connectionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                val candidate = constructUiAutomation(worker.looper, connection)
                    ?: throw IllegalStateException("UiAutomation constructor unavailable")
                connect(candidate)
                configure(candidate)
                // A successful root read is not required at startup; WhatsApp may not yet be foreground.
                automation = candidate
                candidate
            } catch (t: Throwable) {
                automation = null
                val root = generateSequence(t) { current ->
                    when (current) {
                        is java.lang.reflect.InvocationTargetException -> current.targetException
                        else -> current.cause
                    }
                }.lastOrNull() ?: t
                unavailableReason = "${t.javaClass.simpleName}:${root.javaClass.simpleName}:${root.message.orEmpty()}"
                runCatching { thread?.quitSafely() }
                thread = null
                null
            }
        }
    }

    private fun constructUiAutomation(looper: Looper, connection: Any): UiAutomation? {
        val constructors = UiAutomation::class.java.declaredConstructors
            .sortedBy { it.parameterCount }
        for (ctor in constructors) {
            val types = ctor.parameterTypes
            if (types.size !in 2..3) continue
            if (!Looper::class.java.isAssignableFrom(types[0])) continue
            if (!types[1].name.contains("IUiAutomationConnection")) continue
            val instance = runCatching {
                ctor.isAccessible = true
                when (types.size) {
                    2 -> ctor.newInstance(looper, connection)
                    3 -> ctor.newInstance(looper, connection, 0)
                    else -> null
                }
            }.getOrNull()
            if (instance is UiAutomation) return instance
        }
        return null
    }

    private fun connect(ui: UiAutomation) {
        val methods = UiAutomation::class.java.declaredMethods.filter { it.name == "connect" }
        val noArg = methods.firstOrNull { it.parameterCount == 0 }
        val flags = methods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        when {
            noArg != null -> {
                noArg.isAccessible = true
                noArg.invoke(ui)
            }
            flags != null -> {
                flags.isAccessible = true
                flags.invoke(ui, 0)
            }
            else -> throw NoSuchMethodException("UiAutomation.connect")
        }
    }

    private fun configure(ui: UiAutomation) {
        runCatching {
            val info = AccessibilityServiceInfo().apply {
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                notificationTimeout = 0L
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            ui.serviceInfo = info
            ui.setOnAccessibilityEventListener { event ->
                val pkg = event?.packageName?.toString().orEmpty()
                if (pkg.isNotBlank()) {
                    val seq = eventSequence.incrementAndGet()
                    packageEventSequence[pkg] = seq
                    synchronized(eventMonitor) { eventMonitor.notifyAll() }
                }
            }
        }
    }

    private fun serializeCompactTree(
        root: AccessibilityNodeInfo,
        targetPackage: String,
        maxNodes: Int
    ): Pair<String, Int> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val out = StringBuilder(28 * 1024)
        out.append("__AL_FAST_COMPACT__=1")
        val freshCache = ArrayList<CachedClickableNode>(48)
        var count = 0
        while (queue.isNotEmpty() && count < maxNodes && out.length < MAX_COMPACT_CHARS) {
            val node = queue.removeFirst()
            try {
                val pkg = node.packageName?.toString().orEmpty()
                val include = pkg.isBlank() || pkg == targetPackage
                if (include) {
                    val rect = Rect(); node.getBoundsInScreen(rect)
                    out.append('\n').append("N\t")
                    field(out, node.text?.toString().orEmpty()); out.append('\t')
                    field(out, node.contentDescription?.toString().orEmpty()); out.append('\t')
                    field(out, node.viewIdResourceName.orEmpty()); out.append('\t')
                    field(out, node.className?.toString().orEmpty()); out.append('\t')
                    field(out, pkg); out.append('\t')
                    out.append(if (node.isClickable) '1' else '0').append('\t')
                    out.append(if (node.isEnabled) '1' else '0').append('\t')
                    out.append(if (node.isScrollable) '1' else '0').append('\t')
                    out.append('0').append('\t')
                    out.append(rect.left).append(',').append(rect.top).append(',').append(rect.right).append(',').append(rect.bottom)
                    if (pkg == targetPackage && node.isEnabled && node.isClickable && !rect.isEmpty) {
                        freshCache += CachedClickableNode(pkg, Rect(rect), AccessibilityNodeInfo.obtain(node))
                    }
                    count += 1
                }
                val childCount = node.childCount.coerceAtMost(80)
                for (index in 0 until childCount) {
                    runCatching { node.getChild(index) }.getOrNull()?.let(queue::add)
                }
            } finally { runCatching { node.recycle() } }
        }
        while (queue.isNotEmpty()) runCatching { queue.removeFirst().recycle() }
        replaceClickCache(freshCache)
        return out.toString() to count
    }

    private fun field(out: StringBuilder, value: String) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> if (ch >= ' ') out.append(ch)
            }
        }
    }

    private fun replaceClickCache(fresh: ArrayList<CachedClickableNode>) {
        synchronized(clickCacheLock) {
            cachedClickableNodes.forEach { runCatching { it.node.recycle() } }
            cachedClickableNodes.clear()
            cachedClickableNodes.addAll(fresh)
        }
    }

    private fun clearClickCache() {
        synchronized(clickCacheLock) {
            cachedClickableNodes.forEach { runCatching { it.node.recycle() } }
            cachedClickableNodes.clear()
        }
    }

    private data class CachedClickableNode(
        val packageName: String,
        val bounds: Rect,
        val node: AccessibilityNodeInfo
    ) {
        val area: Long get() = bounds.width().toLong().coerceAtLeast(1L) * bounds.height().toLong().coerceAtLeast(1L)
    }

    private fun attr(out: StringBuilder, name: String, value: String) {
        out.append(' ').append(name).append("=\"").append(escape(value)).append('"')
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch >= ' ' || ch == '\n' || ch == '\t') append(ch)
            }
        }
    }

    private fun frameMarker(sequence: Long, eventTriggered: Boolean, state: String, detail: String): String =
        "__AL_FAST_FRAME__=$sequence;event=${if (eventTriggered) 1 else 0};state=$state;$detail"

    private fun marker(state: String, detail: String): String =
        "__AL_FAST_UI__=$state;$detail"

    private fun visualMarker(state: String, detail: String): String =
        "__AL_VISUAL_ACTION__=$state;$detail"

    private fun Long?.orEmptySequence(): Long = this ?: 0L

    private companion object {
        const val MAX_NODES = 1_600
        const val MAX_COMPACT_CHARS = 360_000
    }
}
