package com.example.typingreplacer

import org.junit.Assert.assertEquals
import org.junit.Test

class TextReplacerTest {

    @Test
    fun longestRuleWins() {
        val rules = listOf(
            ReplacementRule("你", "A"),
            ReplacementRule("你好", "B"),
        )

        assertEquals("B", TextReplacer.replace("你好", rules))
    }

    @Test
    fun replacementOutputDoesNotCascade() {
        val rules = listOf(
            ReplacementRule("A", "B"),
            ReplacementRule("B", "C"),
        )

        assertEquals("B", TextReplacer.replace("A", rules))
    }

    @Test
    fun replacementMayContainSourceWithoutLoopingInOnePass() {
        val rules = listOf(
            ReplacementRule("我", "我们"),
        )

        assertEquals("我们", TextReplacer.replace("我", rules))
    }

    @Test
    fun disabledRulesAreIgnored() {
        val rules = listOf(
            ReplacementRule("我", "本喵", enabled = false),
        )

        assertEquals("我", TextReplacer.replace("我", rules))
    }
}

class IncrementalTransformerTest {

    @Test
    fun multiCharacterRuleCanCrossTypingBoundary() {
        val rules = listOf(
            ReplacementRule("你好", "嗨"),
        )

        val target = IncrementalTransformer.transform(
            current = "你好",
            previousOutput = "你",
            change = IncrementalTransformer.Change(
                beforeText = "你",
                fromIndex = 1,
                addedCount = 1,
                removedCount = 0,
            ),
            rules = rules,
        )

        assertEquals("嗨", target)
    }

    @Test
    fun appendDoesNotReprocessPreviousReplacementOutput() {
        val rules = listOf(
            ReplacementRule("我", "我们"),
        )

        val target = IncrementalTransformer.transform(
            current = "我们好",
            previousOutput = "我们",
            change = IncrementalTransformer.Change(
                beforeText = "我们",
                fromIndex = 2,
                addedCount = 1,
                removedCount = 0,
            ),
            rules = rules,
        )

        assertEquals("我们好", target)
    }

    @Test
    fun deletionDoesNotReRunReplacement() {
        val rules = listOf(
            ReplacementRule("我", "我们"),
        )

        val target = IncrementalTransformer.transform(
            current = "我",
            previousOutput = "我们",
            change = IncrementalTransformer.Change(
                beforeText = "我们",
                fromIndex = 1,
                addedCount = 0,
                removedCount = 1,
            ),
            rules = rules,
        )

        assertEquals("我", target)
    }
}
