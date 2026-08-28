package com.example.typingreplacer

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi

/**
 * Android 13+ fallback for editors that emit text events but expose unusable
 * AccessibilityNodeInfo objects. It uses the accessibility IME InputConnection,
 * which is independent from the view accessibility tree.
 */
@TargetApi(33)
object WeChatImeBridge {
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
        val surroundingMatchesEvent: Boolean,
        val selectionStart: Int,
        val selectionEnd: Int,
        val error: String = "",
    )

    fun snapshot(service: AccessibilityService): Snapshot {
        return try {
            val inputMethod = service.inputMethod
            val editorInfo = inputMethod.currentInputEditorInfo
            val editorPackage = editorInfo?.packageName.orEmpty()
            val started = inputMethod.currentInputStarted
            val connection = inputMethod.currentInputConnection

            if (editorPackage != WECHAT_PACKAGE || !started || connection == null) {
                return Snapshot(
                    ready = false,
                    editorPackage = editorPackage,
                    inputStarted = started,
                    hasConnection = connection != null,
                    text = null,
                    offset = -1,
                    selectionStart = -1,
                    selectionEnd = -1,
                    error = "editor-not-ready",
                )
            }

            val surrounding = connection.getSurroundingText(MAX_SURROUNDING, MAX_SURROUNDING, 0)
            if (surrounding == null) {
                return Snapshot(
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
            }

            Snapshot(
                ready = true,
                editorPackage = editorPackage,
                inputStarted = started,
                hasConnection = true,
                text = surrounding.text?.toString(),
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
        expectedCurrent: String,
        target: String,
    ): Result {
        return try {
            val inputMethod = service.inputMethod
            val editorInfo = inputMethod.currentInputEditorInfo
            val editorPackage = editorInfo?.packageName.orEmpty()
            val started = inputMethod.currentInputStarted
            val connection = inputMethod.currentInputConnection

            if (editorPackage != WECHAT_PACKAGE || !started || connection == null) {
                return Result(
                    issued = false,
                    editorPackage = editorPackage,
                    inputStarted = started,
                    hasConnection = connection != null,
                    surroundingLength = -1,
                    surroundingMatchesEvent = false,
                    selectionStart = -1,
                    selectionEnd = -1,
                    error = "editor-not-ready",
                )
            }

            val surrounding = try {
                connection.getSurroundingText(MAX_SURROUNDING, MAX_SURROUNDING, 0)
            } catch (_: Throwable) {
                null
            }

            val surroundingText = surrounding?.text?.toString()
            val selectionStart = surrounding?.let { it.offset + it.selectionStart } ?: -1
            val selectionEnd = surrounding?.let { it.offset + it.selectionEnd } ?: -1

            // TYPE_VIEW_TEXT_CHANGED#getText() is the new editor text. Selecting
            // [0, expectedCurrent.length] lets commitText replace the whole field
            // even when the AccessibilityNodeInfo for WeChat is an empty shell.
            connection.setSelection(0, expectedCurrent.length)
            connection.commitText(target, 1, null)

            Result(
                issued = true,
                editorPackage = editorPackage,
                inputStarted = started,
                hasConnection = true,
                surroundingLength = surroundingText?.length ?: -1,
                surroundingMatchesEvent = surroundingText == expectedCurrent,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
            )
        } catch (t: Throwable) {
            Result(
                issued = false,
                editorPackage = "",
                inputStarted = false,
                hasConnection = false,
                surroundingLength = -1,
                surroundingMatchesEvent = false,
                selectionStart = -1,
                selectionEnd = -1,
                error = t.javaClass.simpleName + ":" + (t.message ?: ""),
            )
        }
    }

    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val MAX_SURROUNDING = 8192
}
