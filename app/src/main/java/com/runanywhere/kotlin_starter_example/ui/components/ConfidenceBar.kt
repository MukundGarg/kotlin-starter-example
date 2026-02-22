// ─── TASK-011: ConfidenceBar — animated horizontal confidence indicator ────────

package com.runanywhere.kotlin_starter_example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConfidenceBar(
    confidenceLevel : Float,
    modifier        : Modifier = Modifier
) {
    // Smooth animation — bar glides rather than snaps (satisfies TASK-015 early)
    val animatedConfidence by animateFloatAsState(
        targetValue    = confidenceLevel.coerceIn(0f, 1f),
        animationSpec  = tween(durationMillis = 150),
        label          = "confidenceBar"
    )

    // Colour: red (0%) → amber (50%) → green (100%)
    val barColor = lerp(
        lerp(Color(0xFFE53935), Color(0xFFFFA000), (animatedConfidence * 2f).coerceIn(0f, 1f)),
        Color(0xFF43A047),
        ((animatedConfidence - 0.5f) * 2f).coerceIn(0f, 1f)
    )

    Column(modifier = modifier) {
        Row(
            modifier            = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment   = Alignment.CenterVertically
        ) {
            Text(
                text     = "Confidence",
                fontSize = 11.sp,
                color    = Color(0xFF9090A8)
            )
            Text(
                text     = "${"%.0f".format(animatedConfidence * 100)}%",
                fontSize = 11.sp,
                color    = Color(0xFF9090A8)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF2A2A3E))
        ) {
            // Fill
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedConfidence)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
    }
}
