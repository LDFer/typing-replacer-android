package com.example.typingreplacer

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Privacy-preserving diagnostics kept only in this app process.
 * No chat text is stored: only counters, lengths, timing and coarse states.
 */
object DiagnosticMetrics {
    private val lock = Any()

    private var sessionStartedAtWall = System.currentTimeMillis()
    private var sessionStartedAtElapsed = SystemClock.elapsedRealtime()
    private val eventCounts = linkedMapOf<String, Long>()
    private val failureCounts = linkedMapOf<String, Long>()

    private var textEventCount = 0L
    private var lastTextEventElapsed = 0L
    private var maxTextEventGapMs = 0L

    private var scans = 0L
    private var scanNodeHits = 0L
    private var scanImeHits = 0L
    private var scanMisses = 0L
    private var scanPending = false

    private var imeSnapshotAttempts = 0L
    private var imeSnapshotReady = 0L
    private var imeSnapshotSurroundingNull = 0L

    private var imeWriteAttempts = 0L
    private var imeWriteSuccess = 0L
    private var imeWriteFailure = 0L
    private var imeWriteLatencyCount = 0L
    private var imeWriteLatencyTotalMs = 0L
    private var imeWriteLatencyMaxMs = 0L

    private var nodeWriteAttempts = 0L
    private var nodeWriteSuccess = 0L
    private var nodeWriteFailure = 0L

    private var replacementHits = 0L
    private var replacementMisses = 0L
    private var selfWriteGuards = 0L
    private var lockRestores = 0L
    private var sendUnlocks = 0L

    private var traceStored = 0L
    private var traceDropped = 0L

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
        lastTextEventElapsed = 0
        maxTextEventGapMs = 0
        scans = 0
        scanNodeHits = 0
        scanImeHits = 0
        scanMisses = 0
        scanPending = false
        imeSnapshotAttempts = 0
        imeSnapshotReady = 0
        imeSnapshotSurroundingNull = 0
        imeWriteAttempts = 0
        imeWriteSuccess = 0
        imeWriteFailure = 0
        imeWriteLatencyCount = 0
        imeWriteLatencyTotalMs = 0
        imeWriteLatencyMaxMs = 0
        nodeWriteAttempts = 0
        nodeWriteSuccess = 0
        nodeWriteFailure = 0
        replacementHits = 0
        replacementMisses = 0
        selfWriteGuards = 0
        lockRestores = 0
        sendUnlocks = 0
        traceStored = 0
        traceDropped = 0
        lastEditorPackage = ""
        lastImeReady = false
        lastImeConnection = false
        lastImeError = ""
    }

    /** Parse the existing structured trace so instrumentation stays centralized. */
    fun ingest(tag: String, message: String) = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        when (tag) {
            "WX-EVENT" -> {
                val name = message.substringBefore(' ').ifBlank { "UNKNOWN" }
                eventCounts[name] = (eventCounts[name] ?: 0L) + 1L
                if (name == "TEXT_CHANGED") {
                    textEventCount++
                    if (lastTextEventElapsed > 0L) {
                        maxTextEventGapMs = max(maxTextEventGapMs, now - lastTextEventElapsed)
                    }
                    lastTextEventElapsed = now
                }
            }

            "WX-SCAN" -> when {
                message.startsWith("begin ") -> {
                    scans++
                    scanPending = true
                }
                message.contains("node path found") -> {
                    scanNodeHits++
                    scanPending = false
                }
                message.startsWith("no focused editable") -> {
                    scanMisses++
                    scanPending = false
                }
            }

            "WX-IME" -> {
                when {
                    message.startsWith("snapshot ") -> {
                        imeSnapshotAttempts++
                        val ready = boolValue(message, "ready")
                        val conn = boolValue(message, "conn")
                        val editor = stringValue(message, "editor")
                        val error = stringValue(message, "err")
                        if (ready) imeSnapshotReady++
                        if (error == "surrounding-null") imeSnapshotSurroundingNull++
                        lastEditorPackage = editor
                        lastImeReady = ready
                        lastImeConnection = conn
                        lastImeError = error
                        if (error.isNotBlank()) recordFailureLocked("ime-snapshot:$error")
                        if (scanPending && ready) {
                            scanImeHits++
                            scanPending = false
                        }
                    }
                    message.startsWith("self-write confirmed") -> selfWriteGuards++
                }
            }

            "WX-TRANSFORM" -> {
                val hit = message.contains("replacement=true")
                if (hit) replacementHits++ else replacementMisses++
            }

            "WX-IME-WRITE" -> {
                imeWriteAttempts++
                val success = boolValue(message, "issued")
                val error = stringValue(message, "err")
                if (success) imeWriteSuccess++ else {
                    imeWriteFailure++
                    recordFailureLocked("ime-write:${error.ifBlank { "unknown" }}")
                }
                if (lastTextEventElapsed > 0L) {
                    val latency = max(0L, now - lastTextEventElapsed)
                    // Ignore unrelated writes after long idle periods.
                    if (latency <= 3000L) {
                        imeWriteLatencyCount++
                        imeWriteLatencyTotalMs += latency
                        imeWriteLatencyMaxMs = max(imeWriteLatencyMaxMs, latency)
                    }
                }
            }

            "WX-WRITE" -> {
                nodeWriteAttempts++
                if (message.contains("success")) nodeWriteSuccess++ else {
                    nodeWriteFailure++
                    recordFailureLocked("wechat-node-write")
                }
            }

            "WX-LOCK" -> lockRestores++
            "FLOW" -> if (message.contains("send-like")) sendUnlocks++
            "SERVICE" -> {
                if (message.contains("解绑") || message.contains("销毁") || message.contains("onInterrupt")) {
                    recordFailureLocked("service:${message.take(48)}")
                }
            }
        }
    }

    fun recordTraceStored(stored: Boolean) = synchronized(lock) {
        if (stored) traceStored++ else traceDropped++
    }

    private fun boolValue(message: String, key: String): Boolean =
        stringValue(message, key) == "true"

    private fun stringValue(message: String, key: String): String {
        val marker = "$key="
        val start = message.indexOf(marker)
        if (start < 0) return ""
        val valueStart = start + marker.length
        val end = message.indexOf(' ', valueStart).let { if (it < 0) message.length else it }
        return message.substring(valueStart, end).trim()
    }

    private fun recordFailureLocked(reason: String) {
        failureCounts[reason] = (failureCounts[reason] ?: 0L) + 1L
    }

    fun summary(): String = synchronized(lock) {
        val elapsed = max(0L, SystemClock.elapsedRealtime() - sessionStartedAtElapsed)
        val eventRate = if (elapsed <= 0) 0.0 else eventCounts.values.sum() * 1000.0 / elapsed
        buildString {
            appendLine("=== 诊断统计 ===")
            appendLine("会话时长: ${elapsed}ms")
            appendLine("微信事件: ${eventCounts.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "无" }}")
            appendLine("事件速率: ${format(eventRate)}/s, TEXT_CHANGED=$textEventCount, 最大相邻TEXT间隔=${maxTextEventGapMs}ms")
            appendLine("扫描: total=$scans node=$scanNodeHits ime=$scanImeHits miss=$scanMisses")
            appendLine("IME快照: ready=$imeSnapshotReady/$imeSnapshotAttempts surroundingNull=$imeSnapshotSurroundingNull")
            appendLine("IME最后状态: editor=${lastEditorPackage.ifBlank { "-" }} ready=$lastImeReady conn=$lastImeConnection err=${lastImeError.ifBlank { "-" }}")
            appendLine("IME写入: success=$imeWriteSuccess/$imeWriteAttempts fail=$imeWriteFailure event→write avg=${avg(imeWriteLatencyTotalMs, imeWriteLatencyCount)}ms max=${imeWriteLatencyMaxMs}ms")
            appendLine("Node写入: success=$nodeWriteSuccess/$nodeWriteAttempts fail=$nodeWriteFailure")
            appendLine("替换: hit=$replacementHits miss=$replacementMisses selfGuard=$selfWriteGuards")
            appendLine("锁定恢复=$lockRestores 发送解锁=$sendUnlocks")
            appendLine("Trace: stored=$traceStored dropped=$traceDropped")
            appendLine("失败分布: ${failureCounts.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "无" }}")
            appendLine("优化提示: ${optimizationHintsLocked(eventRate).joinToString("；").ifBlank { "当前未发现明显异常" }}")
        }.trimEnd()
    }

    private fun optimizationHintsLocked(eventRate: Double): List<String> {
        val hints = mutableListOf<String>()
        if (imeWriteAttempts > 0 && imeWriteFailure * 20 > imeWriteAttempts) {
            hints += "IME写入失败率>5%，优先检查InputConnection生命周期"
        }
        if (imeSnapshotAttempts > 0 && imeSnapshotSurroundingNull * 10 > imeSnapshotAttempts) {
            hints += "surrounding-null偏多，应降低发送/窗口切换瞬间的快照频率"
        }
        if (scans >= 5 && scanImeHits * 2 >= scans && scanNodeHits == 0L) {
            hints += "微信主要依赖IME，可减少Accessibility树扫描"
        }
        if (eventRate > 20.0) {
            hints += "事件频率较高，可对WINDOW_CONTENT_CHANGED进一步节流"
        }
        if (imeWriteLatencyCount >= 2 && imeWriteLatencyMaxMs > 250L) {
            hints += "存在>250ms写入延迟，需检查主线程扫描/日志开销"
        }
        if (traceDropped > 0) {
            hints += "详细Trace过多被采样丢弃，可缩小诊断窗口"
        }
        return hints
    }

    fun buildReport(
        context: Context,
        ruleCount: Int,
        compatibilityScan: Boolean,
        lockReplacement: Boolean,
        verboseTrace: Boolean,
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
        val generated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

        return buildString {
            appendLine("Typing Replacer V2 - Diagnostic Report")
            appendLine("generatedAt=$generated")
            appendLine("sessionStartedAt=$sessionStartedAtWall")
            appendLine("appVersion=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("rules=$ruleCount compatibilityScan=$compatibilityScan lockReplacement=$lockReplacement verboseTrace=$verboseTrace")
            appendLine("batteryOptimizationIgnored=$batteryUnrestricted")
            appendLine("memoryUsed=${usedMb}MB memoryMax=${maxMb}MB")
            appendLine("privacy=NO_CHAT_TEXT; lengths/status/timing only")
            appendLine()
            appendLine(summary())
            appendLine()
            appendLine("=== 最近关键 Trace（不含聊天正文） ===")
            append(trace.ifBlank { "无" })
        }
    }

    private fun avg(total: Long, count: Long): String =
        if (count <= 0) "0.0" else format(total.toDouble() / count)

    private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)
}
