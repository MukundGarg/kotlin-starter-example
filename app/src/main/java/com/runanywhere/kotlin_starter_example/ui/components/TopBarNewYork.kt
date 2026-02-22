// ─── TASK-011: TopBarNewYork — app title + SDK status + detection state ────────

package com.runanywhere.kotlin_starter_example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
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
    modifier         : Modifier = Modifier
) {
    Row(
        modifier              = modifier
            .background(Color(0xCC0D0D1A))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // App title
        Text(
            text       = "ISL Detector",
            fontSize   = 18.sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White
        )

        // Status pill — SDK status + current detection state
        val (pillColor, pillText) = when {
            !isSdkInitialised                   -> Color(0xFFE53935) to "Offline"
            detectionState is DetectionState.Processing -> Color(0xFFFFA000) to "Detecting…"
            detectionState is DetectionState.Detecting  -> Color(0xFF43A047) to "Ready"
            detectionState is DetectionState.Idle       -> Color(0xFF9090A8) to "Idle"
            else                                -> Color(0xFF9090A8) to "—"
        }

        Box(
            modifier = Modifier
                .background(
                    color = pillColor.copy(alpha = 0.2f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text     = pillText,
                fontSize = 12.sp,
                color    = pillColor
            )
        }
    }
}
