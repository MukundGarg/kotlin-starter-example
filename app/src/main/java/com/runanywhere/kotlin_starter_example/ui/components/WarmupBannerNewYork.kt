// ─── TASK-013: WarmupBannerNewYork — enhanced with classifier status display ──
// Changes:
//   • Shows animated shimmer pulse while warming up
//   • Displays one of three states: Warming Up / Ready / Offline (VLM fallback)
//   • Fades out automatically when isWarmingUp becomes false
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WarmupBannerNewYork(
    isWarmingUp       : Boolean,
    isClassifierReady : Boolean,
    isSdkInitialised  : Boolean,
    modifier          : Modifier = Modifier
) {
    // Animate banner in and out smoothly
    AnimatedVisibility(
        visible = isWarmingUp || (!isClassifierReady && !isSdkInitialised),
        enter   = fadeIn(animationSpec = tween(300)) +
                  slideInVertically(animationSpec = tween(300)) { -it },
        exit    = fadeOut(animationSpec = tween(600)) +
                  slideOutVertically(animationSpec = tween(600)) { -it },
        modifier = modifier
    ) {
        // Pulse animation for the progress indicator
        val infiniteTransition = rememberInfiniteTransition(label = "warmupPulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue   = 0.6f,
            targetValue    = 1.0f,
            animationSpec  = infiniteRepeatable(
                animation  = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bannerAlpha"
        )

        // Determine banner content based on state
        val (bgColor, icon, title, subtitle) = when {
            isWarmingUp -> BannerContent(
                bgColor  = Color(0xCC1A1A2E),
                icon     = null,          // spinner shown separately
                title    = "Warming up model…",
                subtitle = "Sign against a well-lit plain background for best results."
            )
            !isClassifierReady && isSdkInitialised -> BannerContent(
                bgColor  = Color(0xCC1A2E1A),
                icon     = "✅",
                title    = "VLM ready (online mode)",
                subtitle = "Local model unavailable — using RunAnywhere VLM."
            )
            else -> BannerContent(
                bgColor  = Color(0xCC2E1A1A),
                icon     = "⚠️",
                title    = "Offline — no model loaded",
                subtitle = "Neither local classifier nor VLM SDK is available."
            )
        }

        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .alpha(if (isWarmingUp) alpha else 1f)
                .background(color = bgColor, shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Spinner (warmup) or emoji icon (ready/error states)
            if (isWarmingUp) {
                CircularProgressIndicator(
                    color        = Color(0xFF6C63FF),
                    strokeWidth  = 2.dp,
                    modifier     = Modifier.size(20.dp)
                )
            } else if (icon != null) {
                Text(text = icon, fontSize = 20.sp)
            }

            Column {
                Text(
                    text       = title,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text     = subtitle,
                    fontSize = 11.sp,
                    color    = Color(0xFF9090A8)
                )
            }
        }
    }
}

// Helper data class to keep the when() block clean
private data class BannerContent(
    val bgColor  : Color,
    val icon     : String?,
    val title    : String,
    val subtitle : String
)
