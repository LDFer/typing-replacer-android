package com.example.typingreplacer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * V2 accessibility engine.
 *
 * Text-change events are the fast path; a focused-node scan is the compatibility
 * fallback. WeChat gets an additional focus + clipboard-paste fallback when its
 * custom input node rejects ACTION_SET_TEXT.
 */
class GlobalReplaceService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var repository: ReplacementRepository
    private lateinit var prefs: SharedPreferences

    private var cachedRules: List<ReplacementRule> = emptyList()
    private var compatibilityScanEnabled = true
    private var lockReplacementEnabled = false
    private var session: InputSession? = null
    private var listenerRegistered = false

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                ReplacementRepository.KEY_RULES -> reloadRules()
                AppSettings.KEY_COMPATIBILITY_SCAN,
                AppSettings.KEY_LOCK_REPLACEMENT -> reloadSettings()
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
                            processNode(source, event, "text-event")
                            return
                        }
                    } finally {
                        source.recycle()
                    }
                }
                scheduleFocusedScan(35L)
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> scheduleFocusedScan(35L)

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                session = null
                scheduleFocusedScan(90L)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val clicked = event.source
                if (clicked != null) {
                    try {
                        if (isLikelySendButton(clicked)) {
                            session?.apply {
                                allowClearUntil = System.currentTimeMillis() + SEND_CLEAR_GRACE_MS
                                lockedText = ""
                                lockActive = false
                            }
                        }
                    } finally {
                        clicked.recycle()
                    }
                }
                scheduleFocusedScan(50L)
            }
        }
    }

    override fun onInterrupt() {
        ServiceRuntimeState.markError("系统调用了 onInterrupt")
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
    }

    private fun reloadSettings() {
        val settings = AppSettings(this)
        compatibilityScanEnabled = settings.compatibilityScanEnabled
        lockReplacementEnabled = settings.lockReplacementEnabled
        if (!lockReplacementEnabled) {
            session?.apply {
                lockActive = false
                lockedText = ""
            }
        }
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
            ServiceRuntimeState.markNode("已找到焦点输入框：${pkg.orEmpty()}（$reason）")
            processNode(node, null, reason)
        } finally {
            node.recycle()
        }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            try {
                val focused = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    if (isEditableTarget(focused)) return focused
                    focused.recycle()
                }
                findDeepFocusedEditable(activeRoot)?.let { return it }
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
                    if (isEditableTarget(focused)) return focused
                    focused.recycle()
                }
                findDeepFocusedEditable(root)?.let { return it }
            } finally {
                root.recycle()
            }
        }
        return null
    }

    private fun findDeepFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if ((node.isFocused || node.isAccessibilityFocused) && isEditableTarget(node)) {
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
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return true

        // Some WeChat versions expose the chat field as a focusable EditText-like
        // node without advertising isEditable/ACTION_SET_TEXT consistently.
        return node.packageName?.toString() == WECHAT_PACKAGE &&
            node.className?.toString()?.contains("EditText", ignoreCase = true) == true &&
            node.isFocusable
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
        val activeSession = session?.takeIf { it.key == key }
            ?: InputSession(key).also { session = it }

        if (now - activeSession.lastWriteAt <= SELF_WRITE_GUARD_MS &&
            current == activeSession.lastWritten
        ) return

        if (current.isEmpty()) {
            if (
                lockReplacementEnabled &&
                activeSession.lockActive &&
                activeSession.lockedText.isNotEmpty() &&
                now > activeSession.allowClearUntil
            ) {
                writeText(node, activeSession.lockedText, 0, 0, "lock-restore-empty", activeSession)
            } else {
                activeSession.lastWritten = ""
                activeSession.lockedText = ""
                activeSession.lockActive = false
            }
            return
        }

        // Locked text may be extended by typing, but deletion is immediately restored.
        if (
            lockReplacementEnabled &&
            activeSession.lockActive &&
            activeSession.lastWritten.isNotEmpty() &&
            current.length < activeSession.lastWritten.length &&
            now > activeSession.allowClearUntil
        ) {
            writeText(
                node,
                activeSession.lockedText.ifEmpty { activeSession.lastWritten },
                node.textSelectionEnd,
                current.length,
                "lock-restore-delete",
                activeSession,
            )
            return
        }

        val target = computeTarget(current, event, activeSession)
        val replacementOccurred = target != current

        if (!replacementOccurred) {
            activeSession.lastWritten = current
            if (lockReplacementEnabled && activeSession.lockActive) {
                activeSession.lockedText = current
            }
            return
        }

        if (writeText(node, target, node.textSelectionEnd, current.length, reason, activeSession)) {
            if (lockReplacementEnabled) {
                activeSession.lockActive = true
                activeSession.lockedText = target
            }
        }
    }

    private fun writeText(
        node: AccessibilityNodeInfo,
        text: String,
        oldSelectionEnd: Int,
        oldLength: Int,
        reason: String,
        activeSession: InputSession,
    ): Boolean {
        val ok = setNodeTextCompat(node, text, oldSelectionEnd, oldLength)
        if (ok) {
            activeSession.lastWritten = text
            activeSession.lastWriteAt = System.currentTimeMillis()
            if (activeSession.lockActive) activeSession.lockedText = text
            ServiceRuntimeState.markReplacement(node.packageName?.toString())
            ServiceRuntimeState.markNode("替换写入成功（$reason）")
        } else {
            ServiceRuntimeState.markError(
                "无法写入目标输入框：${node.packageName?.toString().orEmpty()}"
            )
            ServiceRuntimeState.markNode("找到输入框，但写入失败（$reason）")
        }
        return ok
    }

    private fun computeTarget(
        current: String,
        event: AccessibilityEvent?,
        activeSession: InputSession,
    ): String {
        val change = if (event == null) null else IncrementalTransformer.Change(
            beforeText = event.beforeText?.toString(),
            fromIndex = event.fromIndex,
            addedCount = event.addedCount,
            removedCount = event.removedCount,
        )
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

    private fun setNodeTextCompat(
        node: AccessibilityNodeInfo,
        text: String,
        oldSelectionEnd: Int,
        oldLength: Int,
    ): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            restoreSelection(node, text, oldSelectionEnd, oldLength)
            return true
        }

        if (node.packageName?.toString() != WECHAT_PACKAGE) return false
        return pasteFallback(node, text)
    }

    private fun restoreSelection(
        node: AccessibilityNodeInfo,
        text: String,
        oldSelectionEnd: Int,
        oldLength: Int,
    ) {
        if (oldSelectionEnd < 0) return
        val delta = text.length - oldLength
        val newSelection = (oldSelectionEnd + delta).coerceIn(0, text.length)
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newSelection)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newSelection)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
    }

    private fun pasteFallback(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val oldClip = clipboard.primaryClip

        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                (node.text?.length ?: 0),
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        clipboard.setPrimaryClip(ClipData.newPlainText("typing-replacer", text))
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        handler.postDelayed({
            try {
                if (oldClip != null) {
                    clipboard.setPrimaryClip(oldClip)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) {
            }
        }, 180L)

        if (ok) ServiceRuntimeState.markNode("微信兼容粘贴写入成功")
        return ok
    }

    private fun isLikelySendButton(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        val text = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty()
            .trim().lowercase()
        return id.contains("send") ||
            id.contains("btn_send") ||
            text == "发送" ||
            text == "send" ||
            text.contains("发送消息")
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
        var lockActive: Boolean = false,
        var lockedText: String = "",
        var allowClearUntil: Long = 0L,
    )

    private companion object {
        const val TAG = "TypingReplacer"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val SELF_WRITE_GUARD_MS = 1200L
        const val COMPATIBILITY_SCAN_INTERVAL_MS = 700L
        const val SEND_CLEAR_GRACE_MS = 1800L
    }
}
