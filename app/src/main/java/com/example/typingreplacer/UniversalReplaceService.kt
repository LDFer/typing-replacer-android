package com.example.typingreplacer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
 * V3 compatibility service.
 *
 * Fast path: editable AccessibilityNodeInfo.
 * Android 13+ fallback: current accessibility InputConnection for any app.
 * This avoids hard-coding compatibility logic to WeChat only.
 */
class UniversalReplaceService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repository: ReplacementRepository
    private lateinit var prefs: SharedPreferences

    private var cachedRules: List<ReplacementRule> = emptyList()
    private var compatibilityScanEnabled = true
    private var lockReplacementEnabled = false
    private var nodeSession: InputSession? = null
    private var imeSession: InputSession? = null
    private var listenerRegistered = false
    private var lastContentScanAt = 0L

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                ReplacementRepository.KEY_RULES -> reloadRules()
                AppSettings.KEY_COMPATIBILITY_SCAN,
                AppSettings.KEY_LOCK_REPLACEMENT -> reloadSettings()
            }
        }

    private val delayedScanRunnable = Runnable {
        inspectCurrentEditor("event-scan")
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            ServiceRuntimeState.markHeartbeat()
            if (compatibilityScanEnabled) inspectCurrentEditor("compatibility-scan")
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
        nodeSession = null
        imeSession = null
        ServiceRuntimeState.markConnected()
        DiagnosticLog.add(
            "SERVICE",
            "universal connected rules=${cachedRules.size} scan=$compatibilityScanEnabled lock=$lockReplacementEnabled sdk=${Build.VERSION.SDK_INT}"
        )
        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)
        Log.i(TAG, "Universal accessibility service connected")
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
            "universal configured eventTypes=${info.eventTypes} flags=${info.flags} imeFlag=${Build.VERSION.SDK_INT >= 33}"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        ServiceRuntimeState.markEvent(pkg)
        logEvent(pkg, event)

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (handleTextChanged(pkg, event)) return
                scheduleScan(TEXT_FALLBACK_SCAN_DELAY_MS)
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> scheduleScan(FOCUS_SCAN_DELAY_MS)

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Selection events are often emitted after our own commitText().
                // Text changes are handled synchronously, so avoid scanning here.
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentScanAt >= CONTENT_SCAN_THROTTLE_MS) {
                    lastContentScanAt = now
                    scheduleScan(CONTENT_SCAN_DELAY_MS)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                nodeSession = null
                if (imeSession?.key?.packageName != pkg) imeSession = null
                scheduleScan(WINDOW_SCAN_DELAY_MS)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (isLikelySendEvent(event)) {
                    nodeSession?.unlockForSend()
                    imeSession?.unlockForSend()
                    DiagnosticLog.add("FLOW", "send-like click pkg=$pkg")
                }
                scheduleScan(CLICK_SCAN_DELAY_MS)
            }
        }
    }

    private fun handleTextChanged(pkg: String, event: AccessibilityEvent): Boolean {
        val source = event.source
        if (source != null) {
            try {
                if (isEditableTarget(source)) {
                    DiagnosticLog.add(
                        "APP-NODE",
                        "text-event pkg=$pkg ${nodeSummary(source)}"
                    )
                    if (processNode(source, event, "text-event")) return true
                }
            } finally {
                source.recycle()
            }
        }

        if (Build.VERSION.SDK_INT >= 33 && processImeEvent(pkg, event, "text-event-ime")) {
            return true
        }
        return false
    }

    private fun processImeEvent(
        pkg: String,
        event: AccessibilityEvent,
        reason: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false

        val derived = deriveCurrentText(event)
        if (derived == null) {
            DiagnosticLog.add(
                "APP-IME",
                "event-text-unavailable pkg=$pkg beforeLen=${event.beforeText?.length ?: -1}"
            )
            return processImeSnapshot(pkg, "$reason-snapshot")
        }

        if (!derived.confident) {
            val snapshot = AccessibilityImeBridge.snapshot(this, pkg)
            DiagnosticLog.add(
                "APP-IME",
                "uncertain-event pkg=$pkg mode=${derived.mode} rawLen=${derived.rawLength} snapshotReady=${snapshot.ready} conn=${snapshot.hasConnection} err=${snapshot.error}"
            )
            if (snapshot.ready && snapshot.text != null) {
                return processImeCurrent(
                    pkg = pkg,
                    current = snapshot.text,
                    event = null,
                    reason = "$reason-snapshot-confirmed",
                    activeSession = getImeSession(pkg, event.windowId),
                )
            }
            return false
        }

        DiagnosticLog.add(
            "APP-IME",
            "event pkg=$pkg mode=${derived.mode} len=${derived.text.length} beforeLen=${event.beforeText?.length ?: -1} from=${event.fromIndex} add=${event.addedCount} remove=${event.removedCount}"
        )
        return processImeCurrent(
            pkg = pkg,
            current = derived.text,
            event = event,
            reason = reason,
            activeSession = getImeSession(pkg, event.windowId),
        )
    }

    private fun deriveCurrentText(event: AccessibilityEvent): DerivedText? {
        val raw = event.text.firstOrNull()?.toString() ?: return null
        val before = event.beforeText?.toString()
        val from = event.fromIndex
        val added = event.addedCount
        val removed = event.removedCount

        if (before != null && from >= 0 && added >= 0 && removed >= 0) {
            val removalEnd = from + removed
            val expectedLength = before.length - removed + added
            if (from <= before.length && removalEnd <= before.length) {
                if (raw.length == expectedLength) {
                    return DerivedText(raw, true, "full-event", raw.length)
                }
                if (raw.length == added) {
                    val rebuilt = buildString(expectedLength.coerceAtLeast(0)) {
                        append(before, 0, from)
                        append(raw)
                        append(before, removalEnd, before.length)
                    }
                    if (rebuilt.length == expectedLength) {
                        return DerivedText(rebuilt, true, "delta-rebuilt", raw.length)
                    }
                }
            }
        }

        if (before == null && from == 0 && removed <= 0 && added >= 0 && raw.length == added) {
            return DerivedText(raw, true, "initial-event", raw.length)
        }

        return DerivedText(raw, false, "ambiguous-event", raw.length)
    }

    private fun processImeSnapshot(pkg: String?, reason: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val snapshot = AccessibilityImeBridge.snapshot(this, pkg)
        DiagnosticLog.add(
            "APP-IME",
            "snapshot expected=${pkg.orEmpty()} editor=${snapshot.editorPackage} ready=${snapshot.ready} started=${snapshot.inputStarted} conn=${snapshot.hasConnection} len=${snapshot.text?.length ?: -1} sel=${snapshot.selectionStart}..${snapshot.selectionEnd} err=${snapshot.error}"
        )
        if (!snapshot.ready || snapshot.text == null) return false
        if (snapshot.editorPackage == packageName || snapshot.editorPackage.isBlank()) return false
        return processImeCurrent(
            pkg = snapshot.editorPackage,
            current = snapshot.text,
            event = null,
            reason = reason,
            activeSession = getImeSession(snapshot.editorPackage, IME_SYNTHETIC_WINDOW_ID),
        )
    }

    private fun processImeCurrent(
        pkg: String,
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
            DiagnosticLog.add("APP-IME", "self-write pkg=$pkg len=${current.length}")
            return true
        }

        if (current.isEmpty()) {
            if (
                lockReplacementEnabled &&
                activeSession.lockActive &&
                activeSession.lockedText.isNotEmpty() &&
                now > activeSession.allowClearUntil
            ) {
                DiagnosticLog.add("APP-LOCK", "restore-empty pkg=$pkg len=${activeSession.lockedText.length}")
                return writeIme(
                    pkg,
                    current,
                    activeSession.lockedText,
                    "lock-restore-empty-ime",
                    activeSession,
                )
            }
            activeSession.lastWritten = ""
            activeSession.clearLock()
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
                "APP-LOCK",
                "restore-delete pkg=$pkg currentLen=${current.length} lastLen=${activeSession.lastWritten.length} restoreLen=${restore.length}"
            )
            return writeIme(pkg, current, restore, "lock-restore-delete-ime", activeSession)
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
        val hit = target != current
        DiagnosticLog.add(
            "APP-TRANSFORM",
            "pkg=$pkg replacement=$hit currentLen=${current.length} targetLen=${target.length}"
        )

        if (!hit) {
            activeSession.lastWritten = current
            if (lockReplacementEnabled && activeSession.lockActive) activeSession.lockedText = current
            return true
        }

        val ok = writeIme(pkg, current, target, reason, activeSession)
        if (ok && lockReplacementEnabled) {
            activeSession.lockActive = true
            activeSession.lockedText = target
        }
        return ok
    }

    private fun writeIme(
        pkg: String,
        current: String,
        target: String,
        reason: String,
        activeSession: InputSession,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        // Do not require getSurroundingText() here. Some Xiaomi/ROM/editor
        // combinations expose a valid connection but return surrounding-null.
        val result = AccessibilityImeBridge.replaceAll(
            service = this,
            expectedPackage = pkg,
            expectedCurrent = current,
            target = target,
            probeSurrounding = false,
        )
        DiagnosticLog.add(
            "APP-IME-WRITE",
            "pkg=$pkg reason=$reason issued=${result.issued} editor=${result.editorPackage} started=${result.inputStarted} conn=${result.hasConnection} surroundingLen=${result.surroundingLength} match=${result.surroundingMatchesExpected} err=${result.error}"
        )
        if (!result.issued) {
            ServiceRuntimeState.markError("InputConnection 写入失败：$pkg · ${result.error}")
            return false
        }
        activeSession.lastWritten = target
        activeSession.lastWriteAt = System.currentTimeMillis()
        if (activeSession.lockActive) activeSession.lockedText = target
        ServiceRuntimeState.markReplacement(pkg)
        ServiceRuntimeState.markNode("InputConnection 写入成功：$pkg（$reason）")
        return true
    }

    private fun processNode(
        node: AccessibilityNodeInfo,
        event: AccessibilityEvent?,
        reason: String,
    ): Boolean {
        if (!isEditableTarget(node)) return false
        val pkg = node.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return false
        val current = node.text?.toString() ?: ""
        val key = nodeKey(node)
        val now = System.currentTimeMillis()
        val activeSession = nodeSession?.takeIf { it.key == key }
            ?: InputSession(key).also { nodeSession = it }

        if (
            now - activeSession.lastWriteAt <= SELF_WRITE_GUARD_MS &&
            current == activeSession.lastWritten
        ) return true

        if (current.isEmpty()) {
            if (
                lockReplacementEnabled &&
                activeSession.lockActive &&
                activeSession.lockedText.isNotEmpty() &&
                now > activeSession.allowClearUntil
            ) {
                return writeNodeOrIme(
                    node,
                    pkg,
                    current,
                    activeSession.lockedText,
                    0,
                    0,
                    "lock-restore-empty",
                    activeSession,
                )
            }
            activeSession.lastWritten = ""
            activeSession.clearLock()
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
            return writeNodeOrIme(
                node,
                pkg,
                current,
                restore,
                node.textSelectionEnd,
                current.length,
                "lock-restore-delete",
                activeSession,
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
        if (target == current) {
            activeSession.lastWritten = current
            if (lockReplacementEnabled && activeSession.lockActive) activeSession.lockedText = current
            return true
        }

        val ok = writeNodeOrIme(
            node,
            pkg,
            current,
            target,
            node.textSelectionEnd,
            current.length,
            reason,
            activeSession,
        )
        if (ok && lockReplacementEnabled) {
            activeSession.lockActive = true
            activeSession.lockedText = target
        }
        return ok
    }

    private fun writeNodeOrIme(
        node: AccessibilityNodeInfo,
        pkg: String,
        current: String,
        target: String,
        oldSelectionEnd: Int,
        oldLength: Int,
        reason: String,
        activeSession: InputSession,
    ): Boolean {
        val focusOk = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target)
        }
        val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        DiagnosticLog.add(
            "APP-NODE-WRITE",
            "pkg=$pkg reason=$reason focus=$focusOk setText=$setTextOk currentLen=${current.length} targetLen=${target.length}"
        )
        if (setTextOk) {
            restoreSelection(node, target, oldSelectionEnd, oldLength)
            activeSession.lastWritten = target
            activeSession.lastWriteAt = System.currentTimeMillis()
            if (activeSession.lockActive) activeSession.lockedText = target
            ServiceRuntimeState.markReplacement(pkg)
            ServiceRuntimeState.markNode("AccessibilityNode 写入成功：$pkg（$reason）")
            return true
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val imeActive = getImeSession(pkg, node.windowId)
            val ok = writeIme(pkg, current, target, "$reason-node-fallback", imeActive)
            if (ok) {
                activeSession.lastWritten = target
                activeSession.lastWriteAt = System.currentTimeMillis()
                if (activeSession.lockActive) activeSession.lockedText = target
                return true
            }
        }

        ServiceRuntimeState.markError("节点和 InputConnection 均无法写入：$pkg")
        return false
    }

    private fun inspectCurrentEditor(reason: String) {
        val node = findFocusedEditable()
        if (node != null) {
            try {
                if (processNode(node, null, reason)) return
            } finally {
                node.recycle()
            }
        }

        if (Build.VERSION.SDK_INT >= 33 && processImeSnapshot(null, "$reason-ime")) return
        ServiceRuntimeState.markNode("未找到可用输入编辑器（$reason）")
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
        return node.className?.toString()?.contains("EditText", ignoreCase = true) == true && node.isFocusable
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

    private fun getImeSession(pkg: String, windowId: Int): InputSession {
        val current = imeSession
        if (current != null && current.key.packageName == pkg) return current
        val key = NodeKey(
            windowId = windowId,
            packageName = pkg,
            viewId = "@accessibility-ime",
            className = "InputConnection",
            bounds = "",
        )
        return InputSession(key).also { imeSession = it }
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

    private fun isLikelySendEvent(event: AccessibilityEvent): Boolean {
        val source = event.source
        if (source != null) {
            try {
                val id = source.viewIdResourceName?.lowercase().orEmpty()
                val text = (source.text?.toString() ?: source.contentDescription?.toString()).orEmpty()
                    .trim().lowercase()
                if (
                    id.contains("send") || id.contains("btn_send") ||
                    text == "发送" || text == "send" || text.contains("发送消息")
                ) return true
            } finally {
                source.recycle()
            }
        }
        val eventText = event.text.joinToString(" ").trim().lowercase()
        val description = event.contentDescription?.toString().orEmpty().trim().lowercase()
        return eventText == "发送" || eventText.contains("发送消息") ||
            description == "发送" || description.contains("发送消息")
    }

    private fun logEvent(pkg: String, event: AccessibilityEvent) {
        val source = event.source
        try {
            DiagnosticLog.add(
                "APP-EVENT",
                buildString {
                    append("pkg=$pkg type=${eventName(event.eventType)}")
                    append(" win=${event.windowId}")
                    append(" from=${event.fromIndex}")
                    append(" add=${event.addedCount}")
                    append(" remove=${event.removedCount}")
                    append(" beforeLen=${event.beforeText?.length ?: -1}")
                    append(" eventTextCount=${event.text.size}")
                    append(" eventTextLen=${event.text.firstOrNull()?.length ?: -1}")
                    append(" source=")
                    append(if (source == null) "null" else nodeSummary(source))
                }
            )
        } finally {
            source?.recycle()
        }
    }

    private fun nodeSummary(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return buildString {
            append("class=${node.className?.toString().orEmpty()}")
            append(" id=${node.viewIdResourceName.orEmpty()}")
            append(" editable=${node.isEditable}")
            append(" focusable=${node.isFocusable}")
            append(" focused=${node.isFocused}")
            append(" enabled=${node.isEnabled}")
            append(" visible=${node.isVisibleToUser}")
            append(" textLen=${node.text?.length ?: -1}")
            append(" sel=${node.textSelectionStart}..${node.textSelectionEnd}")
            append(" bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
            append(" actions=${node.actionList.joinToString(",") { it.id.toString() }}")
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

    private fun reloadRules() {
        cachedRules = repository.loadRules()
        DiagnosticLog.add("RULE", "universal reloaded count=${cachedRules.size}")
    }

    private fun reloadSettings() {
        val settings = AppSettings(this)
        compatibilityScanEnabled = settings.compatibilityScanEnabled
        lockReplacementEnabled = settings.lockReplacementEnabled
        DiagnosticLog.add("SETTINGS", "universal scan=$compatibilityScanEnabled lock=$lockReplacementEnabled")
        if (!lockReplacementEnabled) {
            nodeSession?.clearLock()
            imeSession?.clearLock()
        }
    }

    private fun scheduleScan(delayMs: Long) {
        handler.removeCallbacks(delayedScanRunnable)
        handler.postDelayed(delayedScanRunnable, delayMs)
    }

    override fun onInterrupt() {
        ServiceRuntimeState.markError("系统调用了 onInterrupt")
        DiagnosticLog.add("SERVICE", "universal onInterrupt")
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
        nodeSession = null
        imeSession = null
        ServiceRuntimeState.markDisconnected(reason)
        DiagnosticLog.add("SERVICE", "universal $reason")
    }

    private data class DerivedText(
        val text: String,
        val confident: Boolean,
        val mode: String,
        val rawLength: Int,
    )

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
        const val TAG = "TypingReplacerUniversal"
        const val SELF_WRITE_GUARD_MS = 1200L
        const val COMPATIBILITY_SCAN_INTERVAL_MS = 900L
        const val SEND_CLEAR_GRACE_MS = 1800L
        const val CONTENT_SCAN_THROTTLE_MS = 400L
        const val CONTENT_SCAN_DELAY_MS = 120L
        const val TEXT_FALLBACK_SCAN_DELAY_MS = 70L
        const val FOCUS_SCAN_DELAY_MS = 80L
        const val CLICK_SCAN_DELAY_MS = 100L
        const val WINDOW_SCAN_DELAY_MS = 140L
        const val MAX_TREE_DEPTH = 8
        const val MIN_EDITABLE_CANDIDATE_SCORE = 80
        const val IME_SYNTHETIC_WINDOW_ID = -2000
    }
}
