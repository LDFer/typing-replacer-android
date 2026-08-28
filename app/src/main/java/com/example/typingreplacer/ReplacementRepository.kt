package com.example.typingreplacer

import android.content.Context
import org.json.JSONArray

class ReplacementRepository(context: Context) {
    private val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRules(): List<ReplacementRule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return defaultRules()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val rule = ReplacementRule.fromJson(item)
                    if (rule.source.isNotEmpty()) add(rule)
                }
            }
        } catch (_: Exception) {
            defaultRules()
        }
    }

    fun saveRules(rules: List<ReplacementRule>) {
        val array = JSONArray()
        rules.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
    }

    private fun defaultRules(): List<ReplacementRule> = listOf(
        ReplacementRule("我", "本喵"),
    )

    companion object {
        const val KEY_RULES = "rules"
    }
}
