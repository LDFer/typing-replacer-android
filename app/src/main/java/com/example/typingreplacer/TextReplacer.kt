package com.example.typingreplacer

/**
 * 纯文本替换引擎。按规则顺序执行普通字符串替换。
 */
object TextReplacer {
    fun replace(input: String, rules: List<ReplacementRule>): String {
        var result = input
        val ordered = rules
            .filter { it.enabled && it.source.isNotEmpty() }
            .sortedByDescending { it.source.length }
        for (rule in ordered) {
            result = result.replace(rule.source, rule.replacement)
        }
        return result
    }
}
