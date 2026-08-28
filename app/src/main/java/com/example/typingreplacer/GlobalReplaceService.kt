package com.example.typingreplacer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * V2 architecture:
 *
 * 1. Android's AccessibilityService binding is the primary lifecycle.
 * 2. TYPE_VIEW_TEXT_CHANGED is the fast path.
 * 3. The currently focused input node is resolved explicitly; the service never
 *    blindly edits "the first editable node" on the screen.
 * 4. A low-frequency focused-node scan is only a compatibility fallback for
 *    apps/ROMs that stop emitting text-change events after the app goes to the
 *    background.
 * 5. Rules are cached in memory and reloaded only when SharedPreferences change.
 * 6. Self-generated ACTION_SET_TEXT callbacks are suppressed per input session.
 */
class GlobalReplaceService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var repository: ReplacementRepository
    private lateinit var prefs: SharedPreferences

    private var cachedRules: List<ReplacementRule> = emptyList()
    private var compatibilityScanEnabled = true
    private var session: InputSession? = null
    private var listenerRegistered = false

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                ReplacementRepository.KEY_RULES -> reloadRules()
                AppSettings.KEY_COMPATIBILITY_SCAN -> reloadSettings()
            }
        }

    private val delayedScanRunnable = Runnable {
        inspectFocusedInput(reason = "event-scan")
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            ServiceRuntimeState.markHeartbeat()

            if (compatibilityScanEnabled) {
                inspectFocusedInput(reason = "compatibility-scan")
            }

            handler.postDelayed(this, COMPATIBILITY_SCAN_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        repository = ReplacementRepository(this)
        prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)

        configureService()
        reloadRules()
        reloadSettings()

        if (!listenerRegistered) {
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
            listenerRegistered = true
        }

        session = null
        ServiceRuntimeState.markConnected()

        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)

        Log.i(TAG, "Accessibility service connected")
    }

    private fun configureService() {
        val info = serviceInfo ?: AccessibilityServiceInfo()

        info.eventTypes =
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED

        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 0
        info.flags =
            info.flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPackage = event.packageName?.toString()
        if (eventPackage == packageName) return

        ServiceRuntimeState.markEvent(eventPackage)

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source
                if (source != null) {
                    try {
                        if (isEditableTarget(source)) {
                            processNode(
                                node = source,
                                event = event,
                                reason = "text-event",
                            )
                            return
                        }
                    } finally {
                        source.recycle()
                    }
                }

                scheduleFocusedScan(40L)
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                scheduleFocusedScan(40L)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                session = null
                scheduleFocusedScan(100L)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // A click often precedes a focus transition. Refresh the target shortly after.
                scheduleFocusedScan(60L)
            }
        }
    }

    override fun onInterrupt() {
        ServiceRuntimeState.markError("系统调用了 onInterrupt")
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup("无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup("无障碍服务已销毁")
        super.onDestroy()
    }

    private fun cleanup(reason: String) {
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacks(delayedScanRunnable)

        if (listenerRegistered) {
            prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            listenerRegistered = false
        }

        session = null
        ServiceRuntimeState.markDisconnected(reason)
    }

    private fun reloadRules() {
        cachedRules = repository.loadRules()
        Log.i(TAG, "Rules reloaded: ${cachedRules.size}")
    }

    private fun reloadSettings() {
        compatibilityScanEnabled = AppSettings(this).compatibilityScanEnabled
    }

    private fun scheduleFocusedScan(delayMs: Long) {
        handler.removeCallbacks(delayedScanRunnable)
        handler.postDelayed(delayedScanRunnable, delayMs)
    }

    private fun inspectFocusedInput(reason: String) {
        val node = findFocusedEditable()
        if (node == null) {
            ServiceRuntimeState.markNode("未找到当前焦点输入框（$reason）")
            return
        }

        try {
            val pkg = node.packageName?.toString()
            if (pkg == packageName) return

            ServiceRuntimeState.markNode(
                "已找到焦点输入框：${pkg.orEmpty()}（$reason）"
            )
            processNode(node = node, event = null, reason = reason)
        } finally {
            node.recycle()
        }
    }

    /**
     * Prefer FOCUS_INPUT. We never use the old "first editable node in the tree"
     * strategy because pages can contain search boxes, comments and chat inputs
     * at the same time.
     */
    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            try {
                val focused = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    if (isEditableTarget(focused)) {
                        return focused
                    }
                    focused.recycle()
                }

                val deepFocused = findDeepFocusedEditable(activeRoot)
                if (deepFocused != null) return deepFocused
            } finally {
                activeRoot.recycle()
            }
        }

        val orderedWindows = windows.sortedWith(
            compareByDescending<android.view.accessibility.AccessibilityWindowInfo> { it.isFocused }
                .thenByDescending { it.isActive }
        )

        for (window in orderedWindows) {
            val root = window.root ?: continue
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    if (isEditableTarget(focused)) {
                        return focused
                    }
                    focused.recycle()
                }

                val deepFocused = findDeepFocusedEditable(root)
                if (deepFocused != null) return deepFocused
            } finally {
                root.recycle()
            }
        }

        return null
    }

    private fun findDeepFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && isEditableTarget(node)) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val found = findDeepFocusedEditable(child)
                if (found != null) return found
            } finally {
                child.recycle()
            }
        }

        return null
    }

    private fun isEditableTarget(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled || node.isPassword) return false
        if (node.isEditable) return true

        return node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        }
    }

    private fun processNode(
        node: AccessibilityNodeInfo,
        event: AccessibilityEvent?,
        reason: String,
    ) {
        if (!isEditableTarget(node)) return

        val current = node.text?.toString() ?: ""
        val key = nodeKey(node)
        val now = System.currentTimeMillis()

        val activeSession =
            session?.takeIf { it.key == key }
                ?: InputSession(key = key).also { session = it }

        if (
            now - activeSession.lastWriteAt <= SELF_WRITE_GUARD_MS &&
            current == activeSession.lastWritten
        ) {
            return
        }

        if (current.isEmpty()) {
            activeSession.lastWritten = ""
            return
        }

        val target = computeTarget(
            current = current,
            event = event,
            activeSession = activeSession,
        )

        if (target == current) {
            activeSession.lastWritten = current
            return
        }

        val selectionEnd = node.textSelectionEnd
        val ok = setNodeText(node, target, selectionEnd, current.length)

        if (ok) {
            activeSession.lastWritten = target
            activeSession.lastWriteAt = now
            ServiceRuntimeState.markReplacement(node.packageName?.toString())
            ServiceRuntimeState.markNode("替换写入成功（$reason）")
            Log.d(TAG, "replace[$reason] '$current' -> '$target'")
        } else {
            activeSession.lastWritten = current
            ServiceRuntimeState.markError(
                "ACTION_SET_TEXT 被目标应用拒绝：${node.packageName?.toString().orEmpty()}"
            )
            ServiceRuntimeState.markNode("找到输入框，但 ACTION_SET_TEXT 失败（$reason）")
            Log.w(TAG, "ACTION_SET_TEXT failed for ${node.packageName}")
        }
    }

    private fun computeTarget(
        current: String,
        event: AccessibilityEvent?,
        activeSession: InputSession,
    ): String {
        val change =
            if (event == null) {
                null
            } else {
                IncrementalTransformer.Change(
                    beforeText = event.beforeText?.toString(),
                    fromIndex = event.fromIndex,
                    addedCount = event.addedCount,
                    removedCount = event.removedCount,
                )
            }

        return IncrementalTransformer.transform(
            current = current,
            previousOutput = activeSession.lastWritten,
            change = change,
            rules = cachedRules,
        )
    }

    private fun nodeKey(node: AccessibilityNodeInfo): NodeKey {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        return NodeKey(
            windowId = node.windowId,
            packageName = node.packageName?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
        )
    }

    private fun setNodeText(
        node: AccessibilityNodeInfo,
        text: String,
        oldSelectionEnd: Int,
        oldLength: Int,
    ): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }

        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return false

        // ACTION_SET_TEXT often moves the cursor to the end. Restore an approximate
        // logical position when the app supports ACTION_SET_SELECTION.
        if (oldSelectionEnd >= 0) {
            val delta = text.length - oldLength
            val newSelection = (oldSelectionEnd + delta).coerceIn(0, text.length)
            val selectionArgs = Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    newSelection,
                )
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    newSelection,
                )
            }
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                selectionArgs,
            )
        }

        return true
    }

    private data class NodeKey(
        val windowId: Int,
        val packageName: String,
        val viewId: String,
        val className: String,
        val bounds: String,
    )

    private data class InputSession(
        val key: NodeKey,
        var lastWritten: String = "",
        var lastWriteAt: Long = 0L,
    )

    private companion object {
        const val TAG = "TypingReplacer"
        const val SELF_WRITE_GUARD_MS = 1200L
        const val COMPATIBILITY_SCAN_INTERVAL_MS = 700L
    }
}
