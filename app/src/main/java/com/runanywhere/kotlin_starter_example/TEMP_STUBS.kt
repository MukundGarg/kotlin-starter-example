// TEMP_STUBS.kt — delete this file after TASK-010 and TASK-011 are complete

package com.runanywhere.kotlin_starter_example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel

class SignLanguageViewModel : ViewModel()

@Composable
fun SignLanguageScreen(viewModel: SignLanguageViewModel, isSdkInitialised: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(if (isSdkInitialised) "SDK Ready ✅ — Camera active" else "SDK offline mode ⚠️")
    }
}
