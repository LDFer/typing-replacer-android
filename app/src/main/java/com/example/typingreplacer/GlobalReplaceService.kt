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
import android.view.accessibility.AccessibilityWindowInfo

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
        DiagnosticLog.add(
            "SERVICE",
            "connected rules=${cachedRules.size} scan=$compatibilityScanEnabled lock=$lockReplacementEnabled"
        )
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
        DiagnosticLog.add(
            "SERVICE",
            "configured eventTypes=${info.eventTypes} flags=${info.flags} timeout=${info.notificationTimeout}"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPackage = event.packageName?.toString()
        if (eventPackage == packageName) return
        ServiceRuntimeState.markEvent(eventPackage)

        if (eventPackage == WECHAT_PACKAGE) {
            val source = event.source
            try {
                DiagnosticLog.add(
                    "WX-EVENT",
                    buildString {
                        append(eventName(event.eventType))
                        append(" win=${event.windowId}")
                        append(" from=${event.fromIndex}")
                        append(" add=${event.addedCount}")
                        append(" remove=${event.removedCount}")
                        append(" beforeLen=${event.beforeText?.length ?: -1}")
                        append(" source=")
                        append(if (source == null) "null" else nodeSummary(source))
                    }
                )
            } finally {
                source?.recycle()
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source
                if (source != null) {
                    try {
                        if (isEditableTarget(source)) {
                            if (eventPackage == WECHAT_PACKAGE) {
                                DiagnosticLog.add("WX-FLOW", "text-event accepted ${nodeSummary(source)}")
                            }
                            processNode(source, event, "text-event")
                            return
                        } else if (eventPackage == WECHAT_PACKAGE) {
                            DiagnosticLog.add("WX-FLOW", "text-event source rejected ${nodeSummary(source)}")
                        }
                    } finally {
                        source.recycle()
                    }
                } else if (eventPackage == WECHAT_PACKAGE) {
                    DiagnosticLog.add("WX-FLOW", "text-event has no source; scheduling scan")
                }
                scheduleFocusedScan(35L)
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> scheduleFocusedScan(35L)

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                session = null
                if (eventPackage == WECHAT_PACKAGE) {
                    DiagnosticLog.add("WX-FLOW", "window changed; session cleared")
                }
                scheduleFocusedScan(90L)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val clicked = event.source
                if (clicked != null) {
                    try {
                        if (isLikelySendButton(clicked)) {
                            DiagnosticLog.add("FLOW", "send-like button clicked pkg=$eventPackage")
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
        DiagnosticLog.add("SERVICE", "onInterrupt")
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
        DiagnosticLog.add("SERVICE", reason)
    }

    private fun reloadRules() {
        cachedRules = repository.loadRules()
        DiagnosticLog.add("RULE", "reloaded count=${cachedRules.size}")
    }

    private fun reloadSettings() {
        val settings = AppSettings(this)
        compatibilityScanEnabled = settings.compatibilityScanEnabled
        lockReplacementEnabled = settings.lockReplacementEnabled
        DiagnosticLog.add(
            "SETTINGS",
            "scan=$compatibilityScanEnabled lock=$lockReplacementEnabled"
        )
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
        val activePkg = rootInActiveWindow?.let { root ->
            try {
                root.packageName?.toString()
            } finally {
                root.recycle()
            }
        }

        if (activePkg == WECHAT_PACKAGE) {
            DiagnosticLog.add("WX-SCAN", "begin reason=$reason windows=${windows.size}")
        }

        val node = findFocusedEditable()
        if (node == null) {
            ServiceRuntimeState.markNode("未找到当前焦点输入框（$reason）")
            if (activePkg == WECHAT_PACKAGE) {
                DiagnosticLog.add(
                    "WX-SCAN",
                    "no focused editable; ${windowSummary()}"
                )
            }
            return
        }
        try {
            val pkg = node.packageName?.toString()
            if (pkg == packageName) return
            ServiceRuntimeState.markNode("已找到焦点输入框：${pkg.orEmpty()}（$reason）")
            if (pkg == WECHAT_PACKAGE) {
                DiagnosticLog.add("WX-SCAN", "found ${nodeSummary(node)} reason=$reason")
            }
            processNode(node, null, reason)
        } finally {
            node.recycle()
        }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            try {
                val rootPkg = activeRoot.packageName?.toString()
                val focused = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    if (rootPkg == WECHAT_PACKAGE) {
                        DiagnosticLog.add("WX-FOCUS", "root.findFocus -> ${nodeSummary(focused)}")
                    }
                    if (isEditableTarget(focused)) return focused
                    focused.recycle()
                } else if (rootPkg == WECHAT_PACKAGE) {
                    DiagnosticLog.add("WX-FOCUS", "root.findFocus -> null root=${nodeSummary(activeRoot)}")
                }
                findDeepFocusedEditable(activeRoot)?.let {
                    if (rootPkg == WECHAT_PACKAGE) {
                        DiagnosticLog.add("WX-FOCUS", "deep focus -> ${nodeSummary(it)}")
                    }
                    return it
                }
            } finally {
                activeRoot.recycle()
            }
        }

        val orderedWindows = windows.sortedWith(
            compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                .thenByDescending { it.isActive }
        )
        for (window in orderedWindows) {
            val root = window.root ?: continue
            try {
                val rootPkg = root.packageName?.toString()
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    if (rootPkg == WECHAT_PACKAGE) {
                        DiagnosticLog.add(
                            "WX-FOCUS",
                            "window(type=${window.type},active=${window.isActive},focused=${window.isFocused}) findFocus -> ${nodeSummary(focused)}"
                        )
                    }
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
        val isWeChat = node.packageName?.toString() == WECHAT_PACKAGE

        if (isWeChat) {
            DiagnosticLog.add(
                "WX-PROCESS",
                "reason=$reason len=${current.length} selection=${node.textSelectionStart}..${node.textSelectionEnd} ruleMatch=${TextReplacer.replace(current, cachedRules) != current} lock=${activeSession.lockActive}"
            )
        }

        if (now - activeSession.lastWriteAt <= SELF_WRITE_GUARD_MS &&
            current == activeSession.lastWritten
        ) {
            if (isWeChat) DiagnosticLog.add("WX-PROCESS", "self-write guard hit")
            return
        }

        if (current.isEmpty()) {
            if (
                lockReplacementEnabled &&
                activeSession.lockActive &&
                activeSession.lockedText.isNotEmpty() &&
                now > activeSession.allowClearUntil
            ) {
                if (isWeChat) DiagnosticLog.add("WX-LOCK", "empty -> restore locked len=${activeSession.lockedText.length}")
                writeText(node, activeSession.lockedText, 0, 0, "lock-restore-empty", activeSession)
            } else {
                activeSession.lastWritten = ""
                activeSession.lockedText = ""
                activeSession.lockActive = false
                if (isWeChat) DiagnosticLog.add("WX-PROCESS", "empty accepted / session unlocked")
            }
            return
        }

        if (
            lockReplacementEnabled &&
            activeSession.lockActive &&
            activeSession.lastWritten.isNotEmpty() &&
            current.length < activeSession.lastWritten.length &&
            now > activeSession.allowClearUntil
        ) {
            if (isWeChat) {
                DiagnosticLog.add(
                    "WX-LOCK",
                    "delete detected currentLen=${current.length} lastLen=${activeSession.lastWritten.length} lockedLen=${activeSession.lockedText.length}"
                )
            }
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

        if (isWeChat) {
            DiagnosticLog.add(
                "WX-TRANSFORM",
                "replacement=$replacementOccurred currentLen=${current.length} targetLen=${target.length}"
            )
        }

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
        val isWeChat = node.packageName?.toString() == WECHAT_PACKAGE
        if (isWeChat) {
            DiagnosticLog.add(
                "WX-WRITE",
                "start reason=$reason oldLen=$oldLength newLen=${text.length} ${nodeSummary(node)}"
            )
        }

        val ok = setNodeTextCompat(node, text, oldSelectionEnd, oldLength)
        if (ok) {
            activeSession.lastWritten = text
            activeSession.lastWriteAt = System.currentTimeMillis()
            if (activeSession.lockActive) activeSession.lockedText = text
            ServiceRuntimeState.markReplacement(node.packageName?.toString())
            ServiceRuntimeState.markNode("替换写入成功（$reason）")
            if (isWeChat) DiagnosticLog.add("WX-WRITE", "success reason=$reason")
        } else {
            ServiceRuntimeState.markError(
                "无法写入目标输入框：${node.packageName?.toString().orEmpty()}"
            )
            ServiceRuntimeState.markNode("找到输入框，但写入失败（$reason）")
            if (isWeChat) DiagnosticLog.add("WX-WRITE", "FAILED reason=$reason")
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
        val isWeChat = node.packageName?.toString() == WECHAT_PACKAGE
        val focusOk = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (isWeChat) DiagnosticLog.add("WX-ACTION", "ACTION_FOCUS=$focusOk")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (isWeChat) DiagnosticLog.add("WX-ACTION", "ACTION_SET_TEXT=$setTextOk")
        if (setTextOk) {
            restoreSelection(node, text, oldSelectionEnd, oldLength)
            return true
        }

        if (!isWeChat) return false
        DiagnosticLog.add("WX-ACTION", "falling back to clipboard paste")
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
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        if (node.packageName?.toString() == WECHAT_PACKAGE) {
            DiagnosticLog.add("WX-ACTION", "ACTION_SET_SELECTION=$ok pos=$newSelection")
        }
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
        val focusOk = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val selectOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        DiagnosticLog.add("WX-PASTE", "focus=$focusOk selectAll=$selectOk textLen=${node.text?.length ?: 0}")

        clipboard.setPrimaryClip(ClipData.newPlainText("typing-replacer", text))
        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        DiagnosticLog.add("WX-PASTE", "ACTION_PASTE=$pasteOk")

        handler.postDelayed({
            try {
                if (oldClip != null) {
                    clipboard.setPrimaryClip(oldClip)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                DiagnosticLog.add("WX-PASTE", "clipboard restored")
            } catch (e: Exception) {
                DiagnosticLog.add("WX-PASTE", "clipboard restore error=${e.javaClass.simpleName}")
            }
        }, 180L)

        if (pasteOk) ServiceRuntimeState.markNode("微信兼容粘贴写入成功")
        return pasteOk
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

    private fun nodeSummary(node: AccessibilityNodeInfo): String {
        val actions = node.actionList.joinToString(",") { actionName(it.id) }
        return buildString {
            append("pkg=${node.packageName?.toString().orEmpty()}")
            append(" class=${node.className?.toString().orEmpty()}")
            append(" id=${node.viewIdResourceName.orEmpty()}")
            append(" editable=${node.isEditable}")
            append(" focusable=${node.isFocusable}")
            append(" focused=${node.isFocused}")
            append(" a11yFocused=${node.isAccessibilityFocused}")
            append(" enabled=${node.isEnabled}")
            append(" visible=${node.isVisibleToUser}")
            append(" textLen=${node.text?.length ?: -1}")
            append(" actions=[$actions]")
        }
    }

    private fun windowSummary(): String {
        return windows.joinToString(" | ") { window ->
            val root = window.root
            try {
                "type=${window.type},active=${window.isActive},focused=${window.isFocused},rootPkg=${root?.packageName?.toString().orEmpty()},rootClass=${root?.className?.toString().orEmpty()}"
            } finally {
                root?.recycle()
            }
        }
    }

    private fun eventName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "SELECTION_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        else -> "EVENT_$type"
    }

    private fun actionName(id: Int): String = when (id) {
        AccessibilityNodeInfo.ACTION_FOCUS -> "FOCUS"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "CLEAR_FOCUS"
        AccessibilityNodeInfo.ACTION_CLICK -> "CLICK"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "SET_TEXT"
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> "SET_SELECTION"
        AccessibilityNodeInfo.ACTION_PASTE -> "PASTE"
        AccessibilityNodeInfo.ACTION_COPY -> "COPY"
        AccessibilityNodeInfo.ACTION_CUT -> "CUT"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "LONG_CLICK"
        else -> id.toString()
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
