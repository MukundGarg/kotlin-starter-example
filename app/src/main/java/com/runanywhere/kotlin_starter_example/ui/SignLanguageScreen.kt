// ─── TASK-011: SignLanguageScreen — root composable, wires ViewModel → UI ─────
// Changes:
//   • FIXED: Camera preview visibility.
//   • Followed Quick Fix Checklist:
//     1. Wrap PreviewView in AndroidView and store reference via mutableState.
//     2. Use LocalLifecycleOwner.current for binding.
//     3. Ensure bindCamera is called when the view reference is ready.
//   • Improved layering to ensure GestureOverlay doesn't block interactions if needed.
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example.ui

import android.content.Intent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runanywhere.kotlin_starter_example.DetectionState
import com.runanywhere.kotlin_starter_example.SignLanguageViewModel
import com.runanywhere.kotlin_starter_example.ui.components.*

@Composable
fun SignLanguageScreen(
    viewModel: SignLanguageViewModel,
    isSdkInitialised: Boolean
) {
    // ── Collect all StateFlows ─────────────────────────────────────────────
    val currentLetter   by viewModel.currentLetter.collectAsStateWithLifecycle()
    val currentWord     by viewModel.currentWord.collectAsStateWithLifecycle()
    val confidenceLevel by viewModel.confidenceLevel.collectAsStateWithLifecycle()
    val state           by viewModel.state.collectAsStateWithLifecycle()
    val wordHistory     by viewModel.wordHistory.collectAsStateWithLifecycle()

    val isWarmingUp       by viewModel.isWarmingUp.collectAsStateWithLifecycle()
    val isClassifierReady by viewModel.isClassifierReady.collectAsStateWithLifecycle()

    // ── Session export ─────────────────────────────────────────────────────
    val context = LocalContext.current
    val onShare: () -> Unit = {
        val shareText = wordHistory.joinToString(" ").trim()
        if (shareText.isNotBlank()) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_TITLE, "ISL Session")
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share ISL session"))
        }
    }

    // ── Camera preview setup ───────────────────────────────────────────────
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Store a reference to the PreviewView created by AndroidView
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Bind camera whenever the view reference is set or changed
    LaunchedEffect(previewViewRef, lifecycleOwner) {
        previewViewRef?.let { view ->
            viewModel.bindCamera(lifecycleOwner, view)
        }
    }

    // ── Root layout ────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        // Layer 1 — Camera preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // TextureView (COMPATIBLE) is better for Compose overlays
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }.also {
                    previewViewRef = it
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { /* No-op: binding handled in LaunchedEffect */ }
        )

        // Layer 2 — Gesture overlay (letter + confidence badge)
        GestureOverlay(
            detectedLetter  = currentLetter,
            confidenceLevel = confidenceLevel,
            modifier        = Modifier.fillMaxSize()
        )

        // Layer 3 — UI Panels (Top Bar, Warmup, Bottom Panel)
        Column(modifier = Modifier.fillMaxSize()) {
            
            TopBarNewYork(
                isSdkInitialised = isSdkInitialised,
                detectionState   = state,
                wordHistory      = wordHistory,
                onShare          = onShare,
                modifier         = Modifier.fillMaxWidth()
            )

            WarmupBannerNewYork(
                isWarmingUp       = isWarmingUp,
                isClassifierReady = isClassifierReady,
                isSdkInitialised  = isSdkInitialised,
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Bottom panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC0D0D1A))
                    .padding(bottom = 24.dp)
            ) {
                ConfidenceBar(
                    confidenceLevel = confidenceLevel,
                    modifier        = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )

                WordBuilderHud(
                    currentLetter = currentLetter,
                    currentWord   = currentWord,
                    wordHistory   = wordHistory,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                ControlRowNewYork(
                    detectionState = state,
                    isWarmingUp    = isWarmingUp,
                    onStart        = { viewModel.startDetection() },
                    onStop         = { viewModel.stopDetection()  },
                    onReset        = { viewModel.reset()          },
                    onConfirm      = { viewModel.confirmWord()    },
                    modifier       = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}
