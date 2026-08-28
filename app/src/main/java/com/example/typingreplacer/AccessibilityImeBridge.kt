package com.example.typingreplacer

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * Android 13+ generic editor bridge for accessibility services.
 *
 * Accessibility events and AccessibilityInputConnection snapshots are not an
 * atomic stream. In particular, speech/predictive IMEs can update the editor
 * first and deliver the corresponding TYPE_VIEW_TEXT_CHANGED event later.
 * Writing immediately from either side can therefore commit text into an IME
 * that is still holding an older composing buffer, which produces duplicated
 * or progressively accumulated prefixes.
 *
 * Normal replacement writes are queued for a short stability window. Any
 * observed editor movement cancels the stale request. Once the editor has
 * already been quiet long enough for the service-side composing guard to have
 * expired, the final replacement is committed immediately instead of paying a
 * second stability delay. A delete-restore of text that this bridge previously
 * committed is also allowed through immediately so lock mode stays responsive.
 */
@TargetApi(33)
object AccessibilityImeBridge {
    private val queueGuard = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingWrite: PendingWrite? = null
    private var lastCommittedPackage = ""
    private var lastCommittedTarget = ""

    private var lastObservedPackage = ""
    private var lastObservedText = ""
    private var lastObservedAtElapsed = 0L

    private var nudgeService: WeakReference<AccessibilityService>? = null
    private var nudgePackage = ""

    private val flushRunnable = Runnable { flushPendingWrite() }
    private val settleNudgeRunnable = Runnable { issueSettleNudge() }

    private data class PendingWrite(
        val service: WeakReference<AccessibilityService>,
        val packageName: String,
        val expectedCurrent: String,
        val target: String,
    )

    data class Snapshot(
        val ready: Boolean,
        val editorPackage: String,
        val inputStarted: Boolean,
        val hasConnection: Boolean,
        val text: String?,
        val offset: Int,
        val selectionStart: Int,
        val selectionEnd: Int,
        val error: String = "",
    )

    data class Result(
        val issued: Boolean,
        val editorPackage: String,
        val inputStarted: Boolean,
        val hasConnection: Boolean,
        val surroundingLength: Int,
        val surroundingMatchesExpected: Boolean,
        val selectionStart: Int,
        val selectionEnd: Int,
        val error: String = "",
    )

    fun snapshot(
        service: AccessibilityService,
        expectedPackage: String? = null,
    ): Snapshot {
        return try {
            val inputMethod = service.inputMethod ?: return Snapshot(
                ready = false,
                editorPackage = "",
                inputStarted = false,
                hasConnection = false,
                text = null,
                offset = -1,
                selectionStart = -1,
                selectionEnd = -1,
                error = "input-method-null",
            )

            val editorInfo = inputMethod.currentInputEditorInfo
            val editorPackage = editorInfo?.packageName.orEmpty()
            val started = inputMethod.currentInputStarted
            val connection = inputMethod.currentInputConnection

            if (
                (expectedPackage != null && expectedPackage.isNotBlank() && editorPackage != expectedPackage) ||
                !started ||
                connection == null
            ) {
                cancelPendingForUnavailableEditor(editorPackage)
                return Snapshot(
                    ready = false,
                    editorPackage = editorPackage,
                    inputStarted = started,
                    hasConnection = connection != null,
                    text = null,
                    offset = -1,
                    selectionStart = -1,
                    selectionEnd = -1,
                    error = if (editorPackage != expectedPackage && !expectedPackage.isNullOrBlank()) {
                        "editor-package-mismatch"
                    } else {
                        "editor-not-ready"
                    },
                )
            }

            val surrounding = connection.getSurroundingText(MAX_SURROUNDING, MAX_SURROUNDING, 0)
                ?: return Snapshot(
                    ready = false,
                    editorPackage = editorPackage,
                    inputStarted = started,
                    hasConnection = true,
                    text = null,
                    offset = -1,
                    selectionStart = -1,
                    selectionEnd = -1,
                    error = "surrounding-null",
                )

            val text = surrounding.text?.toString()
            if (text != null) {
                observeEditor(service, editorPackage, text)
                cancelPendingIfEditorMoved(editorPackage, text)
            }

            Snapshot(
                ready = true,
                editorPackage = editorPackage,
                inputStarted = started,
                hasConnection = true,
                text = text,
                offset = surrounding.offset,
                selectionStart = surrounding.offset + surrounding.selectionStart,
                selectionEnd = surrounding.offset + surrounding.selectionEnd,
            )
        } catch (t: Throwable) {
            Snapshot(
                ready = false,
                editorPackage = "",
                inputStarted = false,
                hasConnection = false,
                text = null,
                offset = -1,
                selectionStart = -1,
                selectionEnd = -1,
                error = t.javaClass.simpleName + ":" + (t.message ?: ""),
            )
        }
    }

    fun replaceAll(
        service: AccessibilityService,
        expectedPackage: String,
        expectedCurrent: String,
        target: String,
        probeSurrounding: Boolean = true,
    ): Result {
        return try {
            val inputMethod = service.inputMethod ?: return failureResult("input-method-null")
            val editorInfo = inputMethod.currentInputEditorInfo
            val editorPackage = editorInfo?.packageName.orEmpty()
            val started = inputMethod.currentInputStarted
            val connection = inputMethod.currentInputConnection

            if (editorPackage != expectedPackage || !started || connection == null) {
                cancelPendingForUnavailableEditor(editorPackage)
                return Result(
                    issued = false,
                    editorPackage = editorPackage,
                    inputStarted = started,
                    hasConnection = connection != null,
                    surroundingLength = -1,
                    surroundingMatchesExpected = false,
                    selectionStart = -1,
                    selectionEnd = -1,
                    error = if (editorPackage != expectedPackage) {
                        "editor-package-mismatch"
                    } else {
                        "editor-not-ready"
                    },
                )
            }

            val validationSurrounding = try {
                connection.getSurroundingText(MAX_SURROUNDING, MAX_SURROUNDING, 0)
            } catch (_: Throwable) {
                null
            }
            val validationText = validationSurrounding?.text?.toString()
            if (validationText != null) observeEditor(service, editorPackage, validationText)

            val editorAhead = validationText != null && validationText != expectedCurrent
            if (editorAhead) {
                cancelPendingIfEditorMoved(editorPackage, validationText)
                DiagnosticLog.add(
                    "APP-COMPOSE",
                    "bridge-reject-stale pkg=$editorPackage expectedLen=${expectedCurrent.length} actualLen=${validationText.length}",
                )
                return Result(
                    issued = false,
                    editorPackage = editorPackage,
                    inputStarted = started,
                    hasConnection = true,
                    surroundingLength = if (probeSurrounding) validationText.length else -1,
                    surroundingMatchesExpected = false,
                    selectionStart = validationSurrounding?.let { it.offset + it.selectionStart } ?: -1,
                    selectionEnd = validationSurrounding?.let { it.offset + it.selectionEnd } ?: -1,
                    error = "editor-state-ahead",
                )
            }

            val selectionStart = if (probeSurrounding) {
                validationSurrounding?.let { it.offset + it.selectionStart } ?: -1
            } else {
                -1
            }
            val selectionEnd = if (probeSurrounding) {
                validationSurrounding?.let { it.offset + it.selectionEnd } ?: -1
            } else {
                -1
            }
            val surroundingLength = if (probeSurrounding) validationText?.length ?: -1 else -1

            val immediateRestore = synchronized(queueGuard) {
                editorPackage == lastCommittedPackage &&
                    target == lastCommittedTarget &&
                    expectedCurrent.length < target.length
            }

            if (immediateRestore) {
                cancelPending()
                issueWrite(connection, expectedCurrent, target)
                rememberCommitted(editorPackage, target)
                DiagnosticLog.add(
                    "APP-COMPOSE",
                    "bridge-immediate-lock-restore pkg=$editorPackage currentLen=${expectedCurrent.length} targetLen=${target.length}",
                )
                return successResult(
                    editorPackage,
                    started,
                    surroundingLength,
                    validationText == expectedCurrent,
                    selectionStart,
                    selectionEnd,
                )
            }

            // The service already waits out its composing grace period before it
            // performs the final full-pass replacement. If this exact editor text
            // has also been unchanged for a substantial interval, committing here
            // is safe and avoids paying another 900ms bridge delay.
            val quietMs = observedQuietMs(editorPackage, expectedCurrent)
            if (validationText == expectedCurrent && quietMs >= FAST_COMMIT_MIN_QUIET_MS) {
                cancelPending()
                issueWrite(connection, expectedCurrent, target)
                rememberCommitted(editorPackage, target)
                DiagnosticLog.add(
                    "APP-COMPOSE",
                    "bridge-fast-stable-commit pkg=$editorPackage quietMs=$quietMs currentLen=${expectedCurrent.length} targetLen=${target.length}",
                )
                return successResult(
                    editorPackage,
                    started,
                    surroundingLength,
                    true,
                    selectionStart,
                    selectionEnd,
                )
            }

            queueStableWrite(service, editorPackage, expectedCurrent, target)
            successResult(
                editorPackage,
                started,
                surroundingLength,
                validationText == expectedCurrent,
                selectionStart,
                selectionEnd,
            )
        } catch (t: Throwable) {
            failureResult(t.javaClass.simpleName + ":" + (t.message ?: ""))
        }
    }

    private fun observeEditor(
        service: AccessibilityService,
        packageName: String,
        text: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        var changed = false
        synchronized(queueGuard) {
            if (packageName != lastObservedPackage || text != lastObservedText) {
                lastObservedPackage = packageName
                lastObservedText = text
                lastObservedAtElapsed = now
                nudgeService = WeakReference(service)
                nudgePackage = packageName
                changed = true
            }
        }

        if (changed) {
            mainHandler.removeCallbacks(settleNudgeRunnable)
            mainHandler.postDelayed(settleNudgeRunnable, SETTLE_NUDGE_DELAY_MS)
        }
    }

    private fun observedQuietMs(packageName: String, text: String): Long {
        val now = SystemClock.elapsedRealtime()
        return synchronized(queueGuard) {
            if (packageName == lastObservedPackage && text == lastObservedText) {
                (now - lastObservedAtElapsed).coerceAtLeast(0L)
            } else {
                0L
            }
        }
    }

    private fun issueSettleNudge() {
        val service: AccessibilityService
        val packageName: String
        synchronized(queueGuard) {
            service = nudgeService?.get() ?: return
            packageName = nudgePackage
        }
        if (packageName.isBlank()) return

        try {
            // We only need the service to schedule a fresh editor inspection.
            // VIEW_FOCUSED is used because the service handles it with a short,
            // unconditional scan delay and it does not mutate editor/session state.
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED)
            try {
                event.packageName = packageName
                service.onAccessibilityEvent(event)
                DiagnosticLog.add(
                    "APP-COMPOSE",
                    "bridge-settle-nudge pkg=$packageName delayMs=$SETTLE_NUDGE_DELAY_MS",
                )
            } finally {
                event.recycle()
            }
        } catch (t: Throwable) {
            DiagnosticLog.add(
                "APP-COMPOSE",
                "bridge-settle-nudge-error pkg=$packageName err=${t.javaClass.simpleName}",
            )
        }
    }

    private fun queueStableWrite(
        service: AccessibilityService,
        packageName: String,
        expectedCurrent: String,
        target: String,
    ) {
        var shouldSchedule = false
        synchronized(queueGuard) {
            val existing = pendingWrite
            val sameRequest = existing != null &&
                existing.packageName == packageName &&
                existing.expectedCurrent == expectedCurrent &&
                existing.target == target
            if (!sameRequest) {
                pendingWrite = PendingWrite(
                    service = WeakReference(service),
                    packageName = packageName,
                    expectedCurrent = expectedCurrent,
                    target = target,
                )
                shouldSchedule = true
            }
        }

        if (shouldSchedule) {
            mainHandler.removeCallbacks(flushRunnable)
            mainHandler.postDelayed(flushRunnable, STABILITY_DELAY_MS)
            DiagnosticLog.add(
                "APP-COMPOSE",
                "bridge-queued pkg=$packageName currentLen=${expectedCurrent.length} targetLen=${target.length} delayMs=$STABILITY_DELAY_MS",
            )
        }
    }

    private fun flushPendingWrite() {
        val pending = synchronized(queueGuard) { pendingWrite } ?: return
        val service = pending.service.get()
        if (service == null) {
            cancelPending()
            return
        }

        try {
            val inputMethod = service.inputMethod
            val editorPackage = inputMethod?.currentInputEditorInfo?.packageName.orEmpty()
            val started = inputMethod?.currentInputStarted == true
            val connection = inputMethod?.currentInputConnection
            if (editorPackage != pending.packageName || !started || connection == null) {
                DiagnosticLog.add(
                    "APP-COMPOSE",
                    "bridge-cancel-editor pkg=${pending.packageName} actual=$editorPackage started=$started conn=${connection != null}",
                )
                cancelPending()
                return
            }

            val surrounding = try {
                connection.getSurroundingText(MAX_SURROUNDING, MAX_SURROUNDING, 0)
            } catch (_: Throwable) {
                null
            }
            val actual = surrounding?.text?.toString()
            if (actual != null) observeEditor(service, editorPackage, actual)
            if (actual != null && actual != pending.expectedCurrent) {
                DiagnosticLog.add(
                    "APP-COMPOSE",
                    "bridge-cancel-stale pkg=${pending.packageName} expectedLen=${pending.expectedCurrent.length} actualLen=${actual.length}",
                )
                cancelPending()
                return
            }

            synchronized(queueGuard) {
                if (pendingWrite === pending) pendingWrite = null
            }
            mainHandler.removeCallbacks(flushRunnable)

            issueWrite(connection, pending.expectedCurrent, pending.target)
            rememberCommitted(pending.packageName, pending.target)
            DiagnosticLog.add(
                "APP-COMPOSE",
                "bridge-commit-stable pkg=${pending.packageName} currentLen=${pending.expectedCurrent.length} targetLen=${pending.target.length}",
            )
        } catch (t: Throwable) {
            DiagnosticLog.add(
                "APP-COMPOSE",
                "bridge-commit-error pkg=${pending.packageName} err=${t.javaClass.simpleName}",
            )
            cancelPending()
        }
    }

    private fun issueWrite(
        connection: android.accessibilityservice.InputMethod.AccessibilityInputConnection,
        expectedCurrent: String,
        target: String,
    ) {
        connection.setSelection(0, expectedCurrent.length)
        connection.commitText(target, 1, null)
    }

    private fun rememberCommitted(packageName: String, target: String) {
        synchronized(queueGuard) {
            lastCommittedPackage = packageName
            lastCommittedTarget = target
            lastObservedPackage = packageName
            lastObservedText = target
            lastObservedAtElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun cancelPendingIfEditorMoved(packageName: String, actualText: String) {
        var cancelled: PendingWrite? = null
        synchronized(queueGuard) {
            val pending = pendingWrite
            if (
                pending != null &&
                pending.packageName == packageName &&
                actualText != pending.expectedCurrent
            ) {
                cancelled = pending
                pendingWrite = null
            }
        }
        if (cancelled != null) {
            mainHandler.removeCallbacks(flushRunnable)
            DiagnosticLog.add(
                "APP-COMPOSE",
                "bridge-observed-newer pkg=$packageName expectedLen=${cancelled!!.expectedCurrent.length} actualLen=${actualText.length}; queued-write-cancelled",
            )
        }
    }

    private fun cancelPendingForUnavailableEditor(actualPackage: String) {
        val shouldCancel = synchronized(queueGuard) {
            val pending = pendingWrite
            pending != null && (actualPackage.isBlank() || pending.packageName != actualPackage)
        }
        if (shouldCancel) cancelPending()
    }

    private fun cancelPending() {
        synchronized(queueGuard) { pendingWrite = null }
        mainHandler.removeCallbacks(flushRunnable)
    }

    private fun successResult(
        editorPackage: String,
        inputStarted: Boolean,
        surroundingLength: Int,
        surroundingMatchesExpected: Boolean,
        selectionStart: Int,
        selectionEnd: Int,
    ): Result = Result(
        issued = true,
        editorPackage = editorPackage,
        inputStarted = inputStarted,
        hasConnection = true,
        surroundingLength = surroundingLength,
        surroundingMatchesExpected = surroundingMatchesExpected,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
    )

    private fun failureResult(error: String): Result = Result(
        issued = false,
        editorPackage = "",
        inputStarted = false,
        hasConnection = false,
        surroundingLength = -1,
        surroundingMatchesExpected = false,
        selectionStart = -1,
        selectionEnd = -1,
        error = error,
    )

    private const val MAX_SURROUNDING = 8192
    private const val STABILITY_DELAY_MS = 650L
    private const val FAST_COMMIT_MIN_QUIET_MS = 500L
    private const val SETTLE_NUDGE_DELAY_MS = 600L
}
