// ─── TASK-010: SignLanguageViewModel — debounce logic, word builder, ───────────
//              all StateFlows, camera binding, ViewModel lifecycle
// Changes:
//   • Owns SignLanguageService instance (created here, released in onCleared)
//   • Collects service.detectionResults and applies 3-frame debounce
//   • Commits a letter to currentWord only when DEBOUNCE_WINDOW identical
//     frames appear consecutively at >= DEBOUNCE_MIN_CONFIDENCE (0.62)
//   • Exposes 6 StateFlows for SignLanguageScreen to observe
//   • bindCamera() called once from the UI with the LifecycleOwner + PreviewView
//   • startDetection() guards against calling before VLM is loaded
//   • reset() clears all transient state without stopping the camera
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example

import android.app.Application
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.runanywhere.kotlin_starter_example.model.IslHandClassifier
import com.runanywhere.kotlin_starter_example.vision.DetectionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SignLanguageViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG                   = "SignLanguageViewModel"

        // ── Debounce tuning ───────────────────────────────────────────────────
        // DEBOUNCE_WINDOW: how many consecutive identical frames must appear
        // before the letter is committed to currentWord.
        // Lower = more responsive but noisier. Raise after VLM is stable.
        private const val DEBOUNCE_WINDOW        = 3

        // DEBOUNCE_MIN_CONFIDENCE: per-frame gate applied during accumulation.
        // Frames below this are discarded and reset the debounce buffer.
        // Note: MIN_CONFIDENCE in SignLanguageDetector (0.40) is a coarser gate —
        // this is the finer gate applied during word-building.
        private const val DEBOUNCE_MIN_CONFIDENCE = 0.62f

        // ─── TASK-014: Auto word boundary on hand absence ─────────────────────────────
        // Frames of zero confidence before auto-confirming the current word.
        // At ~2 frames/sec (500ms inter-frame delay) this = ~2 seconds of no hand.
        private const val AUTO_WORD_BOUNDARY_FRAMES = 4
    }

    // ── Service — owns CameraManager + Detector ───────────────────────────────
    private val service = SignLanguageService(application)

    // ─── TASK-012: Add classifier to ViewModel ────────────────────────────────────
    private val classifier = IslHandClassifier(application)

    // ── Debounce accumulator ──────────────────────────────────────────────────
    // Stores the last N letter results. When all N match, the letter is committed.
    private val recentLetters = ArrayDeque<String>(DEBOUNCE_WINDOW)

    // ── Zero-frame counter for auto word-boundary (TASK-014 prep) ────────────
    private var zeroConfidenceFrames = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Exposed StateFlows — observed by SignLanguageScreen via collectAsState()
    // ─────────────────────────────────────────────────────────────────────────

    // The ISL letter currently being detected (clears when hand leaves frame)
    private val _currentLetter  = MutableStateFlow("")
    val currentLetter: StateFlow<String> = _currentLetter.asStateFlow()

    // The word being built from committed letters
    private val _currentWord    = MutableStateFlow("")
    val currentWord: StateFlow<String> = _currentWord.asStateFlow()

    // Raw confidence from the latest frame (0.0 when no hand visible)
    private val _confidenceLevel = MutableStateFlow(0f)
    val confidenceLevel: StateFlow<Float> = _confidenceLevel.asStateFlow()

    // Full pipeline state (Idle / Detecting / Processing / etc.)
    private val _state          = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    // History of confirmed words from this session
    private val _wordHistory    = MutableStateFlow<List<String>>(emptyList())
    val wordHistory: StateFlow<List<String>> = _wordHistory.asStateFlow()

    // The latest non-null DetectionResult (used by GestureOverlay)
    private val _latestResult   = MutableStateFlow<DetectionResult?>(null)
    val latestResult: StateFlow<DetectionResult?> = _latestResult.asStateFlow()

    // ─── TASK-013: Warmup state added to ViewModel ────────────────────────────────
    // Warmup: true while the classifier is JIT-compiling on first run
    // Drives WarmupBannerNewYork visibility in SignLanguageScreen
    private val _isWarmingUp = MutableStateFlow(false)
    val isWarmingUp: StateFlow<Boolean> = _isWarmingUp.asStateFlow()

    // Add this new StateFlow to track classifier readiness:
    private val _isClassifierReady = MutableStateFlow(false)
    val isClassifierReady: StateFlow<Boolean> = _isClassifierReady.asStateFlow()

    // ── Init: bridge service state and results into ViewModel flows ───────────
    init {
        // Mirror service state into ViewModel's state flow
        viewModelScope.launch {
            service.state.collect { serviceState ->
                _state.value = serviceState
            }
        }

        // Mirror raw confidence into ViewModel flow + handle zero-confidence frames
        viewModelScope.launch {
            service.rawConfidence.collect { confidence ->
                _confidenceLevel.value = confidence

                if (confidence == 0f) {
                    // No hand visible this frame
                    _currentLetter.value = ""
                    zeroConfidenceFrames++

                    // ── TASK-014: Auto word boundary ──────────────────────────────
                    // If hand has been absent for AUTO_WORD_BOUNDARY_FRAMES consecutive
                    // frames AND there is a word being built, confirm it automatically.
                    if (zeroConfidenceFrames >= AUTO_WORD_BOUNDARY_FRAMES &&
                        _currentWord.value.isNotBlank()
                    ) {
                        Log.i(TAG, "Auto word boundary triggered after " +
                                   "$zeroConfidenceFrames zero-confidence frames")
                        confirmWord()
                        zeroConfidenceFrames = 0
                    }
                } else {
                    // Hand is visible — reset the absence counter
                    zeroConfidenceFrames = 0
                }
            }
        }

        // Collect detection results and apply debounce
        viewModelScope.launch {
            service.detectionResults.collect { result ->
                onNewResult(result)
            }
        }

        // ─── TASK-013: Replace the existing classifier init block with this ───────────
        viewModelScope.launch {
            // Step 1 — Show warmup banner
            _isWarmingUp.value = true
            _state.value = DetectionState.Idle
            Log.d(TAG, "Warmup started")

            // Step 2 — Load the MediaPipe model from assets
            val initialised = classifier.initialise()

            if (initialised) {
                // Step 3 — Run one dummy inference to JIT-compile the runtime
                // Uses a 224×224 blank bitmap (standard vision model input size)
                // This silently "warms up" the TFLite/MediaPipe interpreter so
                // the first real detection frame has no startup latency
                warmup()
            } else {
                Log.w(TAG, "Classifier failed to initialise — VLM fallback will be used")
                _isClassifierReady.value = false
            }

            // Step 4 — Hide warmup banner regardless of outcome
            _isWarmingUp.value = false
            Log.d(TAG, "Warmup complete — ready for detection")
        }

        Log.d(TAG, "ViewModel initialised — service ready")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // bindCamera() — called once from SignLanguageScreen's LaunchedEffect
    //
    // Must be called BEFORE startDetection(). The PreviewView must be
    // attached to the composition before this is called.
    // ─────────────────────────────────────────────────────────────────────────
    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        Log.d(TAG, "bindCamera() called")
        service.startDetection(lifecycleOwner, previewView, classifier)
    }

    // ─── TASK-013: Guard startDetection() behind warmup completion ───────────────
    fun startDetection() {
        if (_isWarmingUp.value) {
            Log.w(TAG, "startDetection() called during warmup — ignoring")
            return
        }
        Log.d(TAG, "startDetection() called")
        service.startPipeline(classifier)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // stopDetection() — pauses the detection loop, preview stays visible
    // ─────────────────────────────────────────────────────────────────────────
    fun stopDetection() {
        Log.d(TAG, "stopDetection() called")
        service.stopDetection()
        _currentLetter.value  = ""
        _confidenceLevel.value = 0f
        recentLetters.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // confirmWord() — finalises currentWord and adds it to wordHistory
    //
    // Called by the UI's Confirm button or auto word-boundary (TASK-014).
    // ─────────────────────────────────────────────────────────────────────────
    fun confirmWord() {
        val word = _currentWord.value.trim()
        if (word.isBlank()) {
            Log.d(TAG, "confirmWord() called with empty word — ignoring")
            return
        }

        Log.i(TAG, "Word confirmed: $word")
        _wordHistory.value  = _wordHistory.value + word
        _currentWord.value  = ""
        _currentLetter.value = ""
        recentLetters.clear()
        service.notifyWordComplete(word)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // reset() — clears all transient state without stopping the camera
    // ─────────────────────────────────────────────────────────────────────────
    fun reset() {
        Log.d(TAG, "reset() called — clearing all state")
        _currentLetter.value   = ""
        _currentWord.value     = ""
        _confidenceLevel.value = 0f
        _wordHistory.value     = emptyList()
        _latestResult.value    = null
        recentLetters.clear()
        zeroConfidenceFrames   = 0   // ← TASK-014: reset absence counter too
    }

    // ─────────────────────────────────────────────────────────────────────────
    // onNewResult() — debounce logic
    //
    // Called for every non-null DetectionResult from the service.
    //
    // Rules:
    //   1. Update currentLetter and latestResult immediately (real-time display)
    //   2. If confidence >= DEBOUNCE_MIN_CONFIDENCE, add letter to recentLetters
    //   3. If confidence < DEBOUNCE_MIN_CONFIDENCE, clear the accumulator
    //   4. If recentLetters has DEBOUNCE_WINDOW identical letters → commit
    //
    // Example flows:
    //   A(0.88) A(0.85) A(0.91) → recentLetters=[A,A,A] → commit "A" → clear
    //   A(0.88) A(0.85) B(0.79) → recentLetters=[A,A,B] → no commit (not identical)
    //   A(0.88) A(0.30) A(0.91) → second frame below threshold → recentLetters reset
    // ─────────────────────────────────────────────────────────────────────────
    private fun onNewResult(result: DetectionResult) {
        // ── Step 1: Immediate display update ─────────────────────────────────
        _currentLetter.value = result.letter
        _latestResult.value  = result

        // ── Step 2: Confidence gate for debounce accumulation ─────────────────
        if (result.confidence >= DEBOUNCE_MIN_CONFIDENCE) {
            // Maintain fixed-size window
            if (recentLetters.size >= DEBOUNCE_WINDOW) {
                recentLetters.removeFirst()
            }
            recentLetters.addLast(result.letter)
            Log.d(TAG, "Debounce buffer: $recentLetters")
        } else {
            // Below threshold — this frame is too uncertain to count
            Log.d(TAG, "Confidence ${result.confidence} below debounce gate — buffer reset")
            recentLetters.clear()
        }

        // ── Step 3: Commit check ──────────────────────────────────────────────
        if (recentLetters.size == DEBOUNCE_WINDOW &&
            recentLetters.distinct().size == 1
        ) {
            val committed = result.letter
            _currentWord.value += committed
            recentLetters.clear()
            Log.i(TAG, "Letter committed: $committed → currentWord=${_currentWord.value}")

            service.notifyBuffering(_currentWord.value)
        }
    }

    // ─── TASK-013: Warmup function ────────────────────────────────────────────────
    // Runs one dummy inference through the classifier immediately after init.
    // The MediaPipe runtime JIT-compiles on first call — doing this on a blank
    // frame means the user never sees the freeze on their first real sign.
    //
    // A minimum display time of 1 second is enforced so the banner is readable
    // and doesn't flash briefly on fast devices.
    private suspend fun warmup() {
        val warmupStart = System.currentTimeMillis()

        try {
            Log.d(TAG, "Running warmup inference on blank frame")

            // Blank 224×224×3 byte array — enough to trigger JIT compilation
            // without needing a real image or camera frame
            classifier.runInference(ByteArray(224 * 224 * 3))

            _isClassifierReady.value = true
            Log.i(TAG, "Warmup inference succeeded — classifier ready")

        } catch (e: Exception) {
            // Warmup failure is non-fatal — VLM fallback remains active
            _isClassifierReady.value = false
            Log.w(TAG, "Warmup inference failed (non-fatal): ${e.message}")
        }

        // Enforce a minimum banner display time so it's readable on fast devices
        // If warmup took longer than MIN_DISPLAY_MS, this delay is skipped
        val elapsed = System.currentTimeMillis() - warmupStart
        val MIN_DISPLAY_MS = 1_200L
        if (elapsed < MIN_DISPLAY_MS) {
            delay(MIN_DISPLAY_MS - elapsed)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // onCleared() — ViewModel is being destroyed; release all resources
    // ─────────────────────────────────────────────────────────────────────────
    override fun onCleared() {
        super.onCleared()
        service.release()
        classifier.close()
        Log.d(TAG, "ViewModel cleared — service and classifier released")
    }
}
