package com.example.typingreplacer

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务。
 *
 * - send 模式：只在点击“发送/提交/发布”等按钮时替换输入框，平时不动输入框。
 * - realtime 模式：每次输入变化都立即替换。
 */
class GlobalReplaceService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var suppressEvents = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return

        val mode = AppSettings(this).mode
        if (mode == AppSettings.MODE_SEND) {
            handleSendMode(event)
        } else {
            handleRealtimeMode(event)
        }
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
        if (suppressEvents) return

        val node = event.source ?: return
        if (!node.isEditable || node.isPassword) return

        val original = node.text?.toString() ?: return
        if (original.isEmpty()) return

        val rules = ReplacementRepository(this).loadRules()
        val replaced = TextReplacer.replace(original, rules)
        if (replaced == original) return

        suppressEvents = true
        try {
            setNodeText(node, replaced)
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

    override fun onInterrupt() {
        // 无障碍服务被系统中断时没有额外清理工作。
    }
}
