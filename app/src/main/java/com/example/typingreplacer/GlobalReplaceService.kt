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

class GlobalReplaceService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var repository: ReplacementRepository
    private lateinit var prefs: SharedPreferences

    private var cachedRules: List<ReplacementRule> = emptyList()
    private var compatibilityScanEnabled = true
    private var lockReplacementEnabled = false
    private var session: InputSession? = null
    private var weChatImeSession: InputSession? = null
    private var listenerRegistered = false
    private var lastWeChatTreeDumpAt = 0L

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
        weChatImeSession = null
        ServiceRuntimeState.markConnected()
        DiagnosticLog.add(
            "SERVICE",
            "connected rules=${cachedRules.size} scan=$compatibilityScanEnabled lock=$lockReplacementEnabled sdk=${Build.VERSION.SDK_INT}"
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
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED

        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 0

        var flags =
            info.flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
        }

        info.flags = flags
        serviceInfo = info
        DiagnosticLog.add(
            "SERVICE",
            "configured eventTypes=${info.eventTypes} flags=${info.flags} imeFlag=${Build.VERSION.SDK_INT >= 33}"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPackage = event.packageName?.toString()
        if (eventPackage == packageName) return
        ServiceRuntimeState.markEvent(eventPackage)

        if (eventPackage == WECHAT_PACKAGE) {
            logWeChatEvent(event)
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (eventPackage == WECHAT_PACKAGE) {
                    handleWeChatTextChanged(event)
                    return
                }

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
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleFocusedScan(35L)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                session = null
                if (eventPackage == WECHAT_PACKAGE) {
                    weChatImeSession = null
                    DiagnosticLog.add("WX-FLOW", "window changed; sessions cleared")
                }
                scheduleFocusedScan(90L)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val clicked = event.source
                var sendLike = false
                if (clicked != null) {
                    try {
                        sendLike = isLikelySendButton(clicked)
                    } finally {
                        clicked.recycle()
                    }
                }

                if (!sendLike && eventPackage == WECHAT_PACKAGE) {
                    sendLike = isLikelySendEvent(event)
                }

                if (sendLike) {
                    DiagnosticLog.add("FLOW", "send-like click pkg=$eventPackage")
                    session?.unlockForSend()
                    weChatImeSession?.unlockForSend()
                }
                scheduleFocusedScan(50L)
            }
        }
    }

    private fun handleWeChatTextChanged(event: AccessibilityEvent) {
        val source = event.source
        if (source != null) {
            try {
                val refreshed = try {
                    source.refresh()
                } catch (_: Throwable) {
                    false
                }
                DiagnosticLog.add(
                    "WX-FLOW",
                    "text source refresh=$refreshed ${nodeSummary(source)}"
                )
                if (isEditableTarget(source)) {
                    DiagnosticLog.add("WX-FLOW", "text-event using node path")
                    processNode(source, event, "text-event")
                    return
                }
            } finally {
                source.recycle()
            }
        }

        if (processWeChatImeTextEvent(event, "text-event-ime")) {
            return
        }

        DiagnosticLog.add("WX-FLOW", "no usable node and IME event path unavailable; scheduling scan")
        scheduleFocusedScan(35L)
    }

    private fun processWeChatImeTextEvent(
        event: AccessibilityEvent,
        reason: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            DiagnosticLog.add("WX-IME", "unsupported sdk=${Build.VERSION.SDK_INT}")
            return false
        }

        val current = event.text.firstOrNull()?.toString()
        if (current == null) {
            DiagnosticLog.add(
                "WX-IME",
                "event.text missing count=${event.text.size} beforeLen=${event.beforeText?.length ?: -1}"
            )
            return processWeChatImeSnapshot(reason = "$reason-snapshot")
        }

        DiagnosticLog.add(
            "WX-IME",
            "event text available len=${current.length} beforeLen=${event.beforeText?.length ?: -1} from=${event.fromIndex} add=${event.addedCount} remove=${event.removedCount}"
        )

        val activeSession = getWeChatImeSession(event.windowId)
        return processWeChatImeCurrent(
            current = current,
            event = event,
            reason = reason,
            activeSession = activeSession,
        )
    }

    private fun processWeChatImeSnapshot(reason: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false

        val snapshot = WeChatImeBridge.snapshot(this)
        DiagnosticLog.add(
            "WX-IME",
            "snapshot ready=${snapshot.ready} editor=${snapshot.editorPackage} started=${snapshot.inputStarted} conn=${snapshot.hasConnection} len=${snapshot.text?.length ?: -1} offset=${snapshot.offset} sel=${snapshot.selectionStart}..${snapshot.selectionEnd} err=${snapshot.error}"
        )

        if (!snapshot.ready || snapshot.editorPackage != WECHAT_PACKAGE) return false
        val current = snapshot.text ?: return false

        val activeSession = getWeChatImeSession(WECHAT_SYNTHETIC_WINDOW_ID)
        return processWeChatImeCurrent(
            current = current,
            event = null,
            reason = reason,
            activeSession = activeSession,
        )
    }

    private fun processWeChatImeCurrent(
        current: String,
        event: AccessibilityEvent?,
        reason: String,
        activeSession: InputSession,
    ): Boolean {
        val now = System.currentTimeMillis()

        if (
            now - activeSession.lastWriteAt <= SELF_WRITE_GUARD_MS &&
            current == activeSession.lastWritten
        ) {
            DiagnosticLog.add("WX-IME", "self-write confirmed len=${current.length}")
            return true
        }

        if (current.isEmpty()) {
            if (
                lockReplacementEnabled &&
                activeSession.lockActive &&
                activeSession.lockedText.isNotEmpty() &&
                now > activeSession.allowClearUntil
            ) {
                DiagnosticLog.add(
                    "WX-LOCK",
                    "IME empty -> restore locked len=${activeSession.lockedText.length}"
                )
                return writeWeChatIme(
                    current = current,
                    target = activeSession.lockedText,
                    reason = "lock-restore-empty-ime",
                    activeSession = activeSession,
                )
            }

            activeSession.lastWritten = ""
            activeSession.lockedText = ""
            activeSession.lockActive = false
            DiagnosticLog.add("WX-IME", "empty accepted / session unlocked")
            return true
        }

        if (
            lockReplacementEnabled &&
            activeSession.lockActive &&
            activeSession.lastWritten.isNotEmpty() &&
            current.length < activeSession.lastWritten.length &&
            now > activeSession.allowClearUntil
        ) {
            val restore = activeSession.lockedText.ifEmpty { activeSession.lastWritten }
            DiagnosticLog.add(
                "WX-LOCK",
                "IME delete detected currentLen=${current.length} lastLen=${activeSession.lastWritten.length} restoreLen=${restore.length}"
            )
            return writeWeChatIme(
                current = current,
                target = restore,
                reason = "lock-restore-delete-ime",
                activeSession = activeSession,
            )
        }

        val change = if (event == null) null else IncrementalTransformer.Change(
            beforeText = event.beforeText?.toString(),
            fromIndex = event.fromIndex,
            addedCount = event.addedCount,
            removedCount = event.removedCount,
        )

        val target = IncrementalTransformer.transform(
            current = current,
            previousOutput = activeSession.lastWritten,
            change = change,
            rules = cachedRules,
        )

        val replacementOccurred = target != current
        DiagnosticLog.add(
            "WX-TRANSFORM",
            "IME replacement=$replacementOccurred currentLen=${current.length} targetLen=${target.length}"
        )

        if (!replacementOccurred) {
            activeSession.lastWritten = current
            if (lockReplacementEnabled && activeSession.lockActive) {
                activeSession.lockedText = current
            }
            return true
        }

        val ok = writeWeChatIme(
            current = current,
            target = target,
            reason = reason,
            activeSession = activeSession,
        )

        if (ok && lockReplacementEnabled) {
            activeSession.lockActive = true
            activeSession.lockedText = target
        }
        return ok
    }

    private fun writeWeChatIme(
        current: String,
        target: String,
        reason: String,
        activeSession: InputSession,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false

        val result = WeChatImeBridge.replaceAll(this, current, target)
        DiagnosticLog.add(
            "WX-IME-WRITE",
            "reason=$reason issued=${result.issued} editor=${result.editorPackage} started=${result.inputStarted} conn=${result.hasConnection} surroundingLen=${result.surroundingLength} match=${result.surroundingMatchesEvent} sel=${result.selectionStart}..${result.selectionEnd} err=${result.error}"
        )

        if (!result.issued) {
            ServiceRuntimeState.markError("微信 InputConnection 写入失败：${result.error}")
            return false
        }

        activeSession.lastWritten = target
        activeSession.lastWriteAt = System.currentTimeMillis()
        if (activeSession.lockActive) activeSession.lockedText = target
        ServiceRuntimeState.markReplacement(WECHAT_PACKAGE)
        ServiceRuntimeState.markNode("微信 Accessibility InputConnection 已提交写入（$reason）")
        return true
    }

    private fun getWeChatImeSession(windowId: Int): InputSession {
        val key = NodeKey(
            windowId = windowId,
            packageName = WECHAT_PACKAGE,
            viewId = "@accessibility-ime",
            className = "InputConnection",
            bounds = "",
        )

        val current = weChatImeSession
        if (current != null && current.key.packageName == WECHAT_PACKAGE) {
            return current
        }

        return InputSession(key).also { weChatImeSession = it }
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
        weChatImeSession = null
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
            session?.clearLock()
            weChatImeSession?.clearLock()
        }
    }

    private fun scheduleFocusedScan(delayMs: Long) {
        handler.removeCallbacks(delayedScanRunnable)
        handler.postDelayed(delayedScanRunnable, delayMs)
    }

    private fun inspectFocusedInput(reason: String) {
        val activePkg = activeRootPackage()
        if (activePkg == WECHAT_PACKAGE) {
            DiagnosticLog.add("WX-SCAN", "begin reason=$reason windows=${windows.size}")
        }

        val node = findFocusedEditable()
        if (node != null) {
            try {
                val pkg = node.packageName?.toString()
                if (pkg == packageName) return
                ServiceRuntimeState.markNode("已找到焦点输入框：${pkg.orEmpty()}（$reason）")
                if (pkg == WECHAT_PACKAGE) {
                    DiagnosticLog.add("WX-SCAN", "node path found ${nodeSummary(node)} reason=$reason")
                }
                processNode(node, null, reason)
                return
            } finally {
                node.recycle()
            }
        }

        if (activePkg == WECHAT_PACKAGE) {
            if (processWeChatImeSnapshot("$reason-ime")) {
                ServiceRuntimeState.markNode("微信节点为空，已使用 Accessibility InputConnection（$reason）")
                return
            }

            DiagnosticLog.add("WX-SCAN", "no focused editable; ${windowSummary()}")
            maybeDumpWeChatTree()
        }

        ServiceRuntimeState.markNode("未找到当前焦点输入框（$reason）")
    }

    private fun activeRootPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
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
                }

                findDeepFocusedEditable(activeRoot)?.let { return it }
                findBestEditableCandidate(activeRoot)?.let { return it }
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
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    if (root.packageName?.toString() == WECHAT_PACKAGE) {
                        DiagnosticLog.add(
                            "WX-FOCUS",
                            "window(type=${window.type},active=${window.isActive},focused=${window.isFocused}) findFocus -> ${nodeSummary(focused)}"
                        )
                    }
                    if (isEditableTarget(focused)) return focused
                    focused.recycle()
                }

                findDeepFocusedEditable(root)?.let { return it }
                findBestEditableCandidate(root)?.let { return it }
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

    private fun findBestEditableCandidate(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_TREE_DEPTH) return

            val score = editableCandidateScore(node)
            if (score > bestScore) {
                best?.recycle()
                best = AccessibilityNodeInfo.obtain(node)
                bestScore = score
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }

        visit(root, 0)
        return if (bestScore >= MIN_EDITABLE_CANDIDATE_SCORE) best else {
            best?.recycle()
            null
        }
    }

    private fun editableCandidateScore(node: AccessibilityNodeInfo): Int {
        if (node.packageName?.toString() == packageName) return 0
        var score = 0
        if (node.isEditable) score += 100
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) score += 100
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) score += 80
        if (node.isFocused) score += 50
        if (node.isAccessibilityFocused) score += 30
        if (node.isFocusable) score += 20
        if (node.isEnabled) score += 10
        if (node.isVisibleToUser) score += 10
        if (node.textSelectionStart >= 0 || node.textSelectionEnd >= 0) score += 20
        return score
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
                "node reason=$reason len=${current.length} selection=${node.textSelectionStart}..${node.textSelectionEnd} lock=${activeSession.lockActive}"
            )
        }

        if (
            now - activeSession.lastWriteAt <= SELF_WRITE_GUARD_MS &&
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
                activeSession.clearLock()
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
        if (target == current) {
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
        val ok = setNodeTextCompat(node, text, oldSelectionEnd, oldLength)
        if (ok) {
            activeSession.lastWritten = text
            activeSession.lastWriteAt = System.currentTimeMillis()
            if (activeSession.lockActive) activeSession.lockedText = text
            ServiceRuntimeState.markReplacement(node.packageName?.toString())
            ServiceRuntimeState.markNode("替换写入成功（$reason）")
            if (isWeChat) DiagnosticLog.add("WX-WRITE", "node success reason=$reason")
        } else {
            ServiceRuntimeState.markError(
                "无法写入目标输入框：${node.packageName?.toString().orEmpty()}"
            )
            ServiceRuntimeState.markNode("找到输入框，但写入失败（$reason）")
            if (isWeChat) DiagnosticLog.add("WX-WRITE", "node FAILED reason=$reason")
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
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newSelection)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newSelection)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun pasteFallback(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val oldClip = clipboard.primaryClip
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                node.text?.length ?: 0,
            )
        }

        val focusOk = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val selectOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        clipboard.setPrimaryClip(ClipData.newPlainText("typing-replacer", text))
        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        DiagnosticLog.add(
            "WX-PASTE",
            "focus=$focusOk selectAll=$selectOk paste=$pasteOk textLen=${node.text?.length ?: 0}"
        )

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

    private fun isLikelySendEvent(event: AccessibilityEvent): Boolean {
        val text = event.text.joinToString(" ").trim().lowercase()
        val description = event.contentDescription?.toString().orEmpty().trim().lowercase()
        return text == "发送" ||
            text.contains("发送消息") ||
            description == "发送" ||
            description.contains("发送消息")
    }

    private fun logWeChatEvent(event: AccessibilityEvent) {
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
                    append(" eventTextCount=${event.text.size}")
                    append(" eventTextLen=${event.text.firstOrNull()?.length ?: -1}")
                    append(" contentChange=${event.contentChangeTypes}")
                    append(" source=")
                    append(if (source == null) "null" else nodeSummary(source))
                }
            )
        } finally {
            source?.recycle()
        }
    }

    private fun maybeDumpWeChatTree() {
        val now = System.currentTimeMillis()
        if (now - lastWeChatTreeDumpAt < TREE_DUMP_INTERVAL_MS) return
        lastWeChatTreeDumpAt = now

        val root = rootInActiveWindow ?: run {
            DiagnosticLog.add("WX-TREE", "root=null")
            return
        }

        try {
            var count = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > MAX_TREE_DEPTH || count >= MAX_TREE_LOG_NODES) return
                count++
                DiagnosticLog.add(
                    "WX-TREE",
                    "d=$depth child=${node.childCount} ${nodeSummary(node)}"
                )
                for (i in 0 until node.childCount) {
                    if (count >= MAX_TREE_LOG_NODES) break
                    val child = node.getChild(i) ?: continue
                    try {
                        visit(child, depth + 1)
                    } finally {
                        child.recycle()
                    }
                }
            }
            visit(root, 0)
            DiagnosticLog.add("WX-TREE", "dump complete nodes=$count")
        } finally {
            root.recycle()
        }
    }

    private fun nodeSummary(node: AccessibilityNodeInfo): String {
        val actions = node.actionList.joinToString(",") { actionName(it.id) }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return buildString {
            append("pkg=${node.packageName?.toString().orEmpty()}")
            append(" class=${node.className?.toString().orEmpty()}")
            append(" id=${node.viewIdResourceName.orEmpty()}")
            append(" child=${node.childCount}")
            append(" bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
            append(" editable=${node.isEditable}")
            append(" focusable=${node.isFocusable}")
            append(" focused=${node.isFocused}")
            append(" a11yFocused=${node.isAccessibilityFocused}")
            append(" enabled=${node.isEnabled}")
            append(" visible=${node.isVisibleToUser}")
            append(" textLen=${node.text?.length ?: -1}")
            append(" sel=${node.textSelectionStart}..${node.textSelectionEnd}")
            append(" actions=[$actions]")
        }
    }

    private fun windowSummary(): String {
        return windows.joinToString(" | ") { window ->
            val root = window.root
            try {
                "type=${window.type},active=${window.isActive},focused=${window.isFocused},rootPkg=${root?.packageName?.toString().orEmpty()},rootClass=${root?.className?.toString().orEmpty()},child=${root?.childCount ?: -1}"
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
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
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
    ) {
        fun clearLock() {
            lockActive = false
            lockedText = ""
        }

        fun unlockForSend() {
            allowClearUntil = System.currentTimeMillis() + SEND_CLEAR_GRACE_MS
            clearLock()
        }
    }

    private companion object {
        const val TAG = "TypingReplacer"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val SELF_WRITE_GUARD_MS = 1200L
        const val COMPATIBILITY_SCAN_INTERVAL_MS = 700L
        const val SEND_CLEAR_GRACE_MS = 1800L
        const val TREE_DUMP_INTERVAL_MS = 2000L
        const val MAX_TREE_LOG_NODES = 80
        const val MAX_TREE_DEPTH = 8
        const val MIN_EDITABLE_CANDIDATE_SCORE = 80
        const val WECHAT_SYNTHETIC_WINDOW_ID = -1000
    }
}
