package com.fersaiyan.cyanbridge.localagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.memoryvault.MemoryVaultBootstrap
import com.fersaiyan.cyanbridge.memoryvault.VaultLockStateManager
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryRoomIndex
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore

/*
 * MIT Attribution (PhoneClaw)
 *
 * This AccessibilityService and its automation primitives are inspired by the
 * PhoneClaw project, which demonstrates AI-driven Android automation using the
 * Accessibility framework.
 *
 * Project: https://github.com/phoneclaw/phoneclaw
 * License: MIT (as stated by the upstream project)
 */
class LocalAgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        startPeriodicAutoCapture()

        // Be explicit about what we want; the XML config is authoritative but some
        // devices apply extra constraints unless flags are set here as well.
        runCatching {
            serviceInfo = serviceInfo.apply {
                flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            }
        }

        Log.i(TAG, "LocalAgentAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Keep the callback lightweight; we only use it for optional periodic screen capture.
        maybeAutoCapture(event)
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onDestroy() {
        stopPeriodicAutoCapture()
        super.onDestroy()
        if (instance === this) instance = null
    }

    private fun maybeAutoCapture(event: AccessibilityEvent?) {
        // MVP: periodic capture of accessibility text into local JSONL memory.
        if (!LocalAgentPrefs.isAutoCaptureEnabled(applicationContext)) return
        if (!MemoryModeManager.isScreenOcrCaptureEnabled(applicationContext)) return
        if (!isDeviceInteractiveAndUnlocked()) return
        if (VaultLockStateManager.isLocked(applicationContext)) return

        MemoryVaultBootstrap.ensureInitialized(applicationContext)

        val intervalMin = LocalAgentPrefs.getCaptureIntervalMin(applicationContext)
        val intervalMs = intervalMin.toLong().coerceAtLeast(1L) * 60_000L

        val now = System.currentTimeMillis()
        val last = lastAutoCaptureAtMs
        if (last > 0L && now - last < intervalMs) return

        val pkg = resolveCapturePackageName(event)
        if (pkg.isBlank()) return

        val blacklist = LocalAgentPrefs.getCaptureBlacklistPackages(applicationContext)
        if (blacklist.contains(pkg)) {
            Log.d(TAG, "Skipping auto-capture for blacklisted package: $pkg")
            return
        }

        if (isOverlayPackage(pkg)) {
            Log.d(TAG, "Skipping overlay/system package capture: $pkg")
            return
        }

        val text = dumpActiveWindowText() ?: return
        if (text.isBlank()) return

        LocalAgentMemoryStore.appendScreenCapture(
            context = applicationContext,
            packageName = pkg,
            text = text,
            tsMs = now,
        )

        // Also index into Room (FTS5) for fast retrieval.
        LocalAgentMemoryRoomIndex.indexScreenCaptureAsync(
            context = applicationContext,
            packageName = pkg,
            text = text,
            tsMs = now,
        )

        lastAutoCaptureAtMs = now
        Log.i(TAG, "Auto-captured screen text: pkg=$pkg chars=${text.length} intervalMin=$intervalMin")
    }

    private fun resolveCapturePackageName(event: AccessibilityEvent?): String {
        val candidates = LinkedHashSet<String>()

        fun addCandidate(raw: CharSequence?) {
            val pkg = normalizePackageName(raw)
            if (pkg.isNotBlank()) candidates.add(pkg)
        }

        addCandidate(event?.packageName)
        addCandidate(event?.source?.packageName)
        addCandidate(rootInActiveWindow?.packageName)
        addCandidate(extractPackageFromActiveWindow())
        addCandidate(lastForegroundNonOverlayPackage)

        val chosen = candidates.firstOrNull { !isOverlayPackage(it) }
            ?: candidates.firstOrNull().orEmpty()

        if (chosen.isNotBlank() && !isOverlayPackage(chosen)) {
            lastForegroundNonOverlayPackage = chosen
        }
        return chosen
    }

    private fun extractPackageFromActiveWindow(): String {
        val allWindows = windows.orEmpty()
        if (allWindows.isEmpty()) return ""

        var fallback: String = ""
        for (win in allWindows) {
            val root = runCatching { win.root }.getOrNull() ?: continue
            val pkg = normalizePackageName(root.packageName)
            if (pkg.isBlank()) continue

            if (fallback.isBlank()) fallback = pkg

            val activeOrFocused = runCatching { win.isActive || win.isFocused }.getOrDefault(false)
            if (!activeOrFocused) continue

            if (!isOverlayPackage(pkg)) {
                return pkg
            }

            if (fallback.isBlank()) {
                fallback = pkg
            }
        }

        return fallback
    }

    private fun normalizePackageName(raw: CharSequence?): String {
        return raw?.toString()?.trim()?.lowercase().orEmpty()
    }

    private fun isOverlayPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (OVERLAY_PACKAGE_PREFIXES.any { pkg.startsWith(it) }) return true
        return OVERLAY_PACKAGE_NAMES.contains(pkg)
    }

    private fun isDeviceInteractiveAndUnlocked(): Boolean {
        val power = getSystemService(POWER_SERVICE) as? PowerManager
        if (power != null && !power.isInteractive) return false

        val keyguard = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true) return false

        return true
    }

    // --- Core automation primitives ---

    /**
     * Returns all user-visible text (and optionally content descriptions) discovered by
     * traversing the active accessibility tree.
     */
    fun getAllTextFromScreen(
        includeContentDescriptions: Boolean = true,
        includeViewIds: Boolean = false,
        maxNodes: Int = 10_000,
    ): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>(256)
        val seen = LinkedHashSet<String>()

        fun add(s: CharSequence?) {
            val v = s?.toString()?.trim().orEmpty()
            if (v.isNotBlank() && seen.add(v)) out.add(v)
        }

        fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visited: IntArray) {
            if (node == null) return
            if (visited[0] >= maxNodes) return
            visited[0]++

            add(node.text)
            if (includeContentDescriptions) add(node.contentDescription)

            if (includeViewIds) {
                val id = node.viewIdResourceName
                if (!id.isNullOrBlank()) add("viewId=$id")
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1, visited)
                if (visited[0] >= maxNodes) return
            }
        }

        walk(root, visited = intArrayOf(0))
        return out
    }

    /** Tap an absolute coordinate using gesture injection (API 24+; minSdk=24 in this app). */
    fun simulateClick(
        x: Int,
        y: Int,
        durationMs: Long = 40L,
        onComplete: ((success: Boolean) -> Unit)? = null,
    ): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(false)
                }
            },
            null,
        )
    }

    /**
     * Finds a node by visible text or contentDescription and clicks it.
     *
     * Implementation detail: if the matched node itself is not clickable, we walk
     * up the parent chain to find a clickable container.
     */
    fun clickByTextOrDesc(
        query: String,
        ignoreCase: Boolean = true,
        partialMatch: Boolean = true,
        maxNodes: Int = 10_000,
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        val q = query.trim()
        if (q.isBlank()) return false

        val target = findFirstNodeMatching(root, q, ignoreCase, partialMatch, maxNodes)
            ?: return false

        return performClickBestEffort(target)
    }

    /**
     * Helper for ACTION_SET_TEXT.
     *
     * NOTE: This requires an editable/focusable node. For best results, call click/focus
     * on the field before setting text.
     */
    fun performSetText(node: AccessibilityNodeInfo, newText: CharSequence): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        // Some UIs require focus first.
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
    }

    /** Scroll by gesture (swipe). */
    fun scrollGesture(
        direction: ScrollDirection,
        distanceRatio: Float = 0.65f,
        durationMs: Long = 350L,
    ): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        if (w <= 0 || h <= 0) return false

        val x = w / 2f
        val dy = (h * distanceRatio).coerceIn(50f, h.toFloat())

        val (startY, endY) = when (direction) {
            // "Scroll down" == move content down == swipe up.
            ScrollDirection.DOWN -> (h * 0.80f) to (h * 0.80f - dy)
            // "Scroll up" == move content up == swipe down.
            ScrollDirection.UP -> (h * 0.20f) to (h * 0.20f + dy)
        }

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    // --- internals ---

    private fun findFirstNodeMatching(
        root: AccessibilityNodeInfo,
        query: String,
        ignoreCase: Boolean,
        partialMatch: Boolean,
        maxNodes: Int,
    ): AccessibilityNodeInfo? {
        val q = query.trim()
        if (q.isBlank()) return null

        fun matches(value: CharSequence?): Boolean {
            val s = value?.toString()?.trim().orEmpty()
            if (s.isBlank()) return false
            return if (partialMatch) s.contains(q, ignoreCase = ignoreCase)
            else s.equals(q, ignoreCase = ignoreCase)
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++

            if (matches(node.text) || matches(node.contentDescription)) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return null
    }

    private fun performClickBestEffort(node: AccessibilityNodeInfo): Boolean {
        // 1) Click the node itself if it can.
        if (node.isClickable) {
            return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                .getOrDefault(false)
        }

        // 2) Walk up the parent chain looking for a clickable container.
        var p: AccessibilityNodeInfo? = node
        var hops = 0
        while (hops < 12) {
            val current = p ?: break
            if (current.isClickable) {
                return runCatching { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .getOrDefault(false)
            }
            p = current.parent
            hops++
        }

        // 3) Fallback: try coordinate click from bounds.
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        if (!rect.isEmpty) {
            return simulateClick(rect.centerX(), rect.centerY())
        }

        return false
    }

    enum class ScrollDirection { UP, DOWN }

    /** Compact best-effort text snapshot for the agent observer. */
    fun dumpActiveWindowText(maxLines: Int = 400): String? {
        val lines = getAllTextFromScreen(includeContentDescriptions = true)
        if (lines.isEmpty()) return null
        return lines.take(maxLines).joinToString("\n")
    }

    /** Best-effort typing: focused field first; otherwise first editable node. */
    fun typeTextBestEffort(text: CharSequence): Boolean {
        val root = rootInActiveWindow ?: return false

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused ?: findFirstEditable(root)
        target ?: return false

        return performSetText(target, text)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var periodicRunnable: Runnable? = null

    private fun startPeriodicAutoCapture() {
        stopPeriodicAutoCapture()
        val r = object : Runnable {
            override fun run() {
                // Use the same capture logic as events, but don't rely on events firing.
                runCatching { maybeAutoCapture(null) }
                handler.postDelayed(this, PERIODIC_TICK_MS)
            }
        }
        periodicRunnable = r
        handler.post(r)
    }

    private fun stopPeriodicAutoCapture() {
        periodicRunnable?.let { handler.removeCallbacks(it) }
        periodicRunnable = null
    }

    companion object {
        private const val TAG = "LocalAgentAccSvc"
        private const val PERIODIC_TICK_MS = 30_000L
        private val OVERLAY_PACKAGE_NAMES = setOf(
            "com.android.systemui",
        )
        private val OVERLAY_PACKAGE_PREFIXES = setOf(
            "com.android.launcher",
            "com.google.android.launcher",
            "com.samsung.android.launcher",
        )

        @Volatile
        var instance: LocalAgentAccessibilityService? = null
            private set

        @Volatile
        private var lastAutoCaptureAtMs: Long = 0L

        @Volatile
        private var lastForegroundNonOverlayPackage: String? = null

        fun isRunning(): Boolean = instance != null
    }
}
