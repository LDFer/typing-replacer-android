package com.example.typingreplacer

import android.content.Context
import org.json.JSONArray

/**
 * 用 SharedPreferences 保存替换规则，格式为 JSON 数组。
 */
class ReplacementRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRules(): MutableList<ReplacementRule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return defaultRules()
        return try {
            val array = JSONArray(raw)
            val rules = mutableListOf<ReplacementRule>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val rule = ReplacementRule.fromJson(item)
                if (rule.source.isNotEmpty()) {
                    rules.add(rule)
                }
            }
            rules
        } catch (_: Exception) {
            defaultRules()
        }
    }

    fun saveRules(rules: List<ReplacementRule>) {
        val array = JSONArray()
        rules.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
    }

    private fun defaultRules(): MutableList<ReplacementRule> = mutableListOf(
        ReplacementRule("我", "本喵"),
    )

    private companion object {
        const val PREFS_NAME = "typing_replacer_prefs"
        const val KEY_RULES = "rules"
    }
}
