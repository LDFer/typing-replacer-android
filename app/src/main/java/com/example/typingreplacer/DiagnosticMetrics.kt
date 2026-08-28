package com.example.typingreplacer

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Privacy-preserving diagnostics. No input text is stored. */
object DiagnosticMetrics {
    private val lock = Any()

    private var sessionStartedAtWall = System.currentTimeMillis()
    private var sessionStartedAtElapsed = SystemClock.elapsedRealtime()
    private val eventCounts = linkedMapOf<String, Long>()
    private val packageCounts = linkedMapOf<String, Long>()
    private val failureCounts = linkedMapOf<String, Long>()
    private val lastTextEventByPackage = linkedMapOf<String, Long>()

    private var textEventCount = 0L
    private var maxTextEventGapMs = 0L
    private var lastTextEventElapsed = 0L
    private var nullSourceEventCount = 0L
    private var windowContentEventCount = 0L
    private var windowStateEventCount = 0L

    private var fullEventTextCount = 0L
    private var deltaRebuiltCount = 0L
    private var initialEventCount = 0L
    private var ambiguousEventCount = 0L

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
        packageCounts.clear()
        failureCounts.clear()
        lastTextEventByPackage.clear()
        textEventCount = 0
        maxTextEventGapMs = 0
        lastTextEventElapsed = 0
        nullSourceEventCount = 0
        windowContentEventCount = 0
        windowStateEventCount = 0
        fullEventTextCount = 0
        deltaRebuiltCount = 0
        initialEventCount = 0
        ambiguousEventCount = 0
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

    fun ingest(tag: String, message: String) = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        when (tag) {
            "APP-EVENT" -> ingestAppEventLocked(message, now)
            "APP-IME" -> ingestAppImeLocked(message)
            "APP-IME-WRITE" -> ingestAppImeWriteLocked(message, now)
            "APP-NODE-WRITE" -> {
                nodeWriteAttempts++
                if (boolValue(message, "setText")) nodeWriteSuccess++ else nodeWriteFailure++
            }
            "APP-TRANSFORM" -> {
                if (message.contains("replacement=true")) replacementHits++ else replacementMisses++
            }
            "APP-LOCK" -> lockRestores++

            // Backward-compatible parsing for previous WeChat-only builds.
            "WX-EVENT" -> ingestLegacyWxEventLocked(message, now)
            "WX-IME" -> ingestLegacyWxImeLocked(message)
            "WX-IME-WRITE" -> ingestLegacyWxImeWriteLocked(message, now)
            "WX-WRITE" -> {
                nodeWriteAttempts++
                if (message.contains("success")) nodeWriteSuccess++ else nodeWriteFailure++
            }
            "WX-TRANSFORM" -> {
                if (message.contains("replacement=true")) replacementHits++ else replacementMisses++
            }
            "WX-LOCK" -> lockRestores++

            "FLOW" -> if (message.contains("send-like")) sendUnlocks++
            "SERVICE" -> if (
                message.contains("解绑") || message.contains("销毁") || message.contains("onInterrupt")
            ) {
                recordFailureLocked("service:${message.take(64)}")
            }
        }
    }

    private fun ingestAppEventLocked(message: String, now: Long) {
        val pkg = stringValue(message, "pkg")
        val type = stringValue(message, "type").ifBlank { "UNKNOWN" }
        eventCounts[type] = (eventCounts[type] ?: 0L) + 1L
        if (pkg.isNotBlank()) packageCounts[pkg] = (packageCounts[pkg] ?: 0L) + 1L
        if (message.contains("source=null")) nullSourceEventCount++
        when (type) {
            "TEXT_CHANGED" -> {
                textEventCount++
                if (lastTextEventElapsed > 0L) {
                    maxTextEventGapMs = max(maxTextEventGapMs, now - lastTextEventElapsed)
                }
                lastTextEventElapsed = now
                if (pkg.isNotBlank()) lastTextEventByPackage[pkg] = now
            }
            "WINDOW_CONTENT_CHANGED" -> windowContentEventCount++
            "WINDOW_STATE_CHANGED", "WINDOWS_CHANGED" -> windowStateEventCount++
        }
    }

    private fun ingestAppImeLocked(message: String) {
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
            }
            message.startsWith("uncertain-event ") -> ambiguousEventCount++
            message.startsWith("event ") -> when (stringValue(message, "mode")) {
                "full-event" -> fullEventTextCount++
                "delta-rebuilt" -> deltaRebuiltCount++
                "initial-event" -> initialEventCount++
                "ambiguous-event" -> ambiguousEventCount++
            }
            message.startsWith("self-write ") -> selfWriteGuards++
        }
    }

    private fun ingestAppImeWriteLocked(message: String, now: Long) {
        imeWriteAttempts++
        val success = boolValue(message, "issued")
        val error = stringValue(message, "err")
        val pkg = stringValue(message, "pkg")
        val conn = boolValue(message, "conn")
        lastEditorPackage = stringValue(message, "editor").ifBlank { pkg }
        lastImeConnection = conn
        lastImeReady = success
        lastImeError = error
        if (success) imeWriteSuccess++ else {
            imeWriteFailure++
            recordFailureLocked("ime-write:${error.ifBlank { "unknown" }}")
        }
        val lastText = lastTextEventByPackage[pkg] ?: 0L
        if (lastText > 0L) {
            val latency = max(0L, now - lastText)
            if (latency <= 3000L) {
                imeWriteLatencyCount++
                imeWriteLatencyTotalMs += latency
                imeWriteLatencyMaxMs = max(imeWriteLatencyMaxMs, latency)
            }
        }
    }

    private fun ingestLegacyWxEventLocked(message: String, now: Long) {
        val type = message.substringBefore(' ').ifBlank { "UNKNOWN" }
        eventCounts[type] = (eventCounts[type] ?: 0L) + 1L
        packageCounts["com.tencent.mm"] = (packageCounts["com.tencent.mm"] ?: 0L) + 1L
        if (message.contains("source=null")) nullSourceEventCount++
        when (type) {
            "TEXT_CHANGED" -> {
                textEventCount++
                if (lastTextEventElapsed > 0L) maxTextEventGapMs = max(maxTextEventGapMs, now - lastTextEventElapsed)
                lastTextEventElapsed = now
                lastTextEventByPackage["com.tencent.mm"] = now
            }
            "WINDOW_CONTENT_CHANGED" -> windowContentEventCount++
            "WINDOW_STATE_CHANGED", "WINDOWS_CHANGED" -> windowStateEventCount++
        }
    }

    private fun ingestLegacyWxImeLocked(message: String) {
        if (message.startsWith("snapshot ")) {
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
        } else if (message.startsWith("self-write")) {
            selfWriteGuards++
        }
    }

    private fun ingestLegacyWxImeWriteLocked(message: String, now: Long) {
        imeWriteAttempts++
        val success = boolValue(message, "issued")
        val error = stringValue(message, "err")
        if (success) imeWriteSuccess++ else {
            imeWriteFailure++
            recordFailureLocked("ime-write:${error.ifBlank { "unknown" }}")
        }
        val lastText = lastTextEventByPackage["com.tencent.mm"] ?: 0L
        if (lastText > 0L) {
            val latency = max(0L, now - lastText)
            if (latency <= 3000L) {
                imeWriteLatencyCount++
                imeWriteLatencyTotalMs += latency
                imeWriteLatencyMaxMs = max(imeWriteLatencyMaxMs, latency)
            }
        }
    }

    fun recordTraceStored(stored: Boolean) = synchronized(lock) {
        if (stored) traceStored++ else traceDropped++
    }

    private fun boolValue(message: String, key: String): Boolean = stringValue(message, key) == "true"

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
        val totalEvents = eventCounts.values.sum()
        val eventRate = if (elapsed <= 0L) 0.0 else totalEvents * 1000.0 / elapsed
        val nullSourceRate = ratio(nullSourceEventCount, totalEvents)
        val contentShare = ratio(windowContentEventCount, totalEvents)
        buildString {
            appendLine("=== 诊断统计 ===")
            appendLine("会话时长: ${elapsed}ms")
            appendLine("涉及 App: ${packageCounts.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }.ifBlank { "无" }}")
            appendLine("事件类型: ${eventCounts.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "无" }}")
            appendLine("事件速率: ${format(eventRate)}/s, TEXT_CHANGED=$textEventCount, 最大相邻TEXT间隔=${maxTextEventGapMs}ms")
            appendLine("窗口/节点可见性: source=null $nullSourceEventCount/$totalEvents (${format(nullSourceRate * 100)}%), CONTENT占比=${format(contentShare * 100)}%, STATE=$windowStateEventCount")
            appendLine("事件文本判定: full=$fullEventTextCount deltaRebuilt=$deltaRebuiltCount initial=$initialEventCount ambiguous=$ambiguousEventCount")
            appendLine("IME快照: ready=$imeSnapshotReady/$imeSnapshotAttempts surroundingNull=$imeSnapshotSurroundingNull")
            appendLine("IME最后状态: editor=${lastEditorPackage.ifBlank { "-" }} ready=$lastImeReady conn=$lastImeConnection err=${lastImeError.ifBlank { "-" }}")
            appendLine("IME写入: success=$imeWriteSuccess/$imeWriteAttempts fail=$imeWriteFailure event→write avg=${avg(imeWriteLatencyTotalMs, imeWriteLatencyCount)}ms max=${imeWriteLatencyMaxMs}ms")
            appendLine("Node写入: success=$nodeWriteSuccess/$nodeWriteAttempts fail=$nodeWriteFailure")
            appendLine("替换: hit=$replacementHits miss=$replacementMisses selfGuard=$selfWriteGuards")
            appendLine("锁定恢复=$lockRestores 发送解锁=$sendUnlocks")
            appendLine("Trace: stored=$traceStored dropped=$traceDropped")
            appendLine("失败分布: ${failureCounts.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "无" }}")
            appendLine("优化提示: ${optimizationHintsLocked(eventRate, nullSourceRate, contentShare).joinToString("；").ifBlank { "当前未发现明显异常" }}")
        }.trimEnd()
    }

    private fun optimizationHintsLocked(
        eventRate: Double,
        nullSourceRate: Double,
        contentShare: Double,
    ): List<String> {
        val hints = mutableListOf<String>()
        if (nodeWriteFailure > 0 && imeWriteSuccess > 0) {
            hints += "检测到Node写入失败但IME回退成功，此设备应长期保留全局InputConnection回退"
        }
        if (imeSnapshotAttempts > 0 && imeSnapshotSurroundingNull * 2 >= imeSnapshotAttempts && imeWriteSuccess > 0) {
            hints += "surroundingText读取不可靠但盲写可用，应避免把surrounding-null视为致命失败"
        }
        if (imeWriteAttempts > 0 && imeWriteFailure * 20 > imeWriteAttempts) {
            hints += "IME写入失败率>5%，需检查当前编辑器包名和InputConnection生命周期"
        }
        if (ambiguousEventCount > fullEventTextCount + deltaRebuiltCount && textEventCount > 0) {
            hints += "多数TEXT_CHANGED无法可靠重建全文，需要增强事件delta/输入法组合文本处理"
        }
        if (textEventCount > 0 && replacementHits == 0L && nodeWriteAttempts == 0L && imeWriteAttempts == 0L) {
            hints += "收到文本事件但从未进入写入，优先检查规则命中和事件文本语义"
        }
        if (nullSourceRate >= 0.5 && contentShare >= 0.4) {
            hints += "大量事件source=null，应依赖IME通道并保持WINDOW_CONTENT_CHANGED节流"
        }
        if (eventRate > 20.0) {
            hints += "事件频率较高，可继续扩大内容事件节流窗口"
        }
        if (imeWriteLatencyCount >= 2 && imeWriteLatencyMaxMs > 250L) {
            hints += "存在>250ms写入延迟，需检查主线程扫描或ROM调度"
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
        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Throwable) {
            null
        }
        val versionName = packageInfo?.versionName ?: "?"
        val versionCode = if (packageInfo == null) {
            -1L
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return buildString {
            appendLine("Typing Replacer V2 - Universal Diagnostic Report")
            appendLine("generatedAt=$generated")
            appendLine("sessionStartedAt=$sessionStartedAtWall")
            appendLine("appVersion=$versionName($versionCode)")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("rules=$ruleCount compatibilityScan=$compatibilityScan lockReplacement=$lockReplacement verboseTrace=$verboseTrace")
            appendLine("batteryOptimizationIgnored=$batteryUnrestricted")
            appendLine("memoryUsed=${usedMb}MB memoryMax=${maxMb}MB")
            appendLine("privacy=NO_CHAT_TEXT; package/length/status/timing only")
            appendLine()
            appendLine(summary())
            appendLine()
            appendLine("=== 最近关键 Trace（不含输入正文） ===")
            append(trace.ifBlank { "无" })
        }
    }

    private fun ratio(value: Long, total: Long): Double =
        if (total <= 0L) 0.0 else value.toDouble() / total.toDouble()

    private fun avg(total: Long, count: Long): String =
        if (count <= 0L) "0.0" else format(total.toDouble() / count)

    private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)
}
