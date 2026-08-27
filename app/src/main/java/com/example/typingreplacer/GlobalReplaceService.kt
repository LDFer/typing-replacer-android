package com.example.typingreplacer

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务。
 *
 * - realtime 模式：输入变化时替换；可开启“锁定替换”，让替换后的文字无法被删除/修改。
 * - send 模式：平时不动输入框，只在点击发送/提交/发布类按钮时替换。
 *
 * 除了监听文本变化事件，还会定时轮询当前输入框，兼容部分手机不发送文本变化事件的情况。
 */
class GlobalReplaceService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var suppressEvents = false
    private var lastSet = ""
    private var lastTextEventTime = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (AppSettings(this@GlobalReplaceService).mode == AppSettings.MODE_REALTIME) {
                pollCurrentInput()
            }
            handler.postDelayed(this, POLL_INTERVAL)
        }
    }

    private companion object {
        const val TAG = "TypingReplacer"
        const val POLL_INTERVAL = 200L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        Log.d(TAG, "event type=${event.eventType} pkg=${event.packageName}")
        if (event.packageName?.toString() == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastSet = ""
            return
        }

        val mode = AppSettings(this).mode
        if (mode == AppSettings.MODE_SEND) {
            handleSendMode(event)
        } else {
            handleRealtimeMode(event)
        }
    }

    override fun onInterrupt() {
        // 无障碍服务被系统中断时没有额外清理工作。
        lastSet = ""
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    private fun handleSendMode(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val clicked = event.source ?: return
        if (!isLikelySendButton(clicked)) return

        val root = rootInActiveWindow ?: return
        val input = findEditable(root)
        root.recycle()
        if (input == null) return

        try {
            val original = input.text?.toString() ?: return
            val rules = ReplacementRepository(this).loadRules()
            val replaced = TextReplacer.replace(original, rules)
            if (replaced != original) {
                setNodeText(input, replaced)
            }
        } finally {
            input.recycle()
        }
    }

    private fun handleRealtimeMode(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        lastTextEventTime = System.currentTimeMillis()
        if (suppressEvents) return

        val node = event.source ?: return
        if (!node.isEditable || node.isPassword) return

        val original = node.text?.toString() ?: return
        Log.d(TAG, "textEvent pkg=${event.packageName} editable=${node.isEditable} text=$original")
        processRealtimeText(node, original)
    }

    private fun pollCurrentInput() {
        if (suppressEvents) return
        // 如果最近有文本变化事件，说明事件驱动已经能工作，轮询只会添乱。
        if (System.currentTimeMillis() - lastTextEventTime < 500) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        if (pkg == packageName) {
            root.recycle()
            return
        }
        val input = findEditable(root)
        root.recycle()
        if (input == null) return

        try {
            val original = input.text?.toString() ?: return
            processRealtimeText(input, original)
        } finally {
            input.recycle()
        }
    }

    private fun processRealtimeText(node: AccessibilityNodeInfo, original: String) {
        if (isPlaceholder(node, original)) {
            lastSet = ""
            return
        }

        val settings = AppSettings(this)
        if (settings.lockReplacement && lastSet.isNotEmpty() && original.isEmpty()) {
            // 全部删除时，强制恢复上次替换后的内容。
            restoreLocked(node)
            return
        }

        if (original.isEmpty()) {
            lastSet = ""
            return
        }

        val rules = ReplacementRepository(this).loadRules()
        val replaced = TextReplacer.replace(original, rules)
        Log.d(TAG, "processed original=$original replaced=$replaced lock=${settings.lockReplacement} lastSet=$lastSet")

        if (settings.lockReplacement && lastSet.isNotEmpty()) {
            if (original == lastSet) {
                return
            }
            val isAppend = original.startsWith(lastSet)
            val isDelete = lastSet.startsWith(original)
            if (isDelete) {
                // 删除了部分内容，锁回去。
                restoreLocked(node)
                return
            }
            if (isAppend) {
                // 允许在替换结果后面继续输入，并重新锁定新的完整内容。
                writeLocked(node, replaced)
                return
            }
            // 完全不同的文本：当作新输入处理，避免旧锁定状态卡住新消息。
            lastSet = ""
        }

        if (replaced == original) {
            lastSet = original
            return
        }

        writeLocked(node, replaced)
    }

    private fun isPlaceholder(node: AccessibilityNodeInfo, text: String): Boolean {
        val hint = node.hintText?.toString().orEmpty()
        val trimmed = text.trim()
        return text == hint
            || trimmed == "发送消息"
            || trimmed == "输入消息"
            || trimmed == "说点什么"
            || trimmed == "你说点什么..."
            || trimmed.isEmpty()
    }

    private fun writeLocked(node: AccessibilityNodeInfo, text: String) {
        suppressEvents = true
        try {
            lastSet = text
            setNodeText(node, text)
        } finally {
            handler.postDelayed({ suppressEvents = false }, 150)
        }
    }

    private fun restoreLocked(node: AccessibilityNodeInfo) {
        if (lastSet.isEmpty()) return
        suppressEvents = true
        try {
            setNodeText(node, lastSet)
        } finally {
            handler.postDelayed({ suppressEvents = false }, 150)
        }
    }

    private fun isLikelySendButton(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        val text = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty()
            .trim()
            .lowercase()
        return id.contains("send")
            || id.contains("btn_submit")
            || text.contains("发送")
            || text.contains("发布")
            || text.contains("提交")
            || text.contains("回复")
            || text == "send"
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            child.recycle()
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
