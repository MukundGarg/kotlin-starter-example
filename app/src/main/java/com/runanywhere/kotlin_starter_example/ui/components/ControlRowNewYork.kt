// ─── TASK-013: Disable Start button during warmup ─────────────────────────────

package com.runanywhere.kotlin_starter_example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runanywhere.kotlin_starter_example.DetectionState

@Composable
fun ControlRowNewYork(
    detectionState : DetectionState,
    modifier       : Modifier = Modifier,
    isWarmingUp    : Boolean = false,
    onStart        : () -> Unit,
    onStop         : () -> Unit,
    onReset        : () -> Unit,
    onConfirm      : () -> Unit
) {
    val isDetecting = detectionState is DetectionState.Detecting  ||
                      detectionState is DetectionState.Processing ||
                      detectionState is DetectionState.BufferingWord

    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Start / Stop (disabled during warmup) ─────────────────────────
        Button(
            onClick  = if (isDetecting) onStop else onStart,
            enabled  = !isWarmingUp,   // ← grayed out while model loads
            colors   = ButtonDefaults.buttonColors(
                containerColor        = if (isDetecting) Color(0xFFE53935)
                                        else Color(0xFF6C63FF),
                disabledContainerColor = Color(0xFF3A3A4E)
            ),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text       = when {
                    isWarmingUp  -> "Loading…"
                    isDetecting  -> "Stop"
                    else         -> "Start"
                },
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                color      = if (isWarmingUp) Color(0xFF6A6A8A) else Color.White
            )
        }

        // ── Confirm word ───────────────────────────────────────────────────
        Button(
            onClick  = onConfirm,
            enabled  = !isWarmingUp,
            colors   = ButtonDefaults.buttonColors(
                containerColor         = Color(0xFF43A047),
                disabledContainerColor = Color(0xFF2A3A2A)
            ),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text       = "Confirm",
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                color      = if (isWarmingUp) Color(0xFF4A6A4A) else Color.White
            )
        }

        // ── Reset ──────────────────────────────────────────────────────────
        OutlinedButton(
            onClick  = onReset,
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text       = "Reset",
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                color      = Color(0xFFB0B0D0)
            )
        }
    }
}
