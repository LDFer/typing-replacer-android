package com.example.typingreplacer

/**
 * Pure transformation logic for an editable input session.
 *
 * [previousOutput] is the exact text the service last observed/wrote for the
 * current input node. [change] mirrors the useful fields from
 * TYPE_VIEW_TEXT_CHANGED without depending on Android classes.
 */
object IncrementalTransformer {

    data class Change(
        val beforeText: String?,
        val fromIndex: Int,
        val addedCount: Int,
        val removedCount: Int,
    )

    fun transform(
        current: String,
        previousOutput: String,
        change: Change?,
        rules: List<ReplacementRule>,
    ): String {
        if (current.isEmpty()) return current

        val maxSourceLength = TextReplacer.maxSourceLength(rules)

        if (change != null) {
            val before = change.beforeText
            val start = change.fromIndex
            val added = change.addedCount

            if (
                before != null &&
                before == previousOutput &&
                start >= 0 &&
                added > 0 &&
                start <= current.length
            ) {
                val addedEnd = (start + added).coerceAtMost(current.length)
                val lookBehind = (maxSourceLength - 1).coerceAtLeast(0)
                val segmentStart = (start - lookBehind).coerceAtLeast(0)

                if (addedEnd > segmentStart) {
                    val segment = current.substring(segmentStart, addedEnd)
                    val replacedSegment = TextReplacer.replace(segment, rules)
                    return current.replaceRange(
                        segmentStart,
                        addedEnd,
                        replacedSegment,
                    )
                }
            }

            // A pure deletion is a user edit, not a reason to re-run the
            // replacer over all previously transformed output.
            if (
                before != null &&
                before == previousOutput &&
                change.removedCount > 0 &&
                change.addedCount == 0
            ) {
                return current
            }
        }

        if (previousOutput.isNotEmpty()) {
            // Normal append path. Protect the already-transformed prefix while
            // retaining a small look-behind so multi-character rules can cross
            // the old/new boundary.
            if (current.startsWith(previousOutput)) {
                val suffix = current.substring(previousOutput.length)
                if (suffix.isEmpty()) return current

                val lookBehind =
                    (maxSourceLength - 1)
                        .coerceAtLeast(0)
                        .coerceAtMost(previousOutput.length)

                val stablePrefixLength = previousOutput.length - lookBehind
                val stablePrefix = current.substring(0, stablePrefixLength)
                val tail = current.substring(stablePrefixLength)

                return stablePrefix + TextReplacer.replace(tail, rules)
            }

            // Normal deletion path.
            if (previousOutput.startsWith(current)) {
                return current
            }
        }

        // New field, paste, or substantial edit.
        return TextReplacer.replace(current, rules)
    }
}
