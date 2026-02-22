// ─── TASK-011: SignLanguageScreen — root composable, wires ViewModel → UI ─────
// Changes:
//   • Binds PreviewView to ViewModel via LaunchedEffect (once, on composition)
//   • Collects all 6 StateFlows with collectAsStateWithLifecycle
//   • Layers camera preview, gesture overlay, word HUD, confidence bar,
//     top bar, warmup banner, and control row
//   • Does NOT modify any child composable — only wires them together
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
    val latestResult    by viewModel.latestResult.collectAsStateWithLifecycle()

    // ─── TASK-013: Add warmup flow collection to SignLanguageScreen ───────────────
    val isWarmingUp       by viewModel.isWarmingUp.collectAsStateWithLifecycle()
    val isClassifierReady by viewModel.isClassifierReady.collectAsStateWithLifecycle()

    // ─── TASK-016: Session export ─────────────────────────────────────────────
    val context = LocalContext.current
    val onShare: () -> Unit = {
        val shareText = wordHistory.joinToString(" ").trim()
        if (shareText.isNotBlank()) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_TITLE, "ISL Session")
            }
            context.startActivity(
                Intent.createChooser(sendIntent, "Share ISL session")
            )
        }
    }

    // ── Camera preview setup ───────────────────────────────────────────────
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView    = remember { PreviewView(context) }

    // Bind camera once when the composable first enters composition
    LaunchedEffect(Unit) {
        viewModel.bindCamera(lifecycleOwner, previewView)
    }

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
            wordHistory      = wordHistory,
            onShare          = onShare,
            modifier         = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )

        // Layer 4 — Warmup banner (enhanced for TASK-013)
        WarmupBannerNewYork(
            isWarmingUp       = isWarmingUp,
            isClassifierReady = isClassifierReady,
            isSdkInitialised  = isSdkInitialised,
            modifier          = Modifier
                .fillMaxWidth()
                .padding(top = 72.dp)
                .align(Alignment.TopCenter)
        )

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
