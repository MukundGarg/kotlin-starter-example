// ─── TASK-008: DetectionState sealed class defined at top of file ─────────────
// ─── TASK-009: SignLanguageService — pipeline mediator, SupervisorJob scope, ──
//              frame collection, detector calls, result routing
// Changes:
//   • DetectionState drives the full state machine (Idle → Detecting →
//     Processing → Detecting → …)
//   • SupervisorJob scope means one failed detection doesn't cancel the pipeline
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
    // SupervisorJob: one failed detection coroutine does NOT cancel the pipeline.
    // IO dispatcher: VLM network call is blocking-ish; keep off Main thread.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var detectionJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Exposed flows — collected by SignLanguageViewModel
    // ─────────────────────────────────────────────────────────────────────────

    // Full pipeline state machine
    private val _state = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    // Non-null DetectionResults only — null frames are swallowed here
    private val _detectionResults = MutableSharedFlow<DetectionResult>(
        extraBufferCapacity = 8  // prevents suspension if ViewModel is slow to collect
    )
    val detectionResults: SharedFlow<DetectionResult> = _detectionResults.asSharedFlow()

    // Raw confidence — resets to 0.0 when no hand detected (drives ConfidenceBar)
    private val _rawConfidence = MutableStateFlow(0f)
    val rawConfidence: StateFlow<Float> = _rawConfidence.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // startDetection() — binds camera and begins the frame collection loop
    // ─────────────────────────────────────────────────────────────────────────
    fun startDetection(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (detectionJob?.isActive == true) {
            Log.d(TAG, "Detection already running — ignoring startDetection()")
            return
        }

        Log.d(TAG, "Starting detection pipeline")
        _state.value = DetectionState.Detecting

        detectionJob = serviceScope.launch {
            cameraManager
                .frameStream(lifecycleOwner, previewView)
                .collect { frame ->
                    _state.value = DetectionState.Processing
                    Log.d(TAG, "Frame received — calling detector")

                    try {
                        // ── THE CRITICAL PATTERN ─────────────────────────────
                        // releaseFrame() MUST be in the finally block.
                        // If it's inside the try, an exception leaves the gate
                        // permanently locked and the camera freezes forever.
                        val result = detector.detectLetterWithConfidence(frame.jpegBytes)
                        handleResult(result)
                    } finally {
                        cameraManager.releaseFrame()  // ALWAYS runs, even on exception
                        delay(INTER_FRAME_DELAY_MS)   // throttle to ~2 API calls/sec
                        _state.value = DetectionState.Detecting
                        Log.d(TAG, "State: ${_state.value}")
                    }
                }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // stopDetection() — cancels the frame loop, leaves camera provider alive
    // so preview continues rendering while idle
    // ─────────────────────────────────────────────────────────────────────────
    fun stopDetection() {
        Log.d(TAG, "Stopping detection")
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
    // release() — called from ViewModel.onCleared() ONLY
    // Shuts down the executor and unbinds the camera
    // ─────────────────────────────────────────────────────────────────────────
    fun release() {
        stopDetection()
        cameraManager.release()
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
            // No hand visible or confidence too low — reset confidence bar
            _rawConfidence.value = 0f
            Log.d(TAG, "No detection this frame — confidence reset to 0")
        }
    }
}
