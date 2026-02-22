// ─── TASK-011: SignLanguageScreen — root composable, wires ViewModel → UI ─────
// Changes:
//   • Binds PreviewView to ViewModel via LaunchedEffect (once, on composition)
//   • Collects all 6 StateFlows with collectAsStateWithLifecycle
//   • Layers camera preview, gesture overlay, word HUD, confidence bar,
//     top bar, warmup banner, and control row
//   • Does NOT modify any child composable — only wires them together
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example.ui

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
    val latestResult    by viewModel.latestResult.collectAsStateWithLifecycle()

    // ── Camera preview setup ───────────────────────────────────────────────
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView    = remember { PreviewView(context) }

    // Bind camera once when the composable first enters composition
    LaunchedEffect(Unit) {
        viewModel.bindCamera(lifecycleOwner, previewView)
    }

    // ── Warmup banner visibility ───────────────────────────────────────────
    val isWarmingUp = state is DetectionState.Idle && !isSdkInitialised

    // ── Root layout: full-screen black background ──────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        // Layer 1 — Camera preview (fills entire screen)
        AndroidView(
            factory  = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2 — Gesture overlay (letter + confidence badge over camera)
        GestureOverlay(
            detectedLetter  = currentLetter,
            confidenceLevel = confidenceLevel,
            modifier        = Modifier.fillMaxSize()
        )

        // Layer 3 — Top bar (app title + SDK status indicator)
        TopBarNewYork(
            isSdkInitialised = isSdkInitialised,
            detectionState   = state,
            modifier         = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )

        // Layer 4 — Warmup banner (shown while SDK is loading)
        if (isWarmingUp) {
            WarmupBannerNewYork(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 72.dp)
                    .align(Alignment.TopCenter)
            )
        }

        // Layer 5 — Bottom panel: confidence bar + word HUD + controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xCC0D0D1A))  // semi-transparent dark
                .padding(bottom = 24.dp)
        ) {
            // Confidence bar sits just above the word HUD
            ConfidenceBar(
                confidenceLevel = confidenceLevel,
                modifier        = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )

            // Word builder: shows current letter, current word, history
            WordBuilderHud(
                currentLetter = currentLetter,
                currentWord   = currentWord,
                wordHistory   = wordHistory,
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Control row: Start / Stop / Reset / Confirm buttons
            ControlRowNewYork(
                detectionState = state,
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
