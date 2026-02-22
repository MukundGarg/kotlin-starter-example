// ─── TASK-011: WarmupBannerNewYork — shown while SDK/model is not yet ready ────

package com.runanywhere.kotlin_starter_example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WarmupBannerNewYork(modifier: Modifier = Modifier) {
    Row(
        modifier          = modifier
            .padding(horizontal = 24.dp)
            .background(
                color = Color(0xCC1A1A2E),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CircularProgressIndicator(
            color       = Color(0xFF6C63FF),
            strokeWidth = 2.dp,
            modifier    = Modifier.size(20.dp)
        )
        Column {
            Text(
                text       = "Warming up…",
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color      = Color.White
            )
            Text(
                text     = "Sign against a well-lit plain background for best results.",
                fontSize = 11.sp,
                color    = Color(0xFF9090A8)
            )
        }
    }
}
