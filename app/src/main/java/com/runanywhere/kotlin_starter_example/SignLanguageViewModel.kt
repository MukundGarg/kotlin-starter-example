// ─── TASK-010: SignLanguageViewModel — debounce logic, word builder, ───────────
//              all StateFlows, camera binding, ViewModel lifecycle
// Changes:
//   • FIXED: startDetection() and stopDetection() now correctly toggle the
//     detection gate in SignLanguageService without unbinding the camera.
//   • bindCamera() ensures the camera is bound exactly once.
//   • Maintains the 3-frame debounce and auto-word boundary logic.
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
        private const val DEBOUNCE_WINDOW        = 3
        private const val DEBOUNCE_MIN_CONFIDENCE = 0.62f
        private const val AUTO_WORD_BOUNDARY_FRAMES = 4
    }

    private val service = SignLanguageService(application)
    private val classifier = IslHandClassifier(application)

    private val recentLetters = ArrayDeque<String>(DEBOUNCE_WINDOW)
    private var zeroConfidenceFrames = 0

    private val _currentLetter  = MutableStateFlow("")
    val currentLetter: StateFlow<String> = _currentLetter.asStateFlow()

    private val _currentWord    = MutableStateFlow("")
    val currentWord: StateFlow<String> = _currentWord.asStateFlow()

    private val _confidenceLevel = MutableStateFlow(0f)
    val confidenceLevel: StateFlow<Float> = _confidenceLevel.asStateFlow()

    private val _state          = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    private val _wordHistory    = MutableStateFlow<List<String>>(emptyList())
    val wordHistory: StateFlow<List<String>> = _wordHistory.asStateFlow()

    private val _latestResult   = MutableStateFlow<DetectionResult?>(null)
    val latestResult: StateFlow<DetectionResult?> = _latestResult.asStateFlow()

    private val _isWarmingUp = MutableStateFlow(false)
    val isWarmingUp: StateFlow<Boolean> = _isWarmingUp.asStateFlow()

    private val _isClassifierReady = MutableStateFlow(false)
    val isClassifierReady: StateFlow<Boolean> = _isClassifierReady.asStateFlow()

    init {
        // Bridge service state
        viewModelScope.launch {
            service.state.collect { _state.value = it }
        }

        // Bridge confidence and handle auto-word boundary
        viewModelScope.launch {
            service.rawConfidence.collect { confidence ->
                _confidenceLevel.value = confidence
                if (confidence == 0f) {
                    _currentLetter.value = ""
                    zeroConfidenceFrames++
                    if (zeroConfidenceFrames >= AUTO_WORD_BOUNDARY_FRAMES && _currentWord.value.isNotBlank()) {
                        confirmWord()
                        zeroConfidenceFrames = 0
                    }
                } else {
                    zeroConfidenceFrames = 0
                }
            }
        }

        // Bridge results
        viewModelScope.launch {
            service.detectionResults.collect { onNewResult(it) }
        }

        // Initialize and Warmup
        viewModelScope.launch {
            _isWarmingUp.value = true
            val initialised = classifier.initialise()
            if (initialised) {
                warmup()
            }
            _isWarmingUp.value = false
        }
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        service.bindCameraAndStartStream(lifecycleOwner, previewView, classifier)
    }

    fun startDetection() {
        if (_isWarmingUp.value) return
        service.startPipeline(classifier)
    }

    fun stopDetection() {
        service.stopDetection()
        _currentLetter.value  = ""
        _confidenceLevel.value = 0f
        recentLetters.clear()
    }

    fun confirmWord() {
        val word = _currentWord.value.trim()
        if (word.isBlank()) return
        _wordHistory.value += word
        _currentWord.value = ""
        _currentLetter.value = ""
        recentLetters.clear()
        service.notifyWordComplete(word)
    }

    fun reset() {
        _currentLetter.value   = ""
        _currentWord.value     = ""
        _confidenceLevel.value = 0f
        _wordHistory.value     = emptyList()
        _latestResult.value    = null
        recentLetters.clear()
        zeroConfidenceFrames   = 0
    }

    private fun onNewResult(result: DetectionResult) {
        _currentLetter.value = result.letter
        _latestResult.value  = result

        if (result.confidence >= DEBOUNCE_MIN_CONFIDENCE) {
            if (recentLetters.size >= DEBOUNCE_WINDOW) recentLetters.removeFirst()
            recentLetters.addLast(result.letter)
        } else {
            recentLetters.clear()
        }

        if (recentLetters.size == DEBOUNCE_WINDOW && recentLetters.distinct().size == 1) {
            val committed = result.letter
            _currentWord.value += committed
            recentLetters.clear()
            service.notifyBuffering(_currentWord.value)
        }
    }

    private suspend fun warmup() {
        val start = System.currentTimeMillis()
        try {
            classifier.runInference(ByteArray(224 * 224 * 3))
            _isClassifierReady.value = true
        } catch (e: Exception) {
            _isClassifierReady.value = false
        }
        val elapsed = System.currentTimeMillis() - start
        if (elapsed < 1200L) delay(1200L - elapsed)
    }

    override fun onCleared() {
        super.onCleared()
        service.release()
        classifier.close()
    }
}
