// ─── TASK-008: DetectionState sealed class defined at top of file ─────────────
// ─── TASK-009: SignLanguageService — pipeline mediator, SupervisorJob scope, ──
//              frame collection, detector calls, result routing
// Changes:
//   • DetectionState drives the full state machine (Idle → Detecting →
//     Processing → Detecting → …)
//   • SupervisorJob scope means one failed detection coroutine does NOT cancel the pipeline
//   • releaseFrame() is ALWAYS called in a finally block — any other placement
//     causes the AtomicBoolean gate to deadlock and freezes the camera
//   • 500ms inter-frame delay caps VLM API calls at ~2/sec, preventing overheating
//   • rawConfidence resets to 0.0 when a frame returns null (hand not visible)
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example

import android.content.Context
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.runanywhere.kotlin_starter_example.model.IslHandClassifier
import com.runanywhere.kotlin_starter_example.vision.CameraManager
import com.runanywhere.kotlin_starter_example.vision.DetectionResult
import com.runanywhere.kotlin_starter_example.vision.SignLanguageDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// TASK-008 — DetectionState sealed class
//
// Drives the state machine inside SignLanguageService.
// The ViewModel observes this to show the correct UI state.
//
// Flow:
//   Idle → Detecting → Processing → Detecting → …
//                                 ↘ BufferingWord → WordComplete
//                                 ↘ Error
// ─────────────────────────────────────────────────────────────────────────────
sealed class DetectionState {
    // Camera bound, no detection running
    object Idle : DetectionState()

    // Frame emitted from camera, waiting for VLM response
    object Detecting : DetectionState()

    // VLM call is currently in-flight
    object Processing : DetectionState()

    // One or more letters committed, word is being built
    data class BufferingWord(val partial: String) : DetectionState()

    // confirmWord() called — word is finalised and added to history
    data class WordComplete(val word: String) : DetectionState()

    // Unrecoverable error in the pipeline (shown to UI, pipeline continues)
    data class Error(val message: String) : DetectionState()

    // Human-readable label for Logcat
    override fun toString(): String = when (this) {
        is Idle          -> "Idle"
        is Detecting     -> "Detecting"
        is Processing    -> "Processing"
        is BufferingWord -> "BufferingWord(partial=$partial)"
        is WordComplete  -> "WordComplete(word=$word)"
        is Error         -> "Error(message=$message)"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TASK-009 — SignLanguageService
//
// Owns the CameraManager and SignLanguageDetector.
// Mediates the frame gate, state machine, and result routing.
// The ViewModel creates one instance and calls start/stop/release.
// ─────────────────────────────────────────────────────────────────────────────
class SignLanguageService(context: Context) {

    companion object {
        private const val TAG                  = "SignLanguageService"
        private const val INTER_FRAME_DELAY_MS = 500L  // caps API at ~2 calls/sec
    }

    // ── Dependencies ──────────────────────────────────────────────────────────
    private val cameraManager = CameraManager(context)
    private val detector      = SignLanguageDetector(context)

    // ── SupervisorJob scope ───────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var detectionJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Exposed flows — collected by SignLanguageViewModel
    // ─────────────────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    private val _detectionResults = MutableSharedFlow<DetectionResult>(
        extraBufferCapacity = 8
    )
    val detectionResults: SharedFlow<DetectionResult> = _detectionResults.asSharedFlow()

    private val _rawConfidence = MutableStateFlow(0f)
    val rawConfidence: StateFlow<Float> = _rawConfidence.asStateFlow()

    // ── State storage for TASK-013 ───────────────────────────────────────────
    private var isDetectionRunning = false
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    // ─────────────────────────────────────────────────────────────────────────
    // startDetection(lifecycle, preview) — binds camera ONLY (TASK-013)
    // ─────────────────────────────────────────────────────────────────────────
    fun startDetection(
        lifecycleOwner : LifecycleOwner,
        previewView    : PreviewView,
        classifier     : IslHandClassifier? = null
    ) {
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        Log.d(TAG, "Binding camera preview")
        cameraManager.bindPreview(lifecycleOwner, previewView)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // startPipeline() — (TASK-013) begins the frame collection loop
    // ─────────────────────────────────────────────────────────────────────────
    fun startPipeline(classifier: IslHandClassifier? = null) {
        val lo = lifecycleOwner ?: run {
            Log.w(TAG, "startPipeline called before lifecycleOwner set")
            return
        }
        val pv = previewView ?: run {
            Log.w(TAG, "startPipeline called before previewView set")
            return
        }

        if (isDetectionRunning) {
            Log.d(TAG, "Detection already running — ignoring startPipeline()")
            return
        }

        Log.d(TAG, "Starting detection pipeline")
        isDetectionRunning = true
        _state.value = DetectionState.Detecting

        detectionJob = serviceScope.launch {
            cameraManager
                .frameStream(lo, pv)
                .collect { frame ->
                    _state.value = DetectionState.Processing
                    Log.d(TAG, "Frame received — calling detector")

                    try {
                        val result = classifier?.classify(frame.jpegBytes)
                            ?: detector.detectLetterWithConfidence(frame.jpegBytes)
                        handleResult(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "Detection error: ${e.message}", e)
                    } finally {
                        cameraManager.releaseFrame()
                        delay(INTER_FRAME_DELAY_MS)
                        if (isDetectionRunning) {
                            _state.value = DetectionState.Detecting
                        }
                    }
                }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // stopDetection() — cancels the frame loop
    // ─────────────────────────────────────────────────────────────────────────
    fun stopDetection() {
        Log.d(TAG, "Stopping detection")
        isDetectionRunning = false
        detectionJob?.cancel()
        detectionJob = null
        _state.value  = DetectionState.Idle
        _rawConfidence.value = 0f
    }

    // ─────────────────────────────────────────────────────────────────────────
    // notifyWordComplete() — called by ViewModel when confirmWord() is triggered
    // ─────────────────────────────────────────────────────────────────────────
    fun notifyWordComplete(word: String) {
        _state.value = DetectionState.WordComplete(word)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // notifyBuffering() — called by ViewModel as letters accumulate
    // ─────────────────────────────────────────────────────────────────────────
    fun notifyBuffering(partial: String) {
        _state.value = DetectionState.BufferingWord(partial)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // release() — shuts down the executor and unbinds the camera
    // ─────────────────────────────────────────────────────────────────────────
    fun release() {
        stopDetection()
        cameraManager.release()
        lifecycleOwner = null
        previewView = null
        Log.d(TAG, "SignLanguageService released")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // handleResult() — routes DetectionResult or null to the correct flows
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun handleResult(result: DetectionResult?) {
        if (result != null) {
            _rawConfidence.value = result.confidence
            _detectionResults.emit(result)
            Log.i(TAG, "Result routed → $result")
        } else {
            _rawConfidence.value = 0f
            Log.d(TAG, "No detection this frame — confidence reset to 0")
        }
    }
}
