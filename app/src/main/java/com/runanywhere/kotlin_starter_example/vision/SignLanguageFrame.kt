// ─── TASK-005: Core data class representing a single camera frame ─────────────
// Flows from CameraManager → SignLanguageService → SignLanguageDetector.
// jpegBytes:  the YUV→JPEG converted frame ready for VLM/TFLite input.
// width/height: pixel dimensions after conversion (target 640×480).
// rotation:   imageProxy.imageInfo.rotationDegrees — used to upright the image
//             before sending to the API so the VLM sees a correctly oriented hand.
// ─────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example.vision

data class SignLanguageFrame(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val rotation: Int   // degrees: 0, 90, 180, or 270
) {
    // ByteArray requires manual equals/hashCode — default identity check is wrong
    // for a data class used in flows and tests.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignLanguageFrame) return false
        return width    == other.width  &&
               height   == other.height &&
               rotation == other.rotation &&
               jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int {
        var result = jpegBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + rotation
        return result
    }
}
