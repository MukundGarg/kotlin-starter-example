// ─── TASK-012: IslHandClassifier — MediaPipe HandLandmarker + ISL classifier ──
// Changes:
//   • Added check for MODEL_ASSET file existence to provide better diagnostics.
//   • Improved classify() to handle missing landmarker gracefully.
//   • Added log markers for frame processing status.
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker.HandLandmarkerOptions
import com.runanywhere.kotlin_starter_example.vision.DetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

class IslHandClassifier(private val context: Context) {

    companion object {
        private const val TAG              = "IslHandClassifier"
        private const val MODEL_ASSET      = "hand_landmarker.task"
        private const val NUM_LANDMARKS    = 21
        private const val FEATURE_SIZE     = NUM_LANDMARKS * 3
        private const val MIN_CONFIDENCE   = 0.40f
        private const val MAX_HANDS        = 2
    }

    private var handLandmarker: HandLandmarker? = null
    private val islLetters = ('A'..'Z').map { it.toString() }

    suspend fun initialise(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Diagnostic check: verify asset exists
            val assetExists = context.assets.list("")?.contains(MODEL_ASSET) == true
            if (!assetExists) {
                Log.e(TAG, "FATAL: $MODEL_ASSET not found in assets. Gesture detection will NOT work.")
                return@withContext false
            }

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()

            val options = HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(MAX_HANDS)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.i(TAG, "HandLandmarker initialised successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise HandLandmarker: ${e.message}", e)
            false
        }
    }

    suspend fun classify(jpegBytes: ByteArray): DetectionResult? =
        withContext(Dispatchers.Default) {
            try {
                val landmarker = handLandmarker ?: return@withContext null

                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    ?: return@withContext null

                val mpImage = BitmapImageBuilder(bitmap).build()
                val result  = landmarker.detect(mpImage)

                if (result.landmarks().isEmpty()) {
                    return@withContext null
                }

                val landmarks = result.landmarks()[0]
                val features  = extractFeatures(landmarks.map { Triple(it.x(), it.y(), it.z()) })

                val (letter, confidence) = classifyFeatures(features)
                    ?: return@withContext null

                if (confidence < MIN_CONFIDENCE) return@withContext null

                DetectionResult(letter = letter, confidence = confidence)
            } catch (e: Exception) {
                Log.e(TAG, "Classification error: ${e.message}")
                null
            }
        }

    suspend fun runInference(dummyBytes: ByteArray) {
        try {
            val landmarker = handLandmarker ?: return
            val blankBitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            val mpImage     = BitmapImageBuilder(blankBitmap).build()
            landmarker.detect(mpImage)
            Log.d(TAG, "Warmup inference complete")
        } catch (e: Exception) {
            Log.w(TAG, "Warmup inference failed: ${e.message}")
        }
    }

    private fun extractFeatures(landmarks: List<Triple<Float, Float, Float>>): FloatArray {
        val (wx, wy, wz) = landmarks[0]
        val (mx, my, mz) = landmarks[9]
        val scale = sqrt((mx - wx) * (mx - wx) + (my - wy) * (my - wy) + (mz - wz) * (mz - wz)).takeIf { it > 0f } ?: 1f

        val features = FloatArray(FEATURE_SIZE)
        landmarks.forEachIndexed { idx, (x, y, z) ->
            features[idx * 3 + 0] = (x - wx) / scale
            features[idx * 3 + 1] = (y - wy) / scale
            features[idx * 3 + 2] = (z - wz) / scale
        }
        return features
    }

    private fun classifyFeatures(features: FloatArray): Pair<String, Float>? {
        // Mock classifier for now - replace with your real logic or MLP
        return null
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
    }
}
