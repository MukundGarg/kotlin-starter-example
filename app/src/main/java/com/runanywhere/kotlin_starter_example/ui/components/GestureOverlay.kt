// ─── TASK-011: GestureOverlay — real-time letter badge over camera preview ────

package com.runanywhere.kotlin_starter_example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GestureOverlay(
    detectedLetter  : String,
    confidenceLevel : Float,
    modifier        : Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Only show the badge when a letter is actively detected
        AnimatedVisibility(
            visible = detectedLetter.isNotBlank(),
            enter   = fadeIn() + scaleIn(),
            exit    = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(
                        color = Color(0xCC6C63FF),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 36.dp, vertical = 20.dp)
            ) {
                // Large detected letter
                Text(
                    text       = detectedLetter,
                    fontSize   = 96.sp,
                    fontWeight = FontWeight.Black,
                    color      = Color.White
                )
                // Confidence percentage below the letter
                Text(
                    text     = "${"%.0f".format(confidenceLevel * 100)}%",
                    fontSize = 18.sp,
                    color    = Color(0xCCFFFFFF)
                )
            }
        }
    }
}
