package com.example.typingreplacer

/**
 * One-pass longest-match replacer.
 *
 * Replacement output is never fed back into another rule during the same pass.
 * This avoids cascades such as:
 *   A -> B
 *   B -> C
 * turning A directly into C.
 */
object TextReplacer {
    fun replace(input: String, rules: List<ReplacementRule>): String {
        if (input.isEmpty()) return input

        val active = rules
            .asSequence()
            .filter { it.enabled && it.source.isNotEmpty() }
            .sortedByDescending { it.source.length }
            .toList()

        if (active.isEmpty()) return input

        val out = StringBuilder(input.length)
        var index = 0

        while (index < input.length) {
            val match = active.firstOrNull { rule ->
                index + rule.source.length <= input.length &&
                    input.regionMatches(
                        thisOffset = index,
                        other = rule.source,
                        otherOffset = 0,
                        length = rule.source.length,
                        ignoreCase = false,
                    )
            }

            if (match == null) {
                out.append(input[index])
                index += 1
            } else {
                out.append(match.replacement)
                index += match.source.length
            }
        }

        return out.toString()
    }

    fun maxSourceLength(rules: List<ReplacementRule>): Int =
        rules.asSequence()
            .filter { it.enabled && it.source.isNotEmpty() }
            .maxOfOrNull { it.source.length }
            ?: 1
}
