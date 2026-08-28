package com.example.typingreplacer

/**
 * In-process diagnostics only. No disk writes are performed while typing.
 *
 * MainActivity and GlobalReplaceService run in the same app process, so the
 * activity can inspect whether the accessibility service is connected, still
 * executing its heartbeat, receiving events, resolving an input node and
 * successfully writing replacements.
 */
object ServiceRuntimeState {
    @Volatile private var connected = false
    @Volatile private var connectedAt = 0L
    @Volatile private var lastHeartbeatAt = 0L
    @Volatile private var lastEventAt = 0L
    @Volatile private var lastEventPackage = ""
    @Volatile private var lastNodeAt = 0L
    @Volatile private var lastNodeStatus = "尚未检查输入框"
    @Volatile private var lastReplacementAt = 0L
    @Volatile private var lastReplacementPackage = ""
    @Volatile private var lastError = ""

    fun markConnected() {
        val now = System.currentTimeMillis()
        connected = true
        connectedAt = now
        lastHeartbeatAt = now
        lastError = ""
    }

    fun markDisconnected(reason: String) {
        connected = false
        lastError = reason
    }

    fun markHeartbeat() {
        lastHeartbeatAt = System.currentTimeMillis()
    }

    fun markEvent(packageName: String?) {
        lastEventAt = System.currentTimeMillis()
        lastEventPackage = packageName.orEmpty()
    }

    fun markNode(status: String) {
        lastNodeAt = System.currentTimeMillis()
        lastNodeStatus = status
    }

    fun markReplacement(packageName: String?) {
        lastReplacementAt = System.currentTimeMillis()
        lastReplacementPackage = packageName.orEmpty()
        lastError = ""
    }

    fun markError(message: String) {
        lastError = message
    }

    fun snapshot(): Snapshot = Snapshot(
        connected = connected,
        connectedAt = connectedAt,
        lastHeartbeatAt = lastHeartbeatAt,
        lastEventAt = lastEventAt,
        lastEventPackage = lastEventPackage,
        lastNodeAt = lastNodeAt,
        lastNodeStatus = lastNodeStatus,
        lastReplacementAt = lastReplacementAt,
        lastReplacementPackage = lastReplacementPackage,
        lastError = lastError,
    )

    data class Snapshot(
        val connected: Boolean,
        val connectedAt: Long,
        val lastHeartbeatAt: Long,
        val lastEventAt: Long,
        val lastEventPackage: String,
        val lastNodeAt: Long,
        val lastNodeStatus: String,
        val lastReplacementAt: Long,
        val lastReplacementPackage: String,
        val lastError: String,
    )
}
