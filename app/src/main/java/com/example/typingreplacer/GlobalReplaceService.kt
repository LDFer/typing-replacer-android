package com.example.typingreplacer

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务：监听任意输入框的文本变化，命中替换规则后强制写回替换结果。
 */
class GlobalReplaceService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var suppressEvents = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || suppressEvents) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (event.packageName?.toString() == packageName) return

        val node = event.source ?: return
        if (!node.isEditable || node.isPassword) return

        val original = node.text?.toString() ?: return
        if (original.isEmpty()) return

        val rules = ReplacementRepository(this).loadRules()
        val replaced = TextReplacer.replace(original, rules)
        if (replaced == original) return

        suppressEvents = true
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                replaced,
            )
        }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } finally {
            handler.postDelayed({ suppressEvents = false }, 150)
        }
    }

    override fun onInterrupt() {
        // 无障碍服务被系统中断时没有额外清理工作。
    }
}
