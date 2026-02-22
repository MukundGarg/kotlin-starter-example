// ─── TASK-017: Unit tests for SignLanguageDetector.parseResponse() ────────────
// Tests all four required cases:
//   1. Valid input → correct DetectionResult
//   2. NONE → null
//   3. Confidence below MIN_CONFIDENCE → null
//   4. Malformed response → null
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example

import com.runanywhere.kotlin_starter_example.vision.DetectionResult
import com.runanywhere.kotlin_starter_example.vision.SignLanguageDetector
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SignLanguageDetectorTest {

    private lateinit var detector: SignLanguageDetector

    @Before
    fun setUp() {
        // We use a dummy context for the detector as parseResponse doesn't need it
        detector = SignLanguageDetector(null as android.content.Context?)
    }

    // ── Test 1: Valid response returns correct DetectionResult ────────────────
    @Test
    fun `parseResponse with valid input returns DetectionResult`() {
        val raw    = "LETTER: B\nCONFIDENCE: 0.87"
        val result = detector.parseResponse(raw)

        assertNotNull("Expected non-null DetectionResult for valid input", result)
        assertEquals("Expected letter B", "B", result!!.letter)
        assertEquals("Expected confidence 0.87", 0.87f, result.confidence, 0.001f)
    }

    // ── Test 2: NONE letter returns null ──────────────────────────────────────
    @Test
    fun `parseResponse with NONE letter returns null`() {
        val raw    = "LETTER: NONE\nCONFIDENCE: 0.10"
        val result = detector.parseResponse(raw)

        assertNull("Expected null when LETTER is NONE", result)
    }

    // ── Test 3: Confidence below MIN_CONFIDENCE (0.40) returns null ───────────
    @Test
    fun `parseResponse with confidence below threshold returns null`() {
        val raw    = "LETTER: A\nCONFIDENCE: 0.30"
        val result = detector.parseResponse(raw)

        assertNull("Expected null when confidence 0.30 is below MIN_CONFIDENCE 0.40", result)
    }

    // ── Test 4a: Missing LETTER line returns null ─────────────────────────────
    @Test
    fun `parseResponse with missing LETTER line returns null`() {
        val raw    = "CONFIDENCE: 0.85"
        val result = detector.parseResponse(raw)

        assertNull("Expected null when LETTER line is missing", result)
    }

    // ── Test 4b: Missing CONFIDENCE line returns null ─────────────────────────
    @Test
    fun `parseResponse with missing CONFIDENCE line returns null`() {
        val raw    = "LETTER: A"
        val result = detector.parseResponse(raw)

        assertNull("Expected null when CONFIDENCE line is missing", result)
    }

    // ── Test 4c: Completely malformed response returns null ───────────────────
    @Test
    fun `parseResponse with malformed response returns null`() {
        val raw    = "Sorry I cannot identify the sign in this image."
        val result = detector.parseResponse(raw)

        assertNull("Expected null for completely malformed response", result)
    }

    // ── Test 4d: Blank response returns null ──────────────────────────────────
    @Test
    fun `parseResponse with blank response returns null`() {
        val result = detector.parseResponse("")

        assertNull("Expected null for blank response", result)
    }

    // ── Test 5: Confidence exactly at threshold (0.40) passes ─────────────────
    @Test
    fun `parseResponse with confidence exactly at MIN_CONFIDENCE passes`() {
        val raw    = "LETTER: C\nCONFIDENCE: 0.40"
        val result = detector.parseResponse(raw)

        assertNotNull("Expected non-null result when confidence is exactly 0.40", result)
        assertEquals("C", result!!.letter)
    }

    // ── Test 6: Case-insensitive parsing ──────────────────────────────────────
    @Test
    fun `parseResponse handles lowercase letter and confidence keys`() {
        val raw    = "letter: D\nconfidence: 0.75"
        val result = detector.parseResponse(raw)

        assertNotNull("Expected parseResponse to handle lowercase keys", result)
        assertEquals("D", result!!.letter)
        assertEquals(0.75f, result.confidence, 0.001f)
    }

    // ── Test 7: Confidence value of 1.0 ──────────────────────────────────────
    @Test
    fun `parseResponse handles confidence value of 1`() {
        val raw    = "LETTER: A\nCONFIDENCE: 1"
        val result = detector.parseResponse(raw)

        assertNotNull(result)
        assertEquals(1.0f, result!!.confidence, 0.001f)
    }
}
