// ─── TASK-006: CameraManager — front camera bind, YUV→JPEG conversion, frame gate ───
// Changes:
//   • FIXED: Camera preview visibility and lifecycle binding order.
//   • Added explicit LOGGING in processFrame() as per Debugging Checklist.
//   • Robust camera selection for emulators (fallback to first available camera).
//   • Mode: PERFORMANCE (SurfaceView) for maximum emulator compatibility.
// ──────────────────────────────────────────────────────────────────────────────

package com.runanywhere.kotlin_starter_example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
        private const val JPEG_QUALITY   = 82
        private const val TARGET_WIDTH   = 640
        private const val TARGET_HEIGHT  = 480
    }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detectionRunning = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null

    fun frameStream(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ): Flow<SignLanguageFrame> = callbackFlow {

        Log.d(TAG, "Initializing frameStream flow")
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                // ── Preview setup ──────────────────────────────────────────────
                previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                
                val preview = Preview.Builder().build()
                
                // CRITICAL: Set surface provider BEFORE binding
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // ── ImageAnalysis setup ────────────────────────────────────────
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(TARGET_WIDTH, TARGET_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy) { frame ->
                        if (!trySend(frame).isSuccess) {
                            detectionRunning.set(false)
                        }
                    }
                }

                val cameraSelector = selectBestCamera(provider)
                
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                
                Log.i(TAG, "Camera bound successfully (Preview + ImageAnalysis)")

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
                    val available = provider.availableCameraInfos
                    if (available.isNotEmpty()) available.first().cameraSelector else front
                }
            }
        } catch (e: Exception) {
            front
        }
    }

    fun releaseFrame() {
        Log.v(TAG, "Frame gate reopened")
        detectionRunning.set(false)
    }

    fun release() {
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    private fun processFrame(imageProxy: ImageProxy, onFrame: (SignLanguageFrame) -> Unit) {
        if (!detectionRunning.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            Log.v(TAG, "Frame received — calling detector")
            val rotation = imageProxy.imageInfo.rotationDegrees
            val nv21 = yuv420ToNv21(imageProxy)

            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )
            val jpegStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height),
                JPEG_QUALITY,
                jpegStream
            )
            val rawJpeg = jpegStream.toByteArray()
            val uprightJpeg = if (rotation != 0) rotateJpeg(rawJpeg, rotation) else rawJpeg

            onFrame(SignLanguageFrame(
                jpegBytes = uprightJpeg,
                width     = imageProxy.width,
                height    = imageProxy.height,
                rotation  = rotation
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
            detectionRunning.set(false)
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
