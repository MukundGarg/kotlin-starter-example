// ─── TASK-012: IslHandClassifier — MediaPipe HandLandmarker + ISL classifier ──
// Changes:
//   • Wraps MediaPipe HandLandmarker to extract 21 3D keypoints per frame
//   • Normalises landmarks relative to wrist (landmark 0) for translation
//     invariance — the hand can be anywhere in frame
//   • Scales by hand bounding-box size for scale invariance
//   • Feeds 63-float feature vector (21 landmarks × xyz) into a lightweight
//     ISL letter classifier (k-NN by default; swap for MLP once trained)
//   • Returns DetectionResult or null — same contract as SignLanguageDetector
//   • Fully offline — no network call at inference time
//   • close() must be called from ViewModel.onCleared() to free native memory
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
import kotlin.math.abs
import kotlin.math.sqrt

class IslHandClassifier(private val context: Context) {

    companion object {
        private const val TAG              = "IslHandClassifier"
        private const val MODEL_ASSET      = "hand_landmarker.task"
        private const val NUM_LANDMARKS    = 21
        private const val FEATURE_SIZE     = NUM_LANDMARKS * 3  // x, y, z per landmark
        private const val MIN_CONFIDENCE   = 0.40f
        private const val MAX_HANDS        = 2  // ISL uses both one- and two-hand shapes
    }

    // ── MediaPipe HandLandmarker ───────────────────────────────────────────────
    private var handLandmarker: HandLandmarker? = null

    // ── ISL letter labels — A–Z ───────────────────────────────────────────────
    private val islLetters = ('A'..'Z').map { it.toString() }

    // ─────────────────────────────────────────────────────────────────────────
    // initialise() — loads the MediaPipe model from assets
    // Call this once from ViewModel init (or warmup) before any inference.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun initialise(): Boolean = withContext(Dispatchers.IO) {
        try {
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

    // ─────────────────────────────────────────────────────────────────────────
    // classify() — full pipeline: JPEG → landmarks → features → letter
    //
    // Returns DetectionResult on success, null if:
    //   • No hand detected in frame
    //   • HandLandmarker not initialised
    //   • Confidence below MIN_CONFIDENCE
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun classify(jpegBytes: ByteArray): DetectionResult? =
        withContext(Dispatchers.Default) {
            try {
                val landmarker = handLandmarker ?: run {
                    Log.w(TAG, "HandLandmarker not initialised — call initialise() first")
                    return@withContext null
                }

                // Step 1: Decode JPEG → Bitmap
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    ?: run {
                        Log.w(TAG, "Failed to decode JPEG frame")
                        return@withContext null
                    }

                // Step 2: Run MediaPipe hand landmarker
                val mpImage = BitmapImageBuilder(bitmap).build()
                val result  = landmarker.detect(mpImage)

                if (result.landmarks().isEmpty()) {
                    Log.d(TAG, "No hand landmarks detected in frame")
                    return@withContext null
                }

                // Step 3: Extract and normalise landmark features
                // Use the first hand detected (primary signing hand)
                val landmarks = result.landmarks()[0]
                val features  = extractFeatures(landmarks.map { Triple(it.x(), it.y(), it.z()) })

                // Step 4: Classify features → ISL letter
                val (letter, confidence) = classifyFeatures(features)
                    ?: return@withContext null

                if (confidence < MIN_CONFIDENCE) {
                    Log.d(TAG, "Confidence $confidence below threshold — discarding")
                    return@withContext null
                }

                val detection = DetectionResult(letter = letter, confidence = confidence)
                Log.i(TAG, "ISL classified locally: $detection")
                detection

            } catch (e: Exception) {
                Log.e(TAG, "Classification error: ${e.message}", e)
                null
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // runInference() — warmup entry point (TASK-013)
    //
    // Accepts raw bytes of any shape; internally uses a blank bitmap.
    // Designed to JIT-compile the TFLite/MediaPipe runtime before first real use.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun runInference(dummyBytes: ByteArray) {
        try {
            val landmarker = handLandmarker ?: return
            // Create a minimal blank bitmap for warmup — 224×224 is the
            // standard input size used by most vision models
            val blankBitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            val mpImage     = BitmapImageBuilder(blankBitmap).build()
            landmarker.detect(mpImage)
            Log.d(TAG, "Warmup inference complete")
        } catch (e: Exception) {
            // Warmup failures are non-fatal — log and continue
            Log.w(TAG, "Warmup inference failed (non-fatal): ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // extractFeatures() — normalises 21 landmarks into a 63-float feature vector
    //
    // Normalisation steps:
    //   1. Translate: subtract wrist (landmark 0) so hand position is irrelevant
    //   2. Scale: divide by wrist-to-middle-MCP distance so hand size is irrelevant
    //   3. Flatten: [x0,y0,z0, x1,y1,z1, … x20,y20,z20]
    //
    // This makes the classifier invariant to where the hand appears in the frame
    // and how far away the signer is from the camera.
    // ─────────────────────────────────────────────────────────────────────────
    private fun extractFeatures(
        landmarks: List<Triple<Float, Float, Float>>
    ): FloatArray {
        require(landmarks.size == NUM_LANDMARKS) {
            "Expected $NUM_LANDMARKS landmarks, got ${landmarks.size}"
        }

        val (wx, wy, wz) = landmarks[0]   // wrist

        // Scale reference: wrist (0) → middle finger MCP (9)
        val (mx, my, mz) = landmarks[9]
        val scale = sqrt(
            (mx - wx) * (mx - wx) +
            (my - wy) * (my - wy) +
            (mz - wz) * (mz - wz)
        ).takeIf { it > 0f } ?: 1f

        val features = FloatArray(FEATURE_SIZE)
        landmarks.forEachIndexed { idx, (x, y, z) ->
            features[idx * 3 + 0] = (x - wx) / scale
            features[idx * 3 + 1] = (y - wy) / scale
            features[idx * 3 + 2] = (z - wz) / scale
        }

        return features
    }

    // ─────────────────────────────────────────────────────────────────────────
    // classifyFeatures() — maps a 63-float feature vector to an ISL letter
    //
    // ── CURRENT IMPLEMENTATION: stub k-NN skeleton ───────────────────────────
    // This is a skeleton that returns null until you populate trainingData.
    // Replace with one of:
    //
    //   Option A (recommended) — trained TFLite MLP:
    //     Load a .tflite model from assets, run interpreter.run(features, output)
    //
    //   Option B — k-NN with collected training data:
    //     Populate trainingData below with feature vectors you collect from
    //     real signing sessions (see data collection note below)
    //
    //   Option C — keep VLM fallback (Phase 1) until training data is ready:
    //     SignLanguageService can route to VLM when classifier returns null
    //
    // ── DATA COLLECTION NOTE ─────────────────────────────────────────────────
    // To train the classifier you need ~200 samples per letter (26 letters =
    // ~5200 samples total). Use this approach:
    //   1. Temporarily log features[] to a CSV file while signing known letters
    //   2. Run extractFeatures() on labelled frames from the INCLUDE dataset
    //   3. Train an MLP in Python (sklearn or PyTorch), export to TFLite
    //   4. Drop the .tflite file into assets/ and load it in classifyFeatures()
    // ─────────────────────────────────────────────────────────────────────────
    private fun classifyFeatures(features: FloatArray): Pair<String, Float>? {
        // ── STUB: replace this block with your real classifier ────────────────
        if (trainingData.isEmpty()) {
            Log.d(TAG, "No training data loaded — classifier returning null")
            return null
        }

        // k-NN: find the nearest neighbour in trainingData
        var bestLabel      = ""
        var bestDistance   = Float.MAX_VALUE

        for ((label, sample) in trainingData) {
            val distance = euclideanDistance(features, sample)
            if (distance < bestDistance) {
                bestDistance = distance
                bestLabel    = label
            }
        }

        if (bestLabel.isEmpty()) return null

        // Convert distance to a pseudo-confidence: closer = higher confidence
        // This is a rough heuristic — replace with softmax output from MLP
        val confidence = (1f / (1f + bestDistance)).coerceIn(0f, 1f)
        return Pair(bestLabel, confidence)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // trainingData — populate this with real ISL landmark samples
    //
    // Format: List of (label, featureVector) pairs
    // Label:  single uppercase letter "A"–"Z"
    // Vector: FloatArray of size 63 (output of extractFeatures())
    //
    // Start collecting data by logging features to CSV, then load them here
    // or train a TFLite model and replace classifyFeatures() entirely.
    // ─────────────────────────────────────────────────────────────────────────
    private val trainingData: List<Pair<String, FloatArray>> = emptyList()
    // TODO: Replace emptyList() with loaded training data or TFLite interpreter

    // ─────────────────────────────────────────────────────────────────────────
    // euclideanDistance() — L2 distance between two feature vectors
    // ─────────────────────────────────────────────────────────────────────────
    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // logFeaturesForCollection() — OPTIONAL data collection helper
    //
    // Call this temporarily while signing known letters to collect training data.
    // Pass the known letter label and the feature vector.
    // Output is a CSV line you can copy from Logcat and paste into a spreadsheet.
    //
    // Usage:
    //   In classify(), after extractFeatures(), add:
    //   logFeaturesForCollection("A", features)
    // ─────────────────────────────────────────────────────────────────────────
    fun logFeaturesForCollection(label: String, features: FloatArray) {
        val csv = features.joinToString(",")
        Log.i(TAG, "TRAINING_DATA,$label,$csv")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close() — release MediaPipe native resources
    // Call from ViewModel.onCleared() — never call during detection
    // ─────────────────────────────────────────────────────────────────────────
    fun close() {
        handLandmarker?.close()
        handLandmarker = null
        Log.d(TAG, "IslHandClassifier closed")
    }
}
