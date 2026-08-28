package com.example.typingreplacer

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale
import kotlin.math.max

/**
 * Privacy-preserving aggregated telemetry kept only in this app process.
 * No chat text is stored; only counters, lengths, timing and coarse states.
 */
object DiagnosticMetrics {
    private val lock = Any()

    private var sessionStartedAtWall = System.currentTimeMillis()
    private var sessionStartedAtElapsed = SystemClock.elapsedRealtime()

    private val eventCounts = linkedMapOf<String, Long>()
    private val failureCounts = linkedMapOf<String, Long>()

    private var textEventCount = 0L
    private var firstTextEventElapsed = 0L
    private var lastTextEventElapsed = 0L
    private var maxTextEventGapMs = 0L

    private var scans = 0L
    private var scanNodeHits = 0L
    private var scanImeHits = 0L
    private var scanMisses = 0L

    private var imeSnapshotAttempts = 0L
    private var imeSnapshotReady = 0L
    private var imeSnapshotSurroundingNull = 0L

    private var imeWriteAttempts = 0L
    private var imeWriteSuccess = 0L
    private var imeWriteFailure = 0L
    private var imeWriteTotalMs = 0L
    private var imeWriteMaxMs = 0L

    private var nodeWriteAttempts = 0L
    private var nodeWriteSuccess = 0L
    private var nodeWriteFailure = 0L
    private var nodeWriteTotalMs = 0L
    private var nodeWriteMaxMs = 0L

    private var replacementHits = 0L
    private var replacementMisses = 0L
    private var selfWriteGuards = 0L
    private var lockRestores = 0L
    private var sendUnlocks = 0L

    private var lastEditorPackage = ""
    private var lastImeReady = false
    private var lastImeConnection = false
    private var lastImeError = ""

    fun reset() = synchronized(lock) {
        sessionStartedAtWall = System.currentTimeMillis()
        sessionStartedAtElapsed = SystemClock.elapsedRealtime()
        eventCounts.clear()
        failureCounts.clear()
        textEventCount = 0
        firstTextEventElapsed = 0
        lastTextEventElapsed = 0
        maxTextEventGapMs = 0
        scans = 0
        scanNodeHits = 0
        scanImeHits = 0
        scanMisses = 0
        imeSnapshotAttempts = 0
        imeSnapshotReady = 0
        imeSnapshotSurroundingNull = 0
        imeWriteAttempts = 0
        imeWriteSuccess = 0
        imeWriteFailure = 0
        imeWriteTotalMs = 0
        imeWriteMaxMs = 0
        nodeWriteAttempts = 0
        nodeWriteSuccess = 0
        nodeWriteFailure = 0
        nodeWriteTotalMs = 0
        nodeWriteMaxMs = 0
        replacementHits = 0
        replacementMisses = 0
        selfWriteGuards = 0
        lockRestores = 0
        sendUnlocks = 0
        lastEditorPackage = ""
        lastImeReady = false
        lastImeConnection = false
        lastImeError = ""
    }

    fun recordEvent(name: String, isTextChange: Boolean = false) = synchronized(lock) {
        eventCounts[name] = (eventCounts[name] ?: 0L) + 1L
        if (isTextChange) {
            val now = SystemClock.elapsedRealtime()
            textEventCount++
            if (firstTextEventElapsed == 0L) firstTextEventElapsed = now
            if (lastTextEventElapsed > 0L) {
                maxTextEventGapMs = max(maxTextEventGapMs, now - lastTextEventElapsed)
            }
            lastTextEventElapsed = now
        }
    }

    fun recordScan(result: String) = synchronized(lock) {
        scans++
        when (result) {
            "node" -> scanNodeHits++
            "ime" -> scanImeHits++
            else -> scanMisses++
        }
    }

    fun recordImeSnapshot(ready: Boolean, connection: Boolean, editorPackage: String, error: String) = synchronized(lock) {
        imeSnapshotAttempts++
        if (ready) imeSnapshotReady++
        if (error == "surrounding-null") imeSnapshotSurroundingNull++
        lastEditorPackage = editorPackage
        lastImeReady = ready
        lastImeConnection = connection
        lastImeError = error
        if (error.isNotBlank()) recordFailureLocked("ime-snapshot:$error")
    }

    fun recordImeWrite(success: Boolean, durationMs: Long, error: String) = synchronized(lock) {
        imeWriteAttempts++
        if (success) imeWriteSuccess++ else imeWriteFailure++
        imeWriteTotalMs += durationMs
        imeWriteMaxMs = max(imeWriteMaxMs, durationMs)
        if (!success) recordFailureLocked("ime-write:${error.ifBlank { "unknown" }}")
    }

    fun recordNodeWrite(success: Boolean, durationMs: Long, packageName: String) = synchronized(lock) {
        nodeWriteAttempts++
        if (success) nodeWriteSuccess++ else nodeWriteFailure++
        nodeWriteTotalMs += durationMs
        nodeWriteMaxMs = max(nodeWriteMaxMs, durationMs)
        if (!success) recordFailureLocked("node-write:${packageName.ifBlank { "unknown" }}")
    }

    fun recordReplacement(hit: Boolean) = synchronized(lock) {
        if (hit) replacementHits++ else replacementMisses++
    }

    fun recordSelfWriteGuard() = synchronized(lock) { selfWriteGuards++ }
    fun recordLockRestore() = synchronized(lock) { lockRestores++ }
    fun recordSendUnlock() = synchronized(lock) { sendUnlocks++ }

    fun recordFailure(reason: String) = synchronized(lock) { recordFailureLocked(reason) }

    private fun recordFailureLocked(reason: String) {
        failureCounts[reason] = (failureCounts[reason] ?: 0L) + 1L
    }

    fun summary(): String = synchronized(lock) {
        val elapsed = max(0L, SystemClock.elapsedRealtime() - sessionStartedAtElapsed)
        buildString {
            appendLine("=== 诊断统计 ===")
            appendLine("会话时长: ${elapsed}ms")
            appendLine("微信事件: ${eventCounts.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "无" }}")
            appendLine("TEXT_CHANGED: $textEventCount, 最大事件间隔: ${maxTextEventGapMs}ms")
            appendLine("扫描: total=$scans node=$scanNodeHits ime=$scanImeHits miss=$scanMisses")
            appendLine("IME快照: ready=$imeSnapshotReady/$imeSnapshotAttempts surroundingNull=$imeSnapshotSurroundingNull")
            appendLine("IME最后状态: editor=${lastEditorPackage.ifBlank { "-" }} ready=$lastImeReady conn=$lastImeConnection err=${lastImeError.ifBlank { "-" }}")
            appendLine("IME写入: success=$imeWriteSuccess/$imeWriteAttempts fail=$imeWriteFailure avg=${avg(imeWriteTotalMs, imeWriteAttempts)}ms max=${imeWriteMaxMs}ms")
            appendLine("Node写入: success=$nodeWriteSuccess/$nodeWriteAttempts fail=$nodeWriteFailure avg=${avg(nodeWriteTotalMs, nodeWriteAttempts)}ms max=${nodeWriteMaxMs}ms")
            appendLine("替换: hit=$replacementHits miss=$replacementMisses selfGuard=$selfWriteGuards")
            appendLine("锁定恢复: $lockRestores, 发送解锁: $sendUnlocks")
            appendLine("失败分布: ${failureCounts.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "无" }}")
        }.trimEnd()
    }

    fun buildReport(
        context: Context,
        ruleCount: Int,
        compatibilityScan: Boolean,
        lockReplacement: Boolean,
        trace: String,
    ): String {
        val pm = context.getSystemService(PowerManager::class.java)
        val batteryUnrestricted = try {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Throwable) {
            false
        }
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)

        return buildString {
            appendLine("Typing Replacer V2 - Diagnostic Report")
            appendLine("generatedAt=${System.currentTimeMillis()}")
            appendLine("appVersion=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("rules=$ruleCount compatibilityScan=$compatibilityScan lockReplacement=$lockReplacement")
            appendLine("batteryOptimizationIgnored=$batteryUnrestricted")
            appendLine("memoryUsed=${usedMb}MB memoryMax=${maxMb}MB")
            appendLine()
            appendLine(summary())
            appendLine()
            appendLine("=== 最近关键 Trace（不含聊天正文） ===")
            append(trace.ifBlank { "无" })
        }
    }

    private fun avg(total: Long, count: Long): String =
        if (count <= 0) "0.0" else String.format(Locale.US, "%.1f", total.toDouble() / count)
}
