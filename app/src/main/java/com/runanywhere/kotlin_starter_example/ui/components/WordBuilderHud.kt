// ─── TASK-011: WordBuilderHud — current letter, current word, word history ─────

package com.runanywhere.kotlin_starter_example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WordBuilderHud(
    currentLetter : String,
    currentWord   : String,
    wordHistory   : List<String>,
    modifier      : Modifier = Modifier
) {
    Column(modifier = modifier) {

        // ── Current letter being held ──────────────────────────────────────
        Text(
            text       = if (currentLetter.isBlank()) "—" else currentLetter,
            fontSize   = 28.sp,
            fontWeight = FontWeight.Bold,
            color      = if (currentLetter.isBlank()) Color(0xFF555566) else Color(0xFF6C63FF)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Word being built ───────────────────────────────────────────────
        Text(
            text       = if (currentWord.isBlank()) "Start signing…" else currentWord,
            fontSize   = 20.sp,
            fontWeight = FontWeight.Medium,
            color      = if (currentWord.isBlank()) Color(0xFF555566) else Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Confirmed word history (horizontal scroll) ─────────────────────
        if (wordHistory.isNotEmpty()) {
            Text(
                text     = "History",
                fontSize = 11.sp,
                color    = Color(0xFF9090A8)
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(wordHistory) { word ->
                    Text(
                        text     = word,
                        fontSize = 15.sp,
                        color    = Color(0xFFB0B0D0)
                    )
                }
            }
        }
    }
}
