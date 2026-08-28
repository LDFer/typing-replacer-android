package com.example.typingreplacer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-process ring buffer for field diagnostics.
 *
 * It intentionally avoids storing full input text. Logs keep node metadata,
 * text length and control-flow results so users can copy them from MainActivity
 * without exposing chat contents.
 */
object DiagnosticLog {
    private const val MAX_LINES = 240
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, message: String) {
        val line = "${formatter.format(Date())} [$tag] $message"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }

    fun snapshot(limit: Int = MAX_LINES): String = synchronized(lock) {
        lines.takeLast(limit.coerceAtLeast(1)).joinToString("\n")
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
        }
    }
}
