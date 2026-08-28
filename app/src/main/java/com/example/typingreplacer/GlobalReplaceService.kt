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
 * Universal replacement service.
 *
 * Node path: use AccessibilityNodeInfo when the target exposes a real editable node.
 * IME-only path (Android 13+): never rewrite a live InputConnection directly from
 * TYPE_VIEW_TEXT_CHANGED. Accessibility events can lag behind the editor snapshot,
 * while speech/predictive IMEs keep their own composing buffer. Rewriting during that
 * window can make the IME append its stale buffer to our already-rewritten text.
 *
 * Instead, IME-only edits are debounced. The service waits until the editor is stable,
 * reads the final text again, performs one replacement, then re-arms lock mode.
 * A locked, clearly manual deletion is the only immediate IME write exception.
 */
class GlobalReplaceService : AccessibilityService() {

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

    private val imeSettleRunnable = Runnable {
        val session = imeSession ?: return@Runnable
        if (!session.imeSettlePending) return@Runnable

        val now = System.currentTimeMillis()
        val remaining = session.imeSettleUntil - now
        if (remaining > 0L) {
            handler.postDelayed(imeSettleRunnable, remaining.coerceAtLeast(1L))
            return@Runnable
        }

        processImeSnapshot(session.key.packageName, "ime-stable-settle")
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
            "stable-ime connected rules=${cachedRules.size} scan=$compatibilityScanEnabled lock=$lockReplacementEnabled sdk=${Build.VERSION.SDK_INT}",
        )

        handler.removeCallbacks(heartbeatRunnable)
        handler.post(heartbeatRunnable)
        Log.i(TAG, "Stable IME accessibility service connected")
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
                AcccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
        }
        info.flags = flags
        serviceInfo = info

        DiagnosticLog.add(
            "SERVICE",
            "stable-ime configured eventTypes=${info.eventTypes} flags=${info.flags} imeFlag=${Build.VERSION.SDK_INT >= 33}",
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
                // Selection churn is extremely noisy after commitText and during speech.
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
                // Do not clear the IME session just because the keyboard/SystemUI
                // opened its own window. The actual editor package is verified by
                // AccessibilityImeBridge and getImeSession() replaces the session
                // only when the active editor itself changes.
                scheduleScan(WINDOW_SCAN_DELAY_MS)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (isLikelySendEvent(event)) {
                    nodeSession?.unlockForSend()
                    imeSession?.unlockForSend()
                    handler.removeCallbacks(imeSettleRunnable)
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
                    DiagnosticLog.add("APP-NODE", "text-event pkg=$pkg ${nodeSummary(source)}")
                    if (processNode(source, event, "text-event")) return true
                }
            } finally {
                source.recycle()
            }
        }

        return Build.VERSION.SDK_INT >= 33 && processImeEvent(pkg, event)
    }

    private fun processImeEvent(pkg: String, event: AccessibilityEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false

        val session = getImeSession(pkg, event.windowId)
        val derived = deriveCurrentText(event)

        if (derived == null) {
            DiagnosticLog.add(
                "APP-IME",
                "event-text-unavailable pkg=$pkg beforeLen=${event.beforeText?.length ?: -1}",
            )
            val snapshot = AccessibilityImeBridge.snapshot(this, pkg)
            logImeSnapshot(pkg, snapshot)
            if (!snapshot.ready || snapshot.text == null) {
                markImeEditForSettle(pkg, event, session, null)
                return false
            }
            return processImeCurrent(
                pkg = pkg,
                current = snapshot.text,
                event = event,
                reason = "text-event-ime-snapshot",
                activeSession = session,
            )
        }

        DiagnosticLog.add(
            "APP-IME",
            "event pkg=$pkg mode=${derived.mode} len=${derived.text.length} beforeLen=${event.beforeText?.length ?: -1} from=${event.fromIndex} add=${event.addedCount} remove=${event.removedCount}",
        )

        if (!derived.confident) {
            val snapshot = AccessibilityImeBridge.snapshot(this, pkg)
            DiagnosticLog.add(
                "APP-IME",
                "uncertain-event pkg=$pkg mode=${derived.mode} rawLen=${derived.rawLength} snapshotReady=${snapshot.ready} conn=${snapshot.hasConnection} err=${snapshot.error}",
            )
            if (snapshot.ready && snapshot.text != null) {
                return processImeCurrent(
                    pkg = pkg,
                    current = snapshot.text,
                    event = event,
                    reason = "text-event-ime-snapshot-confirmed",
                    activeSession = session,
                )
            }
            markImeEditForSettle(pkg, event, session, null)
            return false
        }

        return processImeCurrent(
            pkg = pkg,
            current = derived.text,
            event = event,
            reason = "text-event-ime",
            activeSession = session,
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
        logImeSnapshot(pkg, snapshot)
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

    private fun logImeSnapshot(expectedPkg: String?, snapshot: AccessibilityImeBridge.Snapshot) {
        DiagnosticLog.add(
            "APP-IME",
            "snapshot expected=${expectedPkg.orEmpty()} editor=${snapshot.editorPackage} ready=${snapshot.ready} started=${snapshot.inputStarted} conn=${snapshot.hasConnection} len=${snapshot.text?.length ?: -1} sel=${snapshot.selectionStart}..${snapshot.selectionEnd} err=${snapshot.error}",
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

        val composing = updateCompositionState(pkg, event, activeSession)

        // Locked manual deletion remains immediate. It is deliberately evaluated
        // before the IME stability queue so the protected text visibly snaps back.
        if (shouldRestoreLockedDeletion(event, current, activeSession, now, composing)) {
            val restore = activeSession.lockedText.ifEmpty { activeSession.lastWritten }
            DiagnosticLog.add(
                "APP-LOCK",
                "restore-delete pkg=$pkg currentLen=${current.length} lastLen=${activeSession.lastWritten.length} restoreLen=${restore.length}",
            )
            return writeIme(pkg, current, restore, "lock-restore-delete-ime", activeSession)
        }

        if (event != null) {
            markImeEditForSettle(pkg, event, activeSession, current)
            return true
        }

        // A compatibility/content scan may observe a newer editor state before the
        // queued TEXT_CHANGED callback arrives. Never transform that snapshot until
        // the IME-only stream has been quiet for the configured settle interval.
        if (activeSession.imeSettlePending) {
            val remaining = activeSession.imeSettleUntil - now
            if (remaining > 0L) {
                DiagnosticLog.add(
                    "APP-COMPOSE",
                    "defer-transform pkg=$pkg len=${current.length} pending=true reason=ime-stability remainingMs=$remaining",
                )
                scheduleImeSettle(activeSession)
                return true
            }

            activeSession.imeSettlePending = false
            activeSession.compositionPending = false
            activeSession.compositionUntil = 0L
            DiagnosticLog.add(
                "APP-COMPOSE",
                "settled pkg=$pkg len=${current.length}; stable-ime-pass",
            )
        }

        if (current.isEmpty()) {
            activeSession.lastWritten = ""
            activeSession.clearLock()
            return true
        }

        val target = IncrementalTransformer.transform(
            current = current,
            previousOutput = activeSession.lastWritten,
            change = null,
            rules = cachedRules,
        )
        val hit = target != current
        DiagnosticLog.add(
            "APP-TRANSFORM",
            "pkg=$pkg replacement=$hit currentLen=${current.length} targetLen=${target.length} composing=false settle=false stableIme=true",
        )

        if (!hit) {
            activeSession.lastWritten = current
            if (lockReplacementEnabled && activeSession.lockActive) {
                activeSession.lockedText = current
            }
            return true
        }

        val ok = writeIme(pkg, current, target, reason, activeSession)
        if (ok && lockReplacementEnabled) {
            activeSession.lockActive = true
            activeSession.lockedText = target
            DiagnosticLog.add("APP-LOCK", "armed pkg=$pkg len=${target.length}")
        }
        return ok
    }

    private fun markImeEditForSettle(
        pkg: String,
        event: AccessibilityEvent,
        activeSession: InputSession,
        current: String?,
    ) {
        val now = System.currentTimeMillis()
        val batch = isImeBatchEdit(event)
        val composing = now < activeSession.compositionUntil || batch
        val delay = if (composing) IME_COMPOSING_SETTLE_MS else IME_SINGLE_EDIT_SETTLE_MS

        activeSession.imeSettlePending = true
        activeSession.imeSettleUntil = now + delay
        activeSession.lastObservedAt = now

        DiagnosticLog.add(
            "APP-COMPOSE",
            "defer-transform pkg=$pkg len=${current?.length ?: -1} pending=true reason=ime-stability delayMs=$delay batch=$batch add=${event.addedCount} remove=${event.removedCount}",
        )
        scheduleImeSettle(activeSession)
    }

    private fun scheduleImeSettle(session: InputSession) {
        if (imeSession !== session || !session.imeSettlePending) return
        handler.removeCallbacks(imeSettleRunnable)
        val delay = (session.imeSettleUntil - System.currentTimeMillis()).coerceAtLeast(1L)
        handler.postDelayed(imeSettleRunnable, delay)
    }

    private fun isImeBatchEdit(event: AccessibilityEvent): Boolean {
        if (event.addedCount >= IME_BATCH_ADD_MIN) return true
        if (event.addedCount > 0 && event.removedCount > 0) return true
        if (event.removedCount >= IME_BATCH_REMOVE_MIN) return true
        return false
    }

    private fun writeIme(
        pkg: String,
        current: String,
        target: String,
        reason: String,
        activeSession: InputSession,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false

        val result = AccessibilityImeBridge.replaceAll(
            service = this,
            expectedPackage = pkg,
            expectedCurrent = current,
            target = target,
            probeSurrounding = false,
        )
        DiagnosticLog.add(
            "APP-IME-WRITE",
            "pkg=$pkg reason=$reason issued=${result.issued} editor=${result.editorPackage} started=${result.inputStarted} conn=${result.hasConnection} surroundingLen=${result.surroundingLength} match=${result.surroundingMatchesExpected} err=${result.error}",
        )

        if (!result.issued) {
            ServiceRuntimeState.markError("InputConnection å†™å…¥å¤±è´¥ï¼š$pkg Â· ${result.error}")
            return false
        }

        activeSession.lastWritten = target
        activeSession.lastWriteAt = System.currentTimeMillis()
        if (activeSession.lockActive) activeSession.lockedText = target
        ServiceRuntimeState.markReplacement(pkg)
        ServiceRuntimeState.markNode("InputConnection å†™å…¥æ”åŠŸèƒ½ï¼š	Ùûï"	™X\ÛÛ»ï"HŠBˆ™]\›ˆYBˆB‚ˆš]˜]H[ˆ›ØÙ\ÜÓ›ÙJˆ›ÙNˆXØÙ\ÜÚXš[]S›ÙR[™›Ëˆ]™[ˆXØÙ\ÜÚXš[]Q]™[Ëˆ™X\ÛÛŽˆÝš[™Ëˆ
Nˆ›ÛÛX[ˆÂˆYˆ
Z\ÑY]X›U\™Ù]
›ÙJJH™]\›ˆ˜[ÙBˆ˜[ÙÈH›ÙKœXÚØYÙS˜[YOËÔÝš[™Ê
K›Ü‘[\J
BˆYˆ
ÙËš\Ð›[šÊ
HÙÈOHXÚØYÙS˜[YJH™]\›ˆ˜[ÙB‚ˆ˜[Ý\œ™[H›ÙK^ËÔÝš[™Ê
HÎˆˆ‚ˆ˜[Ù^HH›ÙRÙ^J›ÙJBˆ˜[›ÝÈHÞ\Ý[K˜Ý\œ™[[YSZ[\Ê
Bˆ˜[XÝ]™TÙ\ÜÚ[ÛˆH›ÙTÙ\ÜÚ[ÛËZÙRYˆÈ]šÙ^HOHÙ^HBˆÎˆ[œ]Ù\ÜÚ[ÛŠÙ^JK˜[ÛÈÈ›ÙTÙ\ÜÚ[ÛˆH]B‚ˆYˆ
ˆ›ÝÈHXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš]P]HÑS—ÕÔ’UWÑÕPT‘ÓTÈ	‰‚ˆÝ\œ™[OHXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][‚ˆ
H™]\›ˆYB‚ˆ˜[ÛÛ\ÜÚ[™ÈH\]PÛÛ\ÜÚ][Û”Ý]JÙË]™[XÝ]™TÙ\ÜÚ[ÛŠBˆYˆ
ÛÛ\ÜÚ[™ÊHÂˆXYÛ›ÜÝXÓÙË˜Y
ˆTPÓÓTÔÑH‹ˆ™Y™\‹]˜[œÙ›Ü›HÙÏIÙÈ[IØÝ\œ™[›[™ÝH[™[™ÏIØXÝ]™TÙ\ÜÚ[Û‹˜ÛÛ\ÜÚ][Û”[™[™ßH][›ÙH‹ˆ
Bˆ™]\›ˆYBˆB‚ˆ˜[Ù][™ÐÛÛ\ÜÚ][ÛˆHXÝ]™TÙ\ÜÚ[Û‹˜ÛÛ\ÜÚ][Û”[™[™ÂˆYˆ
Ù][™ÐÛÛ\ÜÚ][ÛŠHÂˆXÝ]™TÙ\ÜÚ[Û‹˜ÛÛ\ÜÚ][Û”[™[™ÈH˜[ÙBˆXYÛ›ÜÝXÓÙË˜Y
ˆTPÓÓTÔÑH‹ˆœÙ]YÙÏIÙÈ[IØÝ\œ™[›[™ÝNÈ[\\ÜÈ][›ÙH‹ˆ
BˆB‚ˆYˆ
Ý\œ™[š\Ñ[\J
JHÂˆYˆ
ÚÝ[™\ÝÜ™SØÚÙY[][ÛŠ]™[Ý\œ™[XÝ]™TÙ\ÜÚ[Û‹›ÝË˜[ÙJJHÂˆ˜[™\ÝÜ™HHXÝ]™TÙ\ÜÚ[Û‹›ØÚÙY^šY‘[\HÈXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][ˆBˆ™]\›ˆÜš]S›ÙSÜ’[YJˆ›ÙKÙËÝ\œ™[™\ÝÜ™K›ØÚË\™\ÝÜ™KY[\H‹XÝ]™TÙ\ÜÚ[Û‹ˆ
BˆBˆXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][ˆHˆ‚ˆXÝ]™TÙ\ÜÚ[Û‹˜ÛX\“ØÚÊ
Bˆ™]\›ˆYBˆB‚ˆYˆ
ÚÝ[™\ÝÜ™SØÚÙY[][ÛŠ]™[Ý\œ™[XÝ]™TÙ\ÜÚ[Û‹›ÝË˜[ÙJJHÂˆ˜[™\ÝÜ™HHXÝ]™TÙ\ÜÚ[Û‹›ØÚÙY^šY‘[\HÈXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][ˆBˆXYÛ›ÜÝXÓÙË˜Y
ˆTSÐÒÈ‹ˆœ™\ÝÜ™KY[]K[›ÙHÙÏIÙÈÝ\œ™[[IØÝ\œ™[›[™ÝH\Ý[IØXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][‹›[™ÝH™\ÝÜ™S[IÜ™\ÝÜ™K›[™ÝH‹ˆ
Bˆ™]\›ˆÜš]S›ÙSÜ’[YJˆ›ÙKˆÙËˆÝ\œ™[ˆ™\ÝÜ™Kˆ›ÙK^Ù[XÝ[Û‘[™ˆÝ\œ™[›[™Ýˆ›ØÚË\™\ÝÜ™KY[]H‹ˆXÝ]™TÙ\ÜÚ[Û‹ˆ
BˆB‚ˆ˜[Ú[™ÙHHYˆ
]™[OH[
H[[ÙH[˜Ü™[Y[[˜[œÙ›Ü›Y\‹Ú[™ÙJˆ™Y›Ü™U^H]™[˜™Y›Ü™U^ËÔÝš[™Ê
Kˆœ›ÛR[™^H]™[™œ›ÛR[™^ˆYYÛÝ[H]™[˜YYÛÝ[ˆ™[[Ý™YÛÝ[H]™[œ™[[Ý™YÛÝ[ˆ
B‚ˆ˜[\™Ù]HYˆ
Ù][™ÐÛÛ\ÜÚ][ÛŠHÂˆ[˜Ü™[Y[[˜[œÙ›Ü›Y\‹˜[œÙ›Ü›JÝ\œ™[XÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][‹[ØXÚY[\ÊBˆH[ÙHÂˆ[˜Ü™[Y[[˜[œÙ›Ü›Y\‹˜[œÙ›Ü›JÝ\œ™[XÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][‹Ú[™ÙKØXÚY[\ÊBˆBˆ˜[]H\™Ù]OHÝ\œ™[ˆXYÛ›ÜÝXÓÙË˜Y
ˆTUS”Ñ“Ô“H‹ˆœÙÏIÙÈ™\XÙ[Y[I]Ý\œ™[[IØÝ\œ™[›[™ÝH\™Ù][IÝ\™Ù]›[™ÝHÛÛ\ÜÚ[™ÏY˜[ÙHÙ]OIÙ][™ÐÛÛ\ÜÚ][Ûˆ][›ÙH‹ˆ
B‚ˆYˆ
Z]
HÂˆXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][ˆHÝ\œ™[ˆYˆ
ØÚÔ™\XÙ[Y[[˜X›Y	‰ˆXÝ]™TÙ\ÜÚ[Û‹›ØÚÐXÝ]™JHÂˆXÝ]™TÙ\ÜÚ[Û‹›ØÚÙY^HÝ\œ™[ˆBˆ™]\›ˆYBˆB‚ˆ˜[ÚÈHÜš]S›ÙSÜ’[YJˆ›ÙKˆÙËˆÝ\œ™[ˆ\™Ù]ˆ›ÙK^Ù[XÝ[Û‘[™ˆÝ\œ™[›[™Ýˆ™X\ÛÛ‹ˆXÝ]™TÙ\ÜÚ[Û‹ˆ
BˆYˆ
ÚÈ	‰ˆØÚÔ™\XÙ[Y[[˜X›Y
HÂˆXÝ]™TÙ\ÜÚ[Û‹›ØÚÐXÝ]™HHYBˆXÝ]™TÙ\ÜÚ[Û‹›ØÚÙY^H\™Ù]ˆXYÛ›ÜÝXÓÙË˜Y
TSÐÒÈ‹˜\›YYÙÏIÙÈ[IÝ\™Ù]›[™ÝH][›ÙHŠBˆBˆ™]\›ˆÚÂˆB‚ˆš]˜]H[ˆ\]PÛÛ\ÜÚ][Û”Ý]JˆÙÎˆÝš[™Ëˆ]™[ˆXØÙ\ÜÚXš[]Q]™[ËˆXÝ]™TÙ\ÜÚ[ÛŽˆ[œ]Ù\ÜÚ[Û‹ˆ
Nˆ›ÛÛX[ˆÂˆ˜[›ÝÈHÞ\Ý[K˜Ý\œ™[[YSZ[\Ê
BˆYˆ
]™[OH[
H™]\›ˆ›ÝÈXÝ]™TÙ\ÜÚ[Û‹˜ÛÛ\ÜÚ][Û•[[‚ˆ˜[YYH]™[˜YYÛÝ[ˆ˜[™[[Ý™YH]™[œ™[[Ý™YÛÝ[ˆ˜[œ›ÛHH]™[™œ›ÛR[™^ˆ˜[™Y›Ü™S[ˆH]™[˜™Y›Ü™U^Ë›[™ÝÎˆXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][‹›[™Ýˆ˜[[™XYPÛÛ\ÜÚ[™ÈH›ÝÈXÝ]™TÙ\ÜÚ[Û‹˜ÛÛ\ÜÚ][Û•[[‚ˆ˜[™\XÙR[”XÙHHYYˆ	‰ˆ™[[Ý™Yˆˆ˜[˜]Ú\[™HYYHSQWÐUÒÐQÓRSˆ	‰ˆ™[[Ý™YOHˆ˜[[ÛX\ˆBˆ[™XYPÛÛ\ÜÚ[™È	‰‚ˆYYOH	‰ˆ™[[Ý™Yˆ	‰ˆœ›ÛHOH	‰ˆ™Y›Ü™S[ˆˆ	‰ˆ™[[Ý™YH™Y›Ü™S[‚ˆ˜[\™ÙQ[]HBˆ[™XYPÛÛ\ÜÚ[™È	‰‚ˆYYOH	‰ˆ™[[Ý™YHSQWÐUÒÔ‘SSÕ‘WÓRSˆ	‰ˆ™Y›Ü™S[ˆˆ	‰‚ˆ
œ›ÛHOH™[[Ý™Y
ˆÈH™Y›Ü™S[ŠBˆ˜[XY™]Üš]HHœ›ÛHOH	‰ˆ™[[Ý™Yˆ	‰ˆYYHSQWÐUÒÐQÓRS‚‚ˆYˆ
™\XÙR[”XÙH˜]Ú\[™[ÛX\ˆ\™ÙQ[]HXY™]Üš]JHÂˆXÝ]™TÙ\ÜÚ[Û‹˜ÛÛ\ÜÚ][Û•[[H›ÝÈ
ÈSQWÐÓÓTÔÒS‘×ÔÑUWÓTÂˆXÝ]™TÙ\ÜÚ[Û‹˜ÛÛ\ÜÚ][Û”[™[™ÈHYB‚ˆ˜[ÛÛ\ÜÙT™X\ÛÛˆHÚ[ˆÂˆ[ÛX\ˆOˆ™[XÛX\ˆ‚ˆ\™ÙQ[]HOˆ›\™ÙKY[]H‚ˆXY™]Üš]HOˆšXY\™]Üš]H‚ˆ™\XÙR[”XÙHOˆœ™\XÙKZ[‹\XÙH‚ˆ[ÙHOˆ˜˜]ÚX\[™‚ˆB‚ˆ˜[\ÝXÝ]™T™]Üš]HH™\XÙR[”XÙH[ÛX\ˆ\™ÙQ[]HXY™]Üš]BˆYˆ
\ÝXÝ]™T™]Üš]H	‰ˆXÝ]™TÙ\ÜÚ[Û‹›ØÚÐXÝ]™JHÂˆXÝ]™TÙ\ÜÚ[Û‹˜ÛX\“ØÚÊ
BˆXYÛ›ÜÝXÓÙË˜Y
ˆTPÓÓTÔÑH‹ˆ›ØÚË\Ý\Ü[™YÙÏIÙÈ™X\ÛÛIÛÛ\ÜÙT™X\ÛÛˆYIYY™[[Ý™OI™[[Ý™Yœ›ÛOIœ›ÛH™Y›Ü™S[I™Y›Ü™S[ˆ‹ˆ
BˆH[ÙHYˆ
X[™XYPÛÛ\ÜÚ[™ÊHÂˆXYÛ›ÜÝXÓÙË˜Y
ˆTPÓÓTÔÑH‹ˆ™]XÝYÙÏIÙÈ™X\ÛÛIÛÛ\ÜÙT™X\ÛÛˆYIYY™[[Ý™OI™[[Ý™Yœ›ÛOIœ›ÛH™Y›Ü™S[I™Y›Ü™S[ˆ‹ˆ
BˆBˆ™]\›ˆYBˆB‚ˆ™]\›ˆ›ÝÈXÝ]™TÙ\ÜÚ[Û‹˜ÛÛ\ÜÚ][Û•[[ˆB‚ˆš]˜]H[ˆÚÝ[™\ÝÜ™SØÚÙY[][ÛŠˆ]™[ˆXØÙ\ÜÚXš[]Q]™[ËˆÝ\œ™[ˆÝš[™ËˆXÝ]™TÙ\ÜÚ[ÛŽˆ[œ]Ù\ÜÚ[Û‹ˆ›ÝÎˆÛ™ËˆÛÛ\ÜÚ[™Îˆ›ÛÛX[‹ˆ
Nˆ›ÛÛX[ˆÂˆYˆ
[ØÚÔ™\XÙ[Y[[˜X›YXXÝ]™TÙ\ÜÚ[Û‹›ØÚÐXÝ]™JH™]\›ˆ˜[ÙBˆYˆ
]™[OH[ÛÛ\ÜÚ[™ÊH™]\›ˆ˜[ÙBˆYˆ
›ÝÈHXÝ]™TÙ\ÜÚ[Û‹˜[ÝÐÛX\•[[
H™]\›ˆ˜[ÙBˆYˆ
]™[˜YYÛÝ[OH]™[œ™[[Ý™YÛÝ[H
H™]\›ˆ˜[ÙB‚ˆ˜[™Y›Ü™S[ˆH]™[˜™Y›Ü™U^Ë›[™ÝÎˆXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][‹›[™ÝˆYˆ
™Y›Ü™S[ˆHÝ\œ™[›[™ÝH™Y›Ü™S[ŠH™]\›ˆ˜[ÙB‚ˆ˜[ÚÛQšY[[]HH]™[™œ›ÛR[™^OH	‰ˆ]™[œ™[[Ý™YÛÝ[H™Y›Ü™S[‚ˆYˆ
ÚÛQšY[[]JH™]\›ˆYBˆ™]\›ˆ]™[œ™[[Ý™YÛÝ[HPVÓPS•PSÓÐÒ×ÑSUWÐÒT”ÂˆB‚ˆš]˜]H[ˆÜš]S›ÙSÜ’[YJˆ›ÙNˆXØÙ\ÜÚXš[]S›ÙR[™›ËˆÙÎˆÝš[™ËˆÝ\œ™[ˆÝš[™Ëˆ\™Ù]ˆÝš[™ËˆÛÙ[XÝ[Û‘[™ˆ[ˆÛ[™Ýˆ[ˆ™X\ÛÛŽˆÝš[™ËˆXÝ]™TÙ\ÜÚ[ÛŽˆ[œ]Ù\ÜÚ[Û‹ˆ
Nˆ›ÛÛX[ˆÂˆ˜[›ØÝ\ÓÚÈH›ÙKœ\™›Ü›PXÝ[ÛŠXØÙ\ÜÚXš[]S›ÙR[™›ËPÕSÓ—Ñ“ÐÕTÊBˆ˜[\™ÜÈH[™J
K˜\HÂˆ]Ú\”Ù\]Y[˜ÙJXØÙ\ÜÚXš[]S›ÙR[™›ËPÕSÓ—ÐT‘ÕSQS•ÔÑUÕVÐÒT”ÑTUQSÑK\™Ù]
BˆBˆ˜[Ù]^ÚÈH›ÙKœ\™›Ü›PXÝ[ÛŠXØÙ\ÜÚXš[]S›ÙR[™›ËPÕSÓ—ÔÑUÕV\™ÜÊBˆXYÛ›ÜÝXÓÙË˜Y
ˆTS“ÑKUÔ’UH‹ˆœÙÏIÙÈ™X\ÛÛI™X\ÛÛˆ›ØÝ\ÏI›ØÝ\ÓÚÈÙ]^IÙ]^ÚÈÝ\œ™[[IØÝ\œ™[›[™ÝH\™Ù][IÝ\™Ù]›[™ÝH‹ˆ
B‚ˆYˆ
Ù]^ÚÊHÂˆ™\ÝÜ™TÙ[XÝ[ÛŠ›ÙK\™Ù]ÛÙ[XÝ[Û‘[™Û[™Ý
BˆXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][ˆH\™Ù]ˆXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš]P]HÞ\Ý[K˜Ý\œ™[[YSZ[\Ê
BˆYˆ
XÝ]™TÙ\ÜÚ[Û‹›ØÚÐXÝ]™JHXÝ]™TÙ\ÜÚ[Û‹›ØÚÙY^H\™Ù]ˆÙ\šXÙT[[YTÝ]K›X\šÔ™\XÙ[Y[
ÙÊBˆÙ\šXÙT[[YTÝ]K›X\šÓ›ÙJXØÙ\ÜÚXš[]S›ÙH9a¦yaiyi,z-){ï&‰ÙÈ;ï"	™X\ÛÛ»ï"HŠBˆ™]\›ˆYBˆB‚ˆYˆ
Z[•‘T”ÒSÓ‹”Ñ×ÒS•HÌÊHÂˆ˜[[YPXÝ]™HHÙ][YTÙ\ÜÚ[ÛŠÙË›ÙKÚ[™ÝÒY
Bˆ˜[ÚÈHÜš]R[YJÙËÝ\œ™[\™Ù]‰™X\ÛÛ‹[›ÙKY˜[˜XÚÈ‹[YPXÝ]™JBˆYˆ
ÚÊHÂˆXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš][ˆH\™Ù]ˆXÝ]™TÙ\ÜÚ[Û‹›\ÝÜš]P]HÞ\Ý[K˜Ý\œ™[[YSZ[\Ê
BˆYˆ
XÝ]™TÙ\ÜÚ[Û‹›ØÚÐXÝ]™JHXÝ]™TÙ\ÜÚ[Û‹›ØÚÙY^H\™Ù]ˆ™]\›ˆYBˆBˆB‚ˆÙ\šXÙT[[YTÝ]K›X\šÑ\œ›ÜŠº" ¹à®yd£[œ]ÛÛ›™XÝ[Ûˆ9gaù¥è9¬åya¦yai{ï&‰ÙÈŠBˆ™]\›ˆ˜[ÙBˆB‚ˆš]˜]H[ˆ[œÜXÝÝ\œ™[Y]ÜŠ™X\ÛÛŽˆÝš[™ÊHÂˆ˜[›ÙHHš[™›ØÝ\ÙYY]X›J
BˆYˆ
›ÙHOH[
HÂˆžHÂˆYˆ
›ØÙ\ÜÓ›ÙJ›ÙK[™X\ÛÛŠJH™]\›‚ˆHš[˜[HÂˆ›ÙKœ™XÞXÛJ
BˆBˆB‚ˆYˆ
Z[•‘T”ÒSÓ‹”Ñ×ÒS•HÌÈ	‰ˆ›ØÙ\ÜÒ[YTÛ˜\ÚÝ
[‰™X\ÛÛ‹Z[YHŠJH™]\›‚ˆÙ\šXÙT[[YTÝ]K›X\šÓ›ÙJ¹§*¹¢o¹b,9cëùå*:/¤ùaiyï%º/¤yfj	™X\ÛÛŠIŠBˆB‚ˆš]˜]H[ˆš[™›ØÝ\ÙYY]X›J
NˆXØÙ\ÜÚXš[]S›ÙR[™›ÏÈÂˆ˜[XÝ]™T›ÛÝH›ÛÝ[XÝ]™UÚ[™ÝÂˆYˆ
XÝ]™T›ÛÝOH[
HÂˆžHÂˆ˜[›ØÝ\ÙYHXÝ]™T›ÛÝ™š[™›ØÝ\ÊXØÙ\ÜÚXš[]S›ÙR[™›Ë‘“ÐÕT×ÒS”U
BˆYˆ
›ØÝ\ÙYOH[
HÂˆYˆ
\ÑY]X›U\™Ù]
›ØÝ\ÙY
JH™]\›ˆ›ØÝ\ÙYˆ›ØÝ\ÙYœ™XÞXÛJ
BˆBˆš[™Y\›ØÝ\ÙYY]X›JXÝ]™T›ÛÝ
OË›]È™]\›ˆ]Bˆš[™™\ÝY]X›PØ[™Y]JXÝ]™T›ÛÝ
OË›]È™]\›ˆ]BˆHš[˜[HÂˆXÝ]™T›ÛÝœ™XÞXÛJ
BˆBˆB‚ˆ˜[Ü™\™YÚ[™ÝÜÈHÚ[™ÝÜËœÛÜYÚ]
ˆÛÛ\\™PžQ\ØÙ[™[™ÏXØÙ\ÜÚXš[]UÚ[™ÝÒ[™›ÏˆÈ]š\Ñ›ØÝ\ÙYBˆ[žQ\ØÙ[™[™ÈÈ]š\ÐXÝ]™HKˆ
Bˆ›Üˆ
Ú[™ÝÈ[ˆÜ™\™YÚ[™ÝÜÊHÂˆ˜[›ÛÝHÚ[™ÝËœ›ÛÝÎˆÛÛ[YBˆžHÂˆ˜[›ØÝ\ÙYH›ÛÝ™š[™›ØÝ\ÊXØÙ\ÜÚXš[]S›ÙR[™›Ë‘“ÐÕT×ÒS”U
BˆYˆ
›ØÝ\ÙYOH[
HÂˆYˆ
\ÑY]X›U\™Ù]
›ØÝ\ÙY
JH™]\›ˆ›ØÝ\ÙYˆ›ØÝ\ÙYœ™XÞXÛJ
BˆBˆš[™Y\›ØÝ\ÙYY]X›J›ÛÝ
OË›]È™]\›ˆ]Bˆš[™™\ÝY]X›PØ[™Y]J›ÛÝ
OË›]È™]\›ˆ]BˆHš[˜[HÂˆ›ÛÝœ™XÞXÛJ
BˆBˆBˆ™]\›ˆ[ˆB‚ˆš]˜]H[ˆš[™Y\›ØÝ\ÙYY]X›J›ÙNˆXØÙ\ÜÚXš[]S›ÙR[™›ÊNˆXØÙ\ÜÚXš[]S›ÙR[™›ÏÈÂˆYˆ

›ÙKš\Ñ›ØÝ\ÙY›ÙKš\ÐXØÙ\ÜÚXš[]Q›ØÝ\ÙY
H	‰ˆ\ÑY]X›U\™Ù]
›ÙJJHÂˆ™]\›ˆXØÙ\ÜÚXš[]S›ÙR[™›Ë›ØZ[Š›ÙJBˆBˆ›Üˆ
H[ˆ[[›ÙK˜Ú[ÛÝ[
HÂˆ˜[Ú[H›ÙK™Ù]Ú[
JHÎˆÛÛ[YBˆžHÂˆ˜[›Ý[™Hš[™Y\›ØÝ\ÙYY]X›JÚ[
BˆYˆ
›Ý[™OH[
H™]\›ˆ›Ý[™ˆHš[˜[HÂˆÚ[œ™XÞXÛJ
BˆBˆBˆ™]\›ˆ[ˆB‚ˆš]˜]H[ˆš[™™\ÝY]X›PØ[™Y]J›ÛÝˆXØÙ\ÜÚXš[]S›ÙR[™›ÊNˆXØÙ\ÜÚXš[]S›ÙR[™›ÏÈÂˆ˜\ˆ™\ÝˆXØÙ\ÜÚXš[]S›ÙR[™›ÏÈH[ˆ˜\ˆ™\ÝØÛÜ™HH‚ˆ[ˆš\Ú]
›ÙNˆXØÙ\ÜÚXš[]S›ÙR[™›Ë\ˆ[
HÂˆYˆ
\ˆPVÕ‘QWÑT
H™]\›‚ˆ˜[ØÛÜ™HHY]X›PØ[™Y]TØÛÜ™J›ÙJBˆYˆ
ØÛÜ™Hˆ™\ÝØÛÜ™JHÂˆ™\ÝËœ™XÞXÛJ
Bˆ™\ÝHXØÙ\ÜÚXš[]S›ÙR[™›Ë›ØZ[Š›ÙJBˆ™\ÝØÛÜ™HHØÛÜ™BˆBˆ›Üˆ
H[ˆ[[›ÙK˜Ú[ÛÝ[
HÂˆ˜[Ú[H›ÙK™Ù]Ú[
JHÎˆÛÛ[YBˆžHÂˆš\Ú]
Ú[\
ÈJBˆHš[˜[HÂˆÚ[œ™XÞXÛJ
BˆBˆBˆB‚ˆš\Ú]
›ÛÝ
Bˆ™]\›ˆYˆ
™\ÝØÛÜ™HHRS—ÑQUP“WÐÐS‘QUWÔÐÓÔ‘JH™\Ý[ÙHÂˆ™\ÝËœ™XÞXÛJ
Bˆ[ˆBˆB‚ˆš]˜]H[ˆY]X›PØ[™Y]TØÛÜ™J›ÙNˆXØÙ\ÜÚXš[]S›ÙR[™›ÊNˆ[ÂˆYˆ
›ÙKœXÚØYÙS˜[YOËÔÝš[™Ê
HOHXÚØYÙS˜[YJH™]\›ˆˆ˜\ˆØÛÜ™HHˆYˆ
›ÙKš\ÑY]X›JHØÛÜ™H
ÏHLˆYˆ
›ÙK˜XÝ[Û“\Ý˜[žHÈ]šYOHXØÙ\ÜÚXš[]S›ÙR[™›ËPÕSÓ—ÔÑUÕVJHØÛÜ™H
ÏHLˆYˆ
›ÙK˜Û\ÜÓ˜[YOËÔÝš[™Ê
OË˜ÛÛZ[œÊ‘Y]^‹YÛ›Ü™PØ\ÙHHYJHOHYJHØÛÜ™H
ÏHˆYˆ
›ÙKš\Ñ›ØÝ\ÙY
HØÛÜ™H
ÏHLˆYˆ
›ÙKš\ÐXØÙ\ÜÚXš[]Q›ØÝ\ÙY
HØÛÜ™H
ÏHÌˆYˆ
›ÙKš\Ñ›ØÝ\ØX›JHØÛÜ™H
ÏHŒˆYˆ
›ÙKš\Ñ[˜X›Y
HØÛÜ™H
ÏHLˆYˆ
›ÙKš\Õš\ÚX›UÕ\Ù\ŠHØÛÜ™H
ÏHLˆYˆ
›ÙK^Ù[XÝ[Û”Ý\H›ÙK^Ù[XÝ[Û‘[™H
HØÛÜ™H
ÏHŒˆ™]\›ˆØÛÜ™BˆB‚ˆš]˜]H[ˆ\ÑY]X›U\™Ù]
›ÙNˆXØÙ\ÜÚXš[]S›ÙR[™›ÊNˆ›ÛÛX[ˆÂˆYˆ
[›ÙKš\Ñ[˜X›Y›ÙKš\Ô\ÜÝÛÜ™
H™]\›ˆ˜[ÙBˆYˆ
›ÙKš\ÑY]X›JH™]\›ˆYBˆYˆ
›ÙK˜XÝ[Û“\Ý˜[žHÈ]šYOHXØÙ\ÜÚXš[]S›ÙR[™›ËPÕSÓ—ÔÑUÕVJH™]\›ˆYBˆ™]\›ˆ›ÙK˜Û\ÜÓ˜[YOËÔÝš[™Ê
OË˜ÛÛZ[œÊ‘Y]^‹YÛ›Ü™PØ\ÙHHYJHOHYH	‰ˆ›ÙKš\Ñ›ØÝ\ØX›BˆB‚ˆš]˜]H[ˆ›ÙRÙ^J›ÙNˆXØÙ\ÜÚXš[]S›ÙR[™›ÊNˆ›ÙRÙ^HÂˆ˜[›Ý[™ÈH™XÝ

Bˆ›ÙK™Ù]›Ý[™Ò[”ØÜ™Y[Š›Ý[™ÊBˆ™]\›ˆ›ÙRÙ^JˆÚ[™ÝÒYH›ÙKÚ[™ÝÒYˆXÚØYÙS˜[YHH›ÙKœXÚØYÙS˜[YOËÔÝš[™Ê
K›Ü‘[\J
KˆšY]ÒYH›ÙKšY]ÒY™\ÛÝ\˜ÙS˜[YK›Ü‘[\J
KˆÛ\ÜÓ˜[YHH›ÙK˜Û\ÜÓ˜[YOËÔÝš[™Ê
K›Ü‘[\J
Kˆ›Ý[™ÈH‰Ø›Ý[™Ë›YK	Ø›Ý[™ËÜK	Ø›Ý[™ËœšYÚK	Ø›Ý[™Ë˜›ÝÛ_H‹ˆ
BˆB‚ˆš]˜]H[ˆÙ][YTÙ\ÜÚ[ÛŠÙÎˆÝš[™ËÚ[™ÝÒYˆ[
Nˆ[œ]Ù\ÜÚ[ÛˆÂˆ˜[Ý\œ™[H[YTÙ\ÜÚ[Û‚ˆYˆ
Ý\œ™[OH[	‰ˆÝ\œ™[šÙ^KœXÚØYÙS˜[YHOHÙÊH™]\›ˆÝ\œ™[‚ˆ[™\‹œ™[[Ý™PØ[˜XÚÜÊ[YTÙ]T[›˜X›JBˆ˜[Ù^HH›ÙRÙ^JˆÚ[™ÝÒYHÚ[™ÝÒYˆXÚØYÙS˜[YHHÙËˆšY]ÒYHXØÙ\ÜÚXš[]KZ[YH‹ˆÛ\ÜÓ˜[YHH’[œ]ÛÛ›™XÝ[Ûˆ‹ˆ›Ý[™ÈHˆ‹ˆ
Bˆ™]\›ˆ[œ]Ù\ÜÚ[ÛŠÙ^JK˜[ÛÈÈ[YTÙ\ÜÚ[ÛˆH]BˆB‚ˆš]˜]H[ˆ™\ÝÜ™TÙ[XÝ[ÛŠˆ›ÙNˆXØÙ\ÜÚXš[]S›ÙR[™›Ëˆ^ˆÝš[™ËˆÛÙ[XÝ[Û‘[™ˆ[ˆÛ[™Ýˆ[ˆ
HÂˆYˆ
ÛÙ[XÝ[Û‘[™
H™]\›‚ˆ˜[[HH^›[™ÝHÛ[™Ýˆ˜[™]ÔÙ[XÝ[ÛˆH
ÛÙ[XÝ[Û‘[™
È[JK˜ÛÙ\˜ÙR[Š^›[™Ý
Bˆ˜[\™ÜÈH[™J
K˜\HÂˆ][
XØÙ\ÜÚXš[]S›ÙR[™›ËPÕSÓ—ÐT‘ÕSQS•ÔÑSPÕSÓ—ÔÕT•ÒS•™]ÔÙ[XÝ[ÛŠBˆ][
XØÙ\ÜÚXš[]S›ÙR[™›ËPÕSÓ—ÐT‘ÕSQS•ÔÑSPÕSÓ—ÑS‘ÒS•™]ÔÙ[XÝ[ÛŠBˆBˆ›ÙKœ\™›Ü›PXÝ[ÛŠXØÙ\ÜÚXš[]S›ÙR[™›ËPÕSÓ—ÔÑUÔÑSPÕSÓ‹\™ÜÊBˆB‚ˆš]˜]H[ˆ\ÓZÙ[TÙ[™]™[
]™[ˆXØÙ\ÜÚXš[]Q]™[
Nˆ›ÛÛX[ˆÂˆ˜[ÛÝ\˜ÙHH]™[œÛÝ\˜ÙBˆYˆ
ÛÝ\˜ÙHOH[
HÂˆžHÂˆ˜[YHÛÝ\˜ÙKšY]ÒY™\ÛÝ\˜ÙS˜[YOË›ÝÙ\˜Ø\ÙJ
K›Ü‘[\J
Bˆ˜[^H
ÛÝ\˜ÙK^ËÔÝš[™Ê
HÎˆÛÝ\˜ÙK˜ÛÛ[\ØÜš\[ÛËÔÝš[™Ê
JBˆ›Ü‘[\J
Kš[J
K›ÝÙ\˜Ø\ÙJ
BˆYˆ
ˆY˜ÛÛZ[œÊœÙ[™ŠHY˜ÛÛZ[œÊ˜—ÜÙ[™ŠHˆ^OH¹cäz` Hˆ^OHœÙ[™ˆ^˜ÛÛZ[œÊ¹cäz` y­¢9 kÈŠBˆ
H™]\›ˆYBˆHš[˜[HÂˆÛÝ\˜ÙKœ™XÞXÛJ
BˆBˆBˆ˜[]™[^H]™[^š›Ú[•ÔÝš[™ÊˆŠKš[J
K›ÝÙ\˜Ø\ÙJ
Bˆ˜[\ØÜš\[ÛˆH]™[˜ÛÛ[\ØÜš\[ÛËÔÝš[™Ê
K›Ü‘[\J
Kš[J
K›ÝÙ\˜Ø\ÙJ
Bˆ™]\›ˆ]™[^OH¹cäz` Hˆ]™[^˜ÛÛZ[œÊ¹cäz` y­¢9 kÈŠHˆ\ØÜš\[ÛˆOH¹cäz` Hˆ\ØÜš\[Û‹˜ÛÛZ[œÊ¹cäz` y­¢9 kÈŠBˆB‚ˆš]˜]H[ˆÙÑ]™[
ÙÎˆÝš[™Ë]™[ˆXØÙ\ÜÚXš[]Q]™[
HÂˆ˜[ÛÝ\˜ÙHH]™[œÛÝ\˜ÙBˆžHÂˆXYÛ›ÜÝXÓÙË˜Y
ˆTQU‘S•‹ˆZ[Ýš[™ÈÂˆ\[™
œÙÏIÙÈ\OIÙ]™[˜[YJ]™[™]™[\J_HŠBˆ\[™
ˆÚ[IÙ]™[Ú[™ÝÒYHŠBˆ\[™
ˆœ›ÛOIÙ]™[™œ›ÛR[™^HŠBˆ\[™
ˆYIÙ]™[˜YYÛÝ[HŠBˆ\[™
ˆ™[[Ý™OIÙ]™[œ™[[Ý™YÛÝ[HŠBˆ\[™
ˆ™Y›Ü™S[IÙ]™[˜™Y›Ü™U^Ë›[™ÝÎˆL_HŠBˆ\[™
ˆ]™[^ÛÝ[IÙ]™[^œÚ^™_HŠBˆ\[™
ˆ]™[^[IÙ]™[^™š\œÝÜ“[

OË›[™ÝÎˆL_HŠBˆ\[™
ˆÛÝ\˜ÙOHŠBˆ\[™
Yˆ
ÛÝ\˜ÙHOH[
H›[ˆ[ÙH›ÙTÝ[[X\žJÛÝ\˜ÙJJBˆKˆ
BˆHš[˜[HÂˆÛÝ\˜ÙOËœ™XÞXÛJ
BˆBˆB‚ˆš]˜]H[ˆ›ÙTÝ[[X\žJ›ÙNˆXØÙ\ÜÚXš[]S›ÙR[™›ÊNˆÝš[™ÈÂˆ˜[›Ý[™ÈH™XÝ

Bˆ›ÙK™Ù]›Ý[™Ò[”ØÜ™Y[Š›Ý[™ÊBˆ™]\›ˆZ[Ýš[™ÈÂˆ\[™
˜Û\ÜÏIÛ›ÙK˜Û\ÜÓ˜[YOËÔÝš[™Ê
K›Ü‘[\J
_HŠBˆ\[™
ˆYIÛ›ÙKšY]ÒY™\ÛÝ\˜ÙS˜[YK›Ü‘[\J
_HŠBˆ\[™
ˆY]X›OIÛ›ÙKš\ÑY]X›_HŠBˆ\[™
ˆ›ØÝ\ØX›OIÛ›ÙKš\Ñ›ØÝ\ØX›_HŠBˆ\[™
ˆ›ØÝ\ÙYIÛ›ÙKš\Ñ›ØÝ\ÙYHŠBˆ\[™
ˆ[˜X›YIÛ›ÙKš\Ñ[˜X›YHŠBˆ\[™
ˆš\ÚX›OIÛ›ÙKš\Õš\ÚX›UÕ\Ù\ŸHŠBˆ\[™
ˆ^[IÛ›ÙK^Ë›[™ÝÎˆL_HŠBˆ\[™
ˆÙ[IÛ›ÙK^Ù[XÝ[Û”Ý\K‹‰Û›ÙK^Ù[XÝ[Û‘[™HŠBˆ\[™
ˆ›Ý[™ÏIØ›Ý[™Ë›YK	Ø›Ý[™ËÜK	Ø›Ý[™ËœšYÚK	Ø›Ý[™Ë˜›ÝÛ_HŠBˆ\[™
ˆXÝ[ÛœÏIÛ›ÙK˜XÝ[Û“\Ýš›Ú[•ÔÝš[™Ê‹ŠHÈ]šYÔÝš[™Ê
H_HŠBˆBˆB‚ˆš]˜]H[ˆ]™[˜[YJ\Nˆ[
NˆÝš[™ÈHÚ[ˆ
\JHÂˆXØÙ\ÜÚXš[]Q]™[•TWÕ’QU×ÕVÐÒS‘ÑQOˆ•VÐÒS‘ÑQ‚ˆXØÙ\ÜÚXš[]Q]™[•TWÕ’QU×Ñ“ÐÕTÑQOˆ•’QU×Ñ“ÐÕTÑQ‚ˆXØÙ\ÜÚXš[]Q]™[•TWÕ’QU×ÕVÔÑSPÕSÓ—ÐÒS‘ÑQOˆ”ÑSPÕSÓ—ÐÒS‘ÑQ‚ˆXØÙ\ÜÚXš[]Q]™[•TWÕ’QU×ÐÓPÒÑQOˆ•’QU×ÐÓPÒÑQ‚ˆXØÙ\ÜÚXš[]Q]™[•TWÕÒS‘Õ×ÔÕUWÐÒS‘ÑQOˆ•ÒS‘Õ×ÔÕUWÐÒS‘ÑQ‚ˆXØÙ\ÜÚXš[]Q]™[•TWÕÒS‘Õ×ÐÓÓ•S•ÐÒS‘ÑQOˆ•ÒS‘Õ×ÐÓÓ•S•ÐÒS‘ÑQ‚ˆXØÙ\ÜÚXš[]Q]™[•TWÕÒS‘ÕÔ×ÐÒS‘ÑQOˆ•ÒS‘ÕÔ×ÐÒS‘ÑQ‚ˆ[ÙHOˆ‘U‘S•É\H‚ˆB‚ˆš]˜]H[ˆ™[ØY[\Ê
HÂˆØXÚY[\ÈH™\ÜÚ]ÜžK›ØY[\Ê
BˆXYÛ›ÜÝXÓÙË˜Y
”•SH‹œÝX›KZ[YH™[ØYYÛÝ[IØØXÚY[\ËœÚ^™_HŠBˆB‚ˆš]˜]H[ˆ™[ØYÙ][™ÜÊ
HÂˆ˜[Ù][™ÜÈH\Ù][™ÜÊ\ÊBˆÛÛ\]Xš[]TØØ[‘[˜X›YHÙ][™ÜË˜ÛÛ\]Xš[]TØØ[‘[˜X›YˆØÚÔ™\XÙ[Y[[˜X›YHÙ][™ÜË›ØÚÔ™\XÙ[Y[[˜X›YˆXYÛ›ÜÝXÓÙË˜Y
ˆ”ÑUS‘ÔÈ‹ˆœÝX›KZ[YHØØ[IÛÛ\]Xš[]TØØ[‘[˜X›YØÚÏIØÚÔ™\XÙ[Y[[˜X›Y‹ˆ
BˆYˆ
[ØÚÔ™\XÙ[Y[[˜X›Y
HÂˆ›ÙTÙ\ÜÚ[ÛË˜ÛX\“ØÚÊ
Bˆ[YTÙ\ÜÚ[ÛË˜ÛX\“ØÚÊ
BˆBˆB‚ˆš]˜]H[ˆØÚY[TØØ[Š[^S\ÎˆÛ™ÊHÂˆ[™\‹œ™[[Ý™PØ[˜XÚÜÊ[^YYØØ[”[›˜X›JBˆ[™\‹œÜÝ[^YY
[^YYØØ[”[›˜X›K[^S\ÊBˆB‚ˆÝ™\œšYH[ˆÛ’[\œ\

HÂˆÙ\šXÙT[[YTÝ]K›X\šÑ\œ›ÜŠ¹ìîùîçú+ ùå*9.¡ˆÛ’[\œ\ŠBˆXYÛ›ÜÝXÓÙË˜Y
”ÑT•’PÑH‹œÝX›KZ[YHÛ’[\œ\ŠBˆB‚ˆÝ™\œšYH[ˆÛ•[˜š[™
[[ˆ[™›ÚY˜ÛÛ[’[[ÊNˆ›ÛÛX[ˆÂˆÛX[\
¹¥è:f§9è£y§#yb¨yaìº)èùîäHŠBˆ™]\›ˆÝ\\‹›Û•[˜š[™
[[
BˆB‚ˆÝ™\œšYH[ˆÛ‘\Ý›ÞJ
HÂˆÛX[\
¹¥è:f§9è£y§#yb¨ymìºe 9«àHŠBˆÝ\\‹›Û‘\Ý›ÞJ
BˆB‚ˆš]˜]H[ˆÛX[\
™X\ÛÛŽˆÝš[™ÊHÂˆ[™\‹œ™[[Ý™PØ[˜XÚÜÊX\™X][›˜X›JBˆ[™\‹œ™[[Ý™PØ[˜XÚÜÊ[^YYØØ[”[›˜X›JBˆ[™\‹œ™[[Ý™PØ[˜XÚÜÊ[YTÙ]T[›˜X›JBˆYˆ
\Ý[™\”™YÚ\Ý\™Y
HÂˆ™YœË[œ™YÚ\Ý\“Û”Ú\™Y™Y™\™[˜ÙPÚ[™ÙS\Ý[™\Š™Y™\™[˜ÙS\Ý[™\ŠBˆ\Ý[™\”™YÚ\Ý\™YH˜[ÙBˆBˆ›ÙTÙ\ÜÚ[ÛˆH[ˆ[YTÙ\ÜÚ[ÛˆH[ˆÙ\šXÙT[[YTÝ]K›X\šÑ\ØÛÛ›™XÝY
™X\ÛÛŠBˆXYÛ›ÜÝXÓÙË˜Y
”ÑT•’PÑH‹œÝX›KZ[YH	™X\ÛÛˆŠBˆB‚ˆš]˜]H]HÛ\ÜÈ\š]™Y^
ˆ˜[^ˆÝš[™Ëˆ˜[ÛÛ™šY[ˆ›ÛÛX[‹ˆ˜[[ÙNˆÝš[™Ëˆ˜[˜]Ó[™Ýˆ[ˆ
B‚ˆš]˜]H]HÛ\ÜÈ›ÙRÙ^Jˆ˜[Ú[™ÝÒYˆ[ˆ˜[XÚØYÙS˜[YNˆÝš[™Ëˆ˜[šY]ÒYˆÝš[™Ëˆ˜[Û\ÜÓ˜[YNˆÝš[™Ëˆ˜[›Ý[™ÎˆÝš[™Ëˆ
B‚ˆš]˜]H]HÛ\ÜÈ[œ]Ù\ÜÚ[ÛŠˆ˜[Ù^Nˆ›ÙRÙ^Kˆ˜\ˆ\ÝÜš][ŽˆÝš[™ÈHˆ‹ˆ˜\ˆ\ÝÜš]P]ˆÛ™ÈHˆ˜\ˆØÚÐXÝ]™Nˆ›ÛÛX[ˆH˜[ÙKˆ˜\ˆØÚÙY^ˆÝš[™ÈHˆ‹ˆ˜\ˆ[ÝÐÛX\•[[ˆÛ™ÈHˆ˜\ˆÛÛ\ÜÚ][Û•[[ˆÛ™ÈHˆ˜\ˆÛÛ\ÜÚ][Û”[™[™Îˆ›ÛÛX[ˆH˜[ÙKˆ˜\ˆ[YTÙ]T[™[™Îˆ›ÛÛX[ˆH˜[ÙKˆ˜\ˆ[YTÙ]U[[ˆÛ™ÈHˆ˜\ˆ\ÝØœÙ\™Y]ˆÛ™ÈHˆ
HÂˆ[ˆÛX\“ØÚÊ
HÂˆØÚÐXÝ]™HH˜[ÙBˆØÚÙY^Hˆ‚ˆB‚ˆ[ˆ[›ØÚÑ›Ü”Ù[™

HÂˆ[ÝÐÛX\•[[HÞ\Ý[K˜Ý\œ™[[YSZ[\Ê
H
ÈÑS‘ÐÓPT—ÑÔPÑWÓTÂˆÛÛ\ÜÚ][Û•[[HˆÛÛ\ÜÚ][Û”[™[™ÈH˜[ÙBˆ[YTÙ]T[™[™ÈH˜[ÙBˆ[YTÙ]U[[HˆÛX\“ØÚÊ
BˆBˆB‚ˆš]˜]HÛÛ\[š[ÛˆØš™XÝÂˆÛÛœÝ˜[QÈH•\[™Ô™\XÙ\”ÝX›R[YH‚ˆÛÛœÝ˜[ÑS—ÕÔ’UWÑÕPT‘ÓTÈHLŒˆÛÛœÝ˜[ÓÓTUP’SUWÔÐÐS—ÒS•T•SÓTÈHLˆÛÛœÝ˜[ÑS‘ÐÓPT—ÑÔPÑWÓTÈHNˆÛÛœÝ˜[ÓÓ•S•ÔÐÐS—Õ“ÕWÓTÈHˆÛÛœÝ˜[ÓÓ•S•ÔÐÐS—ÑSVWÓTÈHLŒˆÛÛœÝ˜[VÑSPÒ×ÔÐÐS—ÑSVWÓTÈHÌˆÛÛœÝ˜[“ÐÕT×ÔÐÐS—ÑSVWÓTÈHˆÛÛœÝ˜[ÓPÒ×ÔÐÐS—ÑSVWÓTÈHLˆÛÛœÝ˜[ÒS‘Õ×ÔÐÐS—ÑSVWÓTÈHMˆÛÛœÝ˜[PVÕ‘QWÑTHˆÛÛœÝ˜[RS—ÑQUP“WÐÐS‘QUWÔÐÓÔ‘HHˆÛÛœÝ˜[SQT×ÔÖS•UP×ÕÒS‘Õ×ÒQHLŒ‚ˆËÈSQK[Û›HY]ÜœÈ˜YHHÛX[[[Ý[Ùˆ][˜ÞH›ÜˆÛÜœ™XÝ™\ÜËˆHÚ[™ÛBˆËÈX[X[Y]Ù]\È]ZXÚÛNÈ˜]ÚØÛÛ\ÜÚ[™ËÜÜYXÚY]ÈØZ]Û™Ù\‹‚ˆÛÛœÝ˜[SQWÔÒS‘ÓWÑQUÔÑUWÓTÈHLˆÛÛœÝ˜[SQWÐÓÓTÔÒS‘×ÔÑUWÓTÈHLLˆÛÛœÝ˜[SQT×ÐUÒÐQÓRSˆH‚ˆÛÛœÝ˜[SQWÐUÒÔ‘SSÕ‘WÓRSˆHˆÛÛœÝ˜[PVÓPS•PSÓÐÒ×ÑSUWÐÒT”ÈHˆBŸB