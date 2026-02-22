// ─── TASK-007: SignLanguageDetector — VLM inference + ISL response parsing ────
// Changes:
//   • Sends JPEG bytes to RunAnywhere.processImageStream() with ISL-specific prompt
//   • Parses structured LETTER: / CONFIDENCE: response with regex
//   • Returns null for NONE, parse failures, or confidence < MIN_CONFIDENCE (0.40)
//   • Logs every raw VLM response at VERBOSE for easy debugging
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example.vision

import android.content.Context
import android.util.Base64
import android.util.Log
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.VLM.VLMGenerationOptions
import com.runanywhere.sdk.public.extensions.VLM.VLMImage
import com.runanywhere.sdk.public.extensions.processImageStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.withContext

class SignLanguageDetector(private val context: Context?) {

    companion object {
        private const val TAG             = "SignLanguageDetector"

        // ── Results below this threshold are discarded (return null) ──────────
        private const val MIN_CONFIDENCE  = 0.40f

        // ── ISL-specific VLM prompt ───────────────────────────────────────────
        private const val ISL_PROMPT = """
You are an expert Indian Sign Language (ISL) interpreter.
Examine the image from a front-facing camera and identify the single ISL
letter currently being signed by the person's hand.
ISL uses a one-hand (and sometimes two-hand) finger-spelling alphabet for letters A–Z.
Focus on hand shape and orientation.
Reply with EXACTLY these two lines and nothing else:
LETTER: <single uppercase letter A-Z, or NONE if no clear sign is visible>
CONFIDENCE: <decimal 0.0 to 1.0 representing how certain you are>
Do not include any other text, explanation, or punctuation."""

        private val LETTER_RE     = Regex("""LETTER:\s*([A-Z]|NONE)""",          RegexOption.IGNORE_CASE)
        private val CONFIDENCE_RE = Regex("""CONFIDENCE:\s*([01]?\.\d+|[01])""", RegexOption.IGNORE_CASE)
    }

    suspend fun detectLetterWithConfidence(jpegBytes: ByteArray): DetectionResult? {
        return try {
            val rawResponse = callVlm(jpegBytes)
            Log.v(TAG, "Raw VLM response: $rawResponse")
            parseResponse(rawResponse)
        } catch (e: Exception) {
            Log.e(TAG, "VLM call failed: ${e.message}", e)
            null
        }
    }

    private suspend fun callVlm(jpegBytes: ByteArray): String {
        return withContext(Dispatchers.IO) {
            try {
                // Convert JPEG bytes to Base64 as VLMImage supports it
                val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                val vlmImage = VLMImage.fromBase64(base64Image)
                val options = VLMGenerationOptions(maxTokens = 50)
                
                RunAnywhere.processImageStream(vlmImage, ISL_PROMPT, options)
                    .reduce { accumulator, value -> accumulator + value }
            } catch (e: Exception) {
                Log.e(TAG, "processImageStream failed", e)
                ""
            }
        }
    }

    internal fun parseResponse(raw: String): DetectionResult? {
        if (raw.isBlank()) return null

        val letterMatch = LETTER_RE.find(raw) ?: return null
        val letter = letterMatch.groupValues[1].uppercase()
        if (letter == "NONE") return null

        val confidenceMatch = CONFIDENCE_RE.find(raw) ?: return null
        val confidence = confidenceMatch.groupValues[1].toFloatOrNull() ?: return null

        if (confidence < MIN_CONFIDENCE) return null

        return DetectionResult(letter = letter, confidence = confidence)
    }
}
