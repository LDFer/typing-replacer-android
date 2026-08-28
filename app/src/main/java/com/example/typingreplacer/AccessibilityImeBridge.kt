package com.example.typingreplacer

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi

/** Android 13+ generic editor bridge for accessibility services. */
@TargetApi(33)
object AccessibilityImeBridge {
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
        val surroundingMatchesExpected: Boolean,
        val selectionStart: Int,
        val selectionEnd: Int,
        val error: String = "",
    )

    fun snapshot(
        service: AccessibilityService,
        expectedPackage: String? = null,
    ): Snapshot {
        return try {
            val inputMethod = service.inputMethod ?: return Snapshot(
                ready = false,
                editorPackage = "",
                inputStarted = false,
                hasConnection = false,
                text = null,
                offset = -1,
                selectionStart = -1,
                selectionEnd = -1,
                error = "input-method-null",
            )

            val editorInfo = inputMethod.currentInputEditorInfo
            val editorPackage = editorInfo?.packageName.orEmpty()
            val started = inputMethod.currentInputStarted
            val connection = inputMethod.currentInputConnection

            if (
                (expectedPackage != null && expectedPackage.isNotBlank() && editorPackage != expectedPackage) ||
                !started ||
                connection == null
            ) {
                return Snapshot(
                    ready = false,
                    editorPackage = editorPackage,
                    inputStarted = started,
                    hasConnection = connection != null,
                    text = null,
                    offset = -1,
                    selectionStart = -1,
                    selectionEnd = -1,
                    error = if (editorPackage != expectedPackage && !expectedPackage.isNullOrBlank()) {
                        "editor-package-mismatch"
                    } else {
                        "editor-not-ready"
                    },
                )
            }

            val surrounding = connection.getSurroundingText(MAX_SURROUNDING, MAX_SURROUNDING, 0)
                ?: return Snapshot(
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
        expectedPackage: String,
        expectedCurrent: String,
        target: String,
        probeSurrounding: Boolean = true,
    ): Result {
        return try {
            val inputMethod = service.inputMethod ?: return Result(
                issued = false,
                editorPackage = "",
                inputStarted = false,
                hasConnection = false,
                surroundingLength = -1,
                surroundingMatchesExpected = false,
                selectionStart = -1,
                selectionEnd = -1,
                error = "input-method-null",
            )

            val editorInfo = inputMethod.currentInputEditorInfo
            val editorPackage = editorInfo?.packageName.orEmpty()
            val started = inputMethod.currentInputStarted
            val connection = inputMethod.currentInputConnection

            if (editorPackage != expectedPackage || !started || connection == null) {
                return Result(
                    issued = false,
                    editorPackage = editorPackage,
                    inputStarted = started,
                    hasConnection = connection != null,
                    surroundingLength = -1,
                    surroundingMatchesExpected = false,
                    selectionStart = -1,
                    selectionEnd = -1,
                    error = if (editorPackage != expectedPackage) {
                        "editor-package-mismatch"
                    } else {
                        "editor-not-ready"
                    },
                )
            }

            val surrounding = if (probeSurrounding) {
                try {
                    connection.getSurroundingText(MAX_SURROUNDING, MAX_SURROUNDING, 0)
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }

            val surroundingText = surrounding?.text?.toString()
            val selectionStart = surrounding?.let { it.offset + it.selectionStart } ?: -1
            val selectionEnd = surrounding?.let { it.offset + it.selectionEnd } ?: -1

            // Event/node paths pass complete editor text. Selection + commitText works
            // even on devices where getSurroundingText() returns null.
            connection.setSelection(0, expectedCurrent.length)
            connection.commitText(target, 1, null)

            Result(
                issued = true,
                editorPackage = editorPackage,
                inputStarted = started,
                hasConnection = true,
                surroundingLength = surroundingText?.length ?: -1,
                surroundingMatchesExpected = surroundingText == expectedCurrent,
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
                surroundingMatchesExpected = false,
                selectionStart = -1,
                selectionEnd = -1,
                error = t.javaClass.simpleName + ":" + (t.message ?: ""),
            )
        }
    }

    private const val MAX_SURROUNDING = 8192
}
