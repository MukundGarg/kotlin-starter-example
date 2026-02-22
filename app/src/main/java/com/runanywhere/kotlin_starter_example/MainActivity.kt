// ─── TASK-003: SDK initialisation added (RunAnywhere.initialize in onCreate) ───
// ─── TASK-004: Runtime camera permission flow wired via rememberLauncherForActivityResult ───

package com.runanywhere.kotlin_starter_example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runanywhere.kotlin_starter_example.ui.SignLanguageScreen
import com.runanywhere.sdk.core.onnx.ONNX
import com.runanywhere.sdk.foundation.bridge.extensions.CppBridgeModelPaths
import com.runanywhere.sdk.llm.llamacpp.LlamaCPP
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.SDKEnvironment
import com.runanywhere.sdk.storage.AndroidPlatformContext

class MainActivity : ComponentActivity() {

    // ── TASK-003: Track whether the RunAnywhere SDK initialised successfully ──
    private var isSdkInitialised = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── TASK-003: Initialise the RunAnywhere SDK before anything else ──────
        // This MUST happen before any call to RunAnywhere.describeImage().
        // A failed init degrades gracefully — camera still binds, VLM is skipped.
        initialiseSdk()

        setContent {
            MaterialTheme {
                ISLAppRoot(isSdkInitialised = isSdkInitialised)
            }
        }
    }

    // ── TASK-003: SDK init helper ─────────────────────────────────────────────
    private fun initialiseSdk() {
        try {
            // Initialize Android platform context FIRST
            AndroidPlatformContext.initialize(this)
            
            // Initialize RunAnywhere SDK
            RunAnywhere.initialize(environment = SDKEnvironment.DEVELOPMENT)
            
            // Set the base directory for model storage
            val runanywherePath = java.io.File(filesDir, "runanywhere").absolutePath
            CppBridgeModelPaths.setBaseDirectory(runanywherePath)
            
            // Register backends
            try {
                LlamaCPP.register(priority = 100)
            } catch (e: Throwable) {
                Log.w("MainActivity", "LlamaCPP registration partial failure: ${e.message}")
            }
            ONNX.register(priority = 100)

            isSdkInitialised = true
        } catch (e: Exception) {
            isSdkInitialised = false
            Toast.makeText(
                this,
                "VLM SDK failed to initialise — running in offline mode. Error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Root composable — owns permission state and gates the app on camera approval
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ISLAppRoot(isSdkInitialised: Boolean) {
    val context = LocalContext.current

    // ── TASK-004: Track permission state ──────────────────────────────────────
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showPermissionRationale by remember { mutableStateOf(false) }

    // ── TASK-004: Permission launcher ─────────────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (!granted) showPermissionRationale = true
    }

    // ── TASK-004: Request permission on first composition if not yet granted ──
    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ── Route to the correct screen based on permission state ─────────────────
    when {
        showPermissionRationale && !cameraPermissionGranted -> {
            PermissionRationaleScreen(
                onRetry = {
                    showPermissionRationale = false
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            )
        }

        cameraPermissionGranted -> {
            // ── Permission granted → show the main detection screen ───────────
            val viewModel: SignLanguageViewModel = viewModel()
            SignLanguageScreen(
                viewModel = viewModel,
                isSdkInitialised = isSdkInitialised
            )
        }

        else -> {
            // Waiting for the system dialog — show a neutral loading state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rationale screen — shown when the user denies camera permission
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PermissionRationaleScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(40.dp)
        ) {
            Text(
                text = "📷",
                fontSize = 64.sp
            )
            Text(
                text = "Camera Access Required",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = "This app detects Indian Sign Language letters in real time using your front camera. " +
                        "No video is recorded or stored — frames are only used for local detection.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0B0C8),
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6C63FF)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Camera Permission", color = Color.White)
            }
            Text(
                text = "If you previously denied and checked 'Don't ask again',\n" +
                        "go to Settings → Apps → ISL Detector → Permissions.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF888899),
                textAlign = TextAlign.Center
            )
        }
    }
}
