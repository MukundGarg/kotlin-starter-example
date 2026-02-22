// ─── TASK-009: SignLanguageService — pipeline mediator, SupervisorJob scope, ──
//              frame collection, detector calls, result routing
// Changes:
//   • FIXED: Detection pipeline wiring. The gate (isDetectionRunning) is now
//     correctly toggled by startPipeline() and stopDetection().
//   • SINGLE BINDING: The frame collection loop runs continuously to keep the 
//     preview active, but only routes frames to the classifier when 
//     isDetectionRunning is true.
//   • Always call cameraManager.releaseFrame() to allow the next frame to be 
//     captured, even if processing is skipped.
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
import java.util.concurrent.atomic.AtomicBoolean

sealed class DetectionState {
    object Idle : DetectionState()
    object Detecting : DetectionState()
    object Processing : DetectionState()
    data class BufferingWord(val partial: String) : DetectionState()
    data class WordComplete(val word: String) : DetectionState()
    data class Error(val message: String) : DetectionState()

    override fun toString(): String = when (this) {
        is Idle          -> "Idle"
        is Detecting     -> "Detecting"
        is Processing    -> "Processing"
        is BufferingWord -> "BufferingWord(partial=$partial)"
        is WordComplete  -> "WordComplete(word=$word)"
        is Error         -> "Error(message=$message)"
    }
}

class SignLanguageService(context: Context) {

    companion object {
        private const val TAG                  = "SignLanguageService"
        private const val INTER_FRAME_DELAY_MS = 500L
    }

    private val cameraManager = CameraManager(context)
    private val detector      = SignLanguageDetector(context)
    private val serviceScope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var detectionJob: Job? = null

    private val _state = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    private val _detectionResults = MutableSharedFlow<DetectionResult>(extraBufferCapacity = 8)
    val detectionResults: SharedFlow<DetectionResult> = _detectionResults.asSharedFlow()

    private val _rawConfidence = MutableStateFlow(0f)
    val rawConfidence: StateFlow<Float> = _rawConfidence.asStateFlow()

    private val isDetectionRunning = AtomicBoolean(false)
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    fun bindCameraAndStartStream(
        lifecycleOwner : LifecycleOwner,
        previewView    : PreviewView,
        classifier     : IslHandClassifier? = null
    ) {
        if (this.previewView === previewView && this.lifecycleOwner === lifecycleOwner && detectionJob?.isActive == true) {
            Log.d(TAG, "Already bound — skipping redundant binding")
            return
        }

        detectionJob?.cancel()
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        
        detectionJob = serviceScope.launch {
            cameraManager
                .frameStream(lifecycleOwner, previewView)
                .collect { frame ->
                    // Always check the gate
                    if (!isDetectionRunning.get()) {
                        cameraManager.releaseFrame()
                        return@collect
                    }
                    
                    _state.value = DetectionState.Processing
                    Log.v(TAG, "Processing frame...")

                    try {
                        // Priority: Local Classifier → VLM Detector fallback
                        val result = classifier?.classify(frame.jpegBytes)
                            ?: detector.detectLetterWithConfidence(frame.jpegBytes)
                        handleResult(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "Detection error: ${e.message}")
                    } finally {
                        cameraManager.releaseFrame()
                        delay(INTER_FRAME_DELAY_MS)
                        if (isDetectionRunning.get()) {
                            _state.value = DetectionState.Detecting
                        }
                    }
                }
        }
    }

    fun startPipeline(classifier: IslHandClassifier? = null) {
        if (isDetectionRunning.get()) return
        Log.i(TAG, "Detection STARTED (gate opened)")
        isDetectionRunning.set(true)
        _state.value = DetectionState.Detecting
    }

    fun stopDetection() {
        Log.i(TAG, "Detection STOPPED (gate closed)")
        isDetectionRunning.set(false)
        _state.value = DetectionState.Idle
        _rawConfidence.value = 0f
    }

    fun release() {
        isDetectionRunning.set(false)
        detectionJob?.cancel()
        detectionJob = null
        cameraManager.release()
        lifecycleOwner = null
        previewView = null
    }

    private suspend fun handleResult(result: DetectionResult?) {
        if (result != null) {
            _rawConfidence.value = result.confidence
            _detectionResults.emit(result)
            Log.d(TAG, "Result: ${result.letter} (${result.confidence})")
        } else {
            _rawConfidence.value = 0f
        }
    }

    fun notifyWordComplete(word: String) {
        _state.value = DetectionState.WordComplete(word)
    }

    fun notifyBuffering(partial: String) {
        _state.value = DetectionState.BufferingWord(partial)
    }
}
