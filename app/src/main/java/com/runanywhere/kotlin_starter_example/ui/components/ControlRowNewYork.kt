// ─── TASK-011: ControlRowNewYork — Start / Stop / Reset / Confirm buttons ──────

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
    onStart        : () -> Unit,
    onStop         : () -> Unit,
    onReset        : () -> Unit,
    onConfirm      : () -> Unit,
    modifier       : Modifier = Modifier
) {
    val isDetecting = detectionState is DetectionState.Detecting ||
                      detectionState is DetectionState.Processing ||
                      detectionState is DetectionState.BufferingWord

    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Start / Stop (toggle) ──────────────────────────────────────────
        Button(
            onClick = if (isDetecting) onStop else onStart,
            colors  = ButtonDefaults.buttonColors(
                containerColor = if (isDetecting) Color(0xFFE53935) else Color(0xFF6C63FF)
            ),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text       = if (isDetecting) "Stop" else "Start",
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                color      = Color.White
            )
        }

        // ── Confirm word ───────────────────────────────────────────────────
        Button(
            onClick  = onConfirm,
            colors   = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF43A047)
            ),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text       = "Confirm",
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                color      = Color.White
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
