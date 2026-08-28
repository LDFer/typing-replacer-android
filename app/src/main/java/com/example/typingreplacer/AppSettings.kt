package com.example.typingreplacer

import android.content.Context

/**
 * V2 intentionally keeps only one compatibility option.
 *
 * The accessibility service is always real-time. The former "send only" and
 * "lock replacement" modes were removed because they were stateful and
 * unreliable across apps.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var compatibilityScanEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMPATIBILITY_SCAN, true)
        set(value) {
            prefs.edit().putBoolean(KEY_COMPATIBILITY_SCAN, value).apply()
        }

    companion object {
        const val PREFS_NAME = "typing_replacer_prefs"
        const val KEY_COMPATIBILITY_SCAN = "compatibility_scan"
    }
}
