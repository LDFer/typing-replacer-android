package com.example.typingreplacer

import android.content.Context

/**
 * Runtime options for the V2 accessibility engine.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var compatibilityScanEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMPATIBILITY_SCAN, true)
        set(value) {
            prefs.edit().putBoolean(KEY_COMPATIBILITY_SCAN, value).apply()
        }

    /**
     * When enabled, once a real replacement has happened, the resulting text is
     * treated as locked. User deletions/edits are restored automatically until
     * the message is sent or the input session changes.
     */
    var lockReplacementEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_REPLACEMENT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOCK_REPLACEMENT, value).apply()
        }

    companion object {
        const val PREFS_NAME = "typing_replacer_prefs"
        const val KEY_COMPATIBILITY_SCAN = "compatibility_scan"
        const val KEY_LOCK_REPLACEMENT = "lock_replacement_v2"
    }
}
