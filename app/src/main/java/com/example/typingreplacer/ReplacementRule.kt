package com.example.typingreplacer

import org.json.JSONObject

/**
 * 一条替换规则：把 [source] 替换为 [replacement]。
 */
data class ReplacementRule(
    val source: String,
    val replacement: String,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source)
        put("replacement", replacement)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(json: JSONObject): ReplacementRule = ReplacementRule(
            source = json.optString("source", ""),
            replacement = json.optString("replacement", ""),
            enabled = json.optBoolean("enabled", true),
        )
    }
}
