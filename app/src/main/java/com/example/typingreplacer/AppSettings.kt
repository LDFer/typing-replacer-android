package com.example.typingreplacer

import android.content.Context

/**
 * 保存处理模式、锁定替换等全局设置。
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: String
        get() = prefs.getString(KEY_MODE, MODE_REALTIME) ?: MODE_REALTIME
        set(value) {
            prefs.edit().putString(KEY_MODE, value).apply()
        }

    var lockReplacement: Boolean
        get() = prefs.getBoolean(KEY_LOCK_REPLACEMENT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LOCK_REPLACEMENT, value).apply()
        }

    companion object {
        const val PREFS_NAME = "typing_replacer_prefs"
        const val KEY_MODE = "processing_mode"
        const val KEY_LOCK_REPLACEMENT = "lock_replacement"

        const val MODE_REALTIME = "realtime"
        const val MODE_SEND = "send"
    }
}
