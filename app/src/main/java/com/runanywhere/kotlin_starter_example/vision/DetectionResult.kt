// ─── TASK-005: Core data class representing a single ISL detection result ─────
// Flows from SignLanguageDetector → SignLanguageService → SignLanguageViewModel.
// letter:     single uppercase A–Z character identified by the VLM/classifier.
// confidence: 0.0–1.0 score returned by the model.
//             Results below MIN_CONFIDENCE (0.40) are discarded inside
//             SignLanguageDetector and never reach this class.
//             The ViewModel applies a second gate at DEBOUNCE_MIN_CONFIDENCE (0.62)
//             before committing a letter to the current word.
// ─────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example.vision

data class DetectionResult(
    val letter: String,     // single uppercase letter, e.g. "B"
    val confidence: Float   // 0.0 → 1.0, already above MIN_CONFIDENCE threshold
) {
    // Convenience — human-readable log string used throughout pipeline
    override fun toString(): String =
        "DetectionResult(letter=$letter, confidence=${"%.2f".format(confidence)})"
}
