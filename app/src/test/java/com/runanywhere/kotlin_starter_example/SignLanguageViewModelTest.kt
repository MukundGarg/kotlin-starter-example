// ─── TASK-018: Unit tests for ViewModel debounce logic ───────────────────────
// Tests all four required cases:
//   1. 2 identical letters → no commit (below DEBOUNCE_WINDOW=3)
//   2. 3 identical letters → letter committed to currentWord
//   3. A A B sequence → debounce resets, no commit
//   4. A A A → "A" committed, recentLetters cleared
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example

import com.runanywhere.kotlin_starter_example.vision.DetectionResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DebounceLogicTest {

    // ── We test the debounce logic in isolation ──────────────────────────────
    // Mirrors the ViewModel constants exactly
    private val DEBOUNCE_WINDOW         = 3
    private val DEBOUNCE_MIN_CONFIDENCE = 0.62f

    // Local state mirrors what the ViewModel tracks
    private val recentLetters = ArrayDeque<String>(DEBOUNCE_WINDOW)
    private var currentWord   = ""
    private var currentLetter = ""

    @Before
    fun setUp() {
        recentLetters.clear()
        currentWord   = ""
        currentLetter = ""
    }

    // ── Mirrors ViewModel.onNewResult() exactly ───────────────────────────────
    private fun processResult(result: DetectionResult) {
        currentLetter = result.letter

        if (result.confidence >= DEBOUNCE_MIN_CONFIDENCE) {
            if (recentLetters.size >= DEBOUNCE_WINDOW) recentLetters.removeFirst()
            recentLetters.addLast(result.letter)
        } else {
            recentLetters.clear()
        }

        if (recentLetters.size == DEBOUNCE_WINDOW &&
            recentLetters.distinct().size == 1
        ) {
            currentWord += result.letter
            recentLetters.clear()
        }
    }

    private fun result(letter: String, confidence: Float = 0.85f) =
        DetectionResult(letter, confidence)

    // ── Test 1: 2 identical letters → NO commit ───────────────────────────────
    @Test
    fun `two identical letters do not commit`() {
        processResult(result("A"))
        processResult(result("A"))

        assertEquals("currentWord should be empty after 2 frames", "", currentWord)
        assertEquals("recentLetters should have 2 entries", 2, recentLetters.size)
    }

    // ── Test 2: 3 identical letters → commit ──────────────────────────────────
    @Test
    fun `three identical letters commit the letter`() {
        processResult(result("A"))
        processResult(result("A"))
        processResult(result("A"))

        assertEquals("currentWord should contain A after 3 identical frames", "A", currentWord)
        assertEquals("recentLetters should be cleared after commit", 0, recentLetters.size)
    }

    // ── Test 3: A A B → no commit, buffer contains A A B ─────────────────────
    @Test
    fun `A A B sequence does not commit`() {
        processResult(result("A"))
        processResult(result("A"))
        processResult(result("B"))

        assertEquals("No commit for A A B sequence", "", currentWord)
        assertEquals(3, recentLetters.size)
        assertFalse("Not all letters are identical",
            recentLetters.distinct().size == 1)
    }

    // ── Test 4: A A A → A committed, buffer cleared ──────────────────────────
    @Test
    fun `A A A commits A and clears buffer`() {
        processResult(result("A"))
        processResult(result("A"))
        processResult(result("A"))

        assertEquals("A", currentWord)
        assertTrue("Buffer should be empty after commit", recentLetters.isEmpty())
    }

    // ── Test 5: Low confidence frame resets the buffer ────────────────────────
    @Test
    fun `low confidence frame resets the debounce buffer`() {
        processResult(result("A", confidence = 0.85f))
        processResult(result("A", confidence = 0.25f))  // below 0.62 threshold
        processResult(result("A", confidence = 0.88f))

        assertEquals("Buffer should have 1 entry after reset", 1, recentLetters.size)
        assertEquals("No commit should have happened", "", currentWord)
    }

    // ── Test 6: Two letters committed in sequence ──────────────────────────────
    @Test
    fun `two letters committed in sequence build the word`() {
        repeat(3) { processResult(result("A")) }
        assertEquals("A", currentWord)

        repeat(3) { processResult(result("B")) }
        assertEquals("AB", currentWord)
    }

    // ── Test 7: Confidence exactly at threshold is accepted ───────────────────
    @Test
    fun `confidence exactly at DEBOUNCE_MIN_CONFIDENCE is accepted`() {
        repeat(3) { processResult(result("C", confidence = 0.62f)) }

        assertEquals("C", currentWord)
    }

    // ── Test 8: currentLetter updates immediately on first frame ──────────────
    @Test
    fun `currentLetter updates immediately even before commit`() {
        processResult(result("Z"))

        assertEquals("Z", currentLetter)
        assertEquals("No commit yet", "", currentWord)
    }
}
