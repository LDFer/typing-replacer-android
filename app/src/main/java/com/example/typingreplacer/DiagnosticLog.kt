package com.example.typingreplacer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-process privacy-preserving trace ring buffer. */
object DiagnosticLog {
    private const val MAX_LINES = 360
    private const val DUPLICATE_WINDOW_MS = 180L
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var verbose = false
    private var lastStoredKey = ""
    private var lastStoredAt = 0L

    fun add(tag: String, message: String) {
        DiagnosticMetrics.ingest(tag, message)

        val important = isImportant(tag, message)
        if (!verbose && !important) return

        val now = System.currentTimeMillis()
        val key = "$tag|$message"
        synchronized(lock) {
            if (verbose && key == lastStoredKey && now - lastStoredAt < DUPLICATE_WINDOW_MS) {
                DiagnosticMetrics.recordTraceStored(false)
                return
            }
            val line = "${formatter.format(Date(now))} [$tag] $message"
            lines.addLast(line)
            lastStoredKey = key
            lastStoredAt = now
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        DiagnosticMetrics.recordTraceStored(true)
    }

    fun startSession() {
        synchronized(lock) {
            lines.clear()
            lastStoredKey = ""
            lastStoredAt = 0L
        }
        DiagnosticMetrics.reset()
        verbose = true
        add("SESSION", "diagnostic session started")
    }

    fun stopSession() {
        add("SESSION", "diagnostic session stopped")
        verbose = false
    }

    fun isVerbose(): Boolean = verbose

    fun snapshot(limit: Int = MAX_LINES): String = synchronized(lock) {
        lines.takeLast(limit.coerceAtLeast(1)).joinToString("\n")
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            lastStoredKey = ""
            lastStoredAt = 0L
        }
        DiagnosticMetrics.reset()
    }

    private fun isImportant(tag: String, message: String): Boolean = when (tag) {
        "SERVICE", "SESSION", "WX-IME-WRITE", "WX-LOCK", "WX-WRITE", "WX-PASTE", "FLOW" -> true
        "WX-TRANSFORM" -> message.contains("replacement=true")
        "WX-IME" -> message.contains("ready=false") ||
            (message.contains("err=") && !message.endsWith("err="))
        else -> false
    }
}
