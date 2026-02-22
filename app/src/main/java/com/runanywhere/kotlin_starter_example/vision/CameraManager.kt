// ─── TASK-006: CameraManager — Optimized for Lag & Detection Triggering ────────
// Changes:
//   • FIXED: Camera preview not showing on some devices/emulators.
//   • FIXED: Camera selection logic to be extremely resilient (falls back to any camera).
//   • OPTIMIZED: Uses ResolutionSelector (CameraX 1.3+) for better resolution matching.
//   • MODE: Switched to COMPATIBLE (TextureView) for better layering with Compose.
//   • DEBUGGING: Added detailed logging for binding and frame acquisition.
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraManager(private val context: Context) {

    companion object {
        private const val TAG            = "CameraManager"
        private const val JPEG_QUALITY   = 70
        
        // Target resolutions for a balance of speed and accuracy
        private const val TARGET_WIDTH   = 480
        private const val TARGET_HEIGHT  = 360
        private const val EMULATOR_WIDTH  = 320
        private const val EMULATOR_HEIGHT = 240
    }

    private val isEmulator = Build.FINGERPRINT.contains("generic") ||
            Build.FINGERPRINT.contains("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            "google_sdk" == Build.PRODUCT

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isProcessingFrame = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null

    fun frameStream(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ): Flow<SignLanguageFrame> = callbackFlow {

        Log.d(TAG, "Initializing frameStream flow. Emulator: $isEmulator")
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                // 1. Resolution Strategy
                val resWidth = if (isEmulator) EMULATOR_WIDTH else TARGET_WIDTH
                val resHeight = if (isEmulator) EMULATOR_HEIGHT else TARGET_HEIGHT
                
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(resWidth, resHeight),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                // 2. Preview setup (COMPATIBLE mode is more stable for Compose overlays)
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()

                // 3. ImageAnalysis setup
                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy) { frame ->
                        if (!trySend(frame).isSuccess) {
                            releaseFrame()
                        }
                    }
                }

                // 4. Robust Camera Selection
                val cameraSelector = selectBestCamera(provider)
                
                provider.unbindAll()
                
                // Bind to lifecycle
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                
                // CRITICAL: Set surface provider AFTER binding to ensure the camera is ready
                preview.setSurfaceProvider(previewView.surfaceProvider)
                
                Log.i(TAG, "Camera bound successfully. Resolution: ${resWidth}x${resHeight}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera: ${e.message}", e)
                close(e)
            }

        }, ContextCompat.getMainExecutor(context))

        awaitClose {
            Log.d(TAG, "frameStream flow closed — unbinding camera")
            cameraProvider?.unbindAll()
        }
    }

    private fun selectBestCamera(provider: ProcessCameraProvider): CameraSelector {
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        val back = CameraSelector.DEFAULT_BACK_CAMERA
        
        return try {
            when {
                provider.hasCamera(front) -> front
                provider.hasCamera(back) -> back
                else -> {
                    // Fallback: Pick the first available camera
                    val available = provider.availableCameraInfos
                    if (available.isNotEmpty()) {
                        Log.w(TAG, "No front/back camera detected. Falling back to first available.")
                        available.first().cameraSelector
                    } else {
                        Log.e(TAG, "No cameras found at all!")
                        front // Last resort
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting camera: ${e.message}")
            front
        }
    }

    fun releaseFrame() {
        isProcessingFrame.set(false)
    }

    fun release() {
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    private fun processFrame(imageProxy: ImageProxy, onFrame: (SignLanguageFrame) -> Unit) {
        if (!isProcessingFrame.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val nv21 = yuv420ToNv21(imageProxy)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val jpegStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), JPEG_QUALITY, jpegStream)
            
            val rawJpeg = jpegStream.toByteArray()
            val finalJpeg = if (rotation != 0) rotateJpeg(rawJpeg, rotation) else rawJpeg

            onFrame(SignLanguageFrame(
                jpegBytes = finalJpeg,
                width     = imageProxy.width,
                height    = imageProxy.height,
                rotation  = rotation
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
            isProcessingFrame.set(false)
        } finally {
            imageProxy.close()
        }
    }

    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val width  = imageProxy.width
        val height = imageProxy.height
        val nv21   = ByteArray(width * height * 3 / 2)
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        var i = 0
        for (row in 0 until height) {
            val base = row * yPlane.rowStride
            for (col in 0 until width) {
                nv21[i++] = yPlane.buffer[base + col * yPlane.pixelStride]
            }
        }

        val chromaHeight = height / 2
        val chromaWidth  = width  / 2
        for (row in 0 until chromaHeight) {
            val vBase = row * vPlane.rowStride
            val uBase = row * uPlane.rowStride
            for (col in 0 until chromaWidth) {
                nv21[i++] = vPlane.buffer[vBase + col * vPlane.pixelStride]
                nv21[i++] = uPlane.buffer[uBase + col * uPlane.pixelStride]
            }
        }
        return nv21
    }

    private fun rotateJpeg(jpegBytes: ByteArray, degrees: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }
}
