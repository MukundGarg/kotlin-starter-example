// ─── TASK-016: TopBarNewYork — share button added ─────────────────────────────
// Changes:
//   • Share icon button appears in top-right when wordHistory is non-empty
//   • Tapping it fires onShare() which opens the system share sheet
//   • Button is hidden when there is nothing to share (wordHistory empty)
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runanywhere.kotlin_starter_example.DetectionState

@Composable
fun TopBarNewYork(
    isSdkInitialised : Boolean,
    detectionState   : DetectionState,
    wordHistory      : List<String>,       // ← TASK-016: drives share button visibility
    onShare          : () -> Unit,         // ← TASK-016: share handler
    modifier         : Modifier = Modifier
) {
    Row(
        modifier              = modifier
            .background(Color(0xCC0D0D1A))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // ── App title ──────────────────────────────────────────────────────
        Text(
            text       = "ISL Detector",
            fontSize   = 18.sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White
        )

        // ── Right side: status pill + share button ─────────────────────────
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── TASK-016: Share button — only visible when history exists ──
            AnimatedVisibility(
                visible = wordHistory.isNotEmpty(),
                enter   = fadeIn() + scaleIn(),
                exit    = fadeOut() + scaleOut()
            ) {
                IconButton(
                    onClick  = onShare,
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = Color(0x226C63FF),
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    Icon(
                        imageVector        = Icons.Default.Share,
                        contentDescription = "Share ISL session",
                        tint               = Color(0xFF6C63FF),
                        modifier           = Modifier.size(18.dp)
                    )
                }
            }

            // ── Status pill ────────────────────────────────────────────────
            val (pillColor, pillText) = when {
                !isSdkInitialised ->
                    Color(0xFFE53935) to "Offline"
                detectionState is DetectionState.Processing ->
                    Color(0xFFFFA000) to "Detecting…"
                detectionState is DetectionState.Detecting ->
                    Color(0xFF43A047) to "Ready"
                detectionState is DetectionState.BufferingWord ->
                    Color(0xFF6C63FF) to "Building…"
                detectionState is DetectionState.WordComplete ->
                    Color(0xFF43A047) to "Word saved"
                detectionState is DetectionState.Idle ->
                    Color(0xFF9090A8) to "Idle"
                else ->
                    Color(0xFF9090A8) to "—"
            }

            Box(
                modifier = Modifier
                    .background(
                        color = pillColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text     = pillText,
                    fontSize = 12.sp,
                    color    = pillColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
