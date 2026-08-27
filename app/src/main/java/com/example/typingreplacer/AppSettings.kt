package com.example.typingreplacer

import android.content.Context

/**
 * 保存处理模式等全局设置。
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: String
        get() = prefs.getString(KEY_MODE, MODE_SEND) ?: MODE_SEND
        set(value) {
            prefs.edit().putString(KEY_MODE, value).apply()
        }

    companion object {
        const val PREFS_NAME = "typing_replacer_prefs"
        const val KEY_MODE = "processing_mode"

        const val MODE_REALTIME = "realtime"
        const val MODE_SEND = "send"
    }
}
