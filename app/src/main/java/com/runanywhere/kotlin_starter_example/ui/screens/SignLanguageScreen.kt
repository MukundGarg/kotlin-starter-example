package com.runanywhere.kotlin_starter_example.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.runanywhere.kotlin_starter_example.SignLanguageViewModel

@Composable
fun SignLanguageScreen(
    viewModel: SignLanguageViewModel,
    isSdkInitialised: Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
    }

    LaunchedEffect(isSdkInitialised, lifecycleOwner, previewView) {
        if (isSdkInitialised) {
            viewModel.bindCamera(lifecycleOwner, previewView)
        }
    }
}
