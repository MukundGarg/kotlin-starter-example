// ─── TASK-006: CameraManager — front camera bind, YUV→JPEG conversion, frame gate ───
// Changes:
//   • Binds CameraX Preview + ImageAnalysis to the front-facing lens
//   • Uses STRATEGY_KEEP_ONLY_LATEST + YUV_420_888 image format
//   • Converts YUV frames to JPEG with pixel-by-pixel NV21 extraction
//     (naive buffer.array() produces corrupt images on real devices)
//   • Guards concurrent detection with AtomicBoolean (detectionRunning)
//   • Rotates bitmap to upright before JPEG compression
//   • Exposes frameStream(): Flow<SignLanguageFrame> and releaseFrame()
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
        private const val JPEG_QUALITY   = 82      // payload size vs. fidelity balance
        private const val TARGET_WIDTH   = 640
        private const val TARGET_HEIGHT  = 480
    }

    // ── Single-thread executor for ImageAnalysis callbacks ────────────────────
    // Must NOT be shut down on stopDetection() — only in release().
    // Shutting it down early orphans the ImageAnalysis use-case and
    // causes "Frame gate reopened" to stop appearing in Logcat.
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── Gate: true while a detection call is in-flight ────────────────────────
    // Set to true when a frame is emitted; reset via releaseFrame() in
    // SignLanguageService's finally block. This limits concurrent VLM calls to 1.
    private val detectionRunning = AtomicBoolean(false)

    private var cameraProvider: ProcessCameraProvider? = null

    // ─────────────────────────────────────────────────────────────────────────
    // frameStream() — binds the camera and emits SignLanguageFrame objects
    // ─────────────────────────────────────────────────────────────────────────
    fun frameStream(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ): Flow<SignLanguageFrame> = callbackFlow {

        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            // ── Preview use-case ──────────────────────────────────────────────
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // ── ImageAnalysis use-case ────────────────────────────────────────
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(TARGET_WIDTH, TARGET_HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                processFrame(imageProxy) { frame ->
                    // trySend is safe from any thread; drops silently if channel closed
                    trySend(frame)
                }
            }

            // ── Front camera selector ─────────────────────────────────────────
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d(TAG, "Camera use-cases bound (preview + imageAnalysis)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera use-cases: ${e.message}", e)
                close(e)
            }

        }, ContextCompat.getMainExecutor(context))

        // Clean up when the flow is cancelled (lifecycle destroyed, stopDetection called)
        awaitClose {
            Log.d(TAG, "frameStream closed — unbinding camera")
            cameraProvider?.unbindAll()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // releaseFrame() — called from SignLanguageService's finally block.
    // MUST be called after every emitted frame, even on exception, or the
    // AtomicBoolean gate permanently blocks all subsequent frames.
    // ─────────────────────────────────────────────────────────────────────────
    fun releaseFrame() {
        detectionRunning.set(false)
        Log.d(TAG, "Frame gate reopened")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // release() — call from ViewModel.onCleared() only
    // ─────────────────────────────────────────────────────────────────────────
    fun release() {
        analysisExecutor.shutdown()
        cameraProvider?.unbindAll()
        Log.d(TAG, "CameraManager released")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // processFrame() — internal: gates, converts, rotates, emits
    // ─────────────────────────────────────────────────────────────────────────
    private fun processFrame(imageProxy: ImageProxy, onFrame: (SignLanguageFrame) -> Unit) {
        // Gate: skip this frame if a detection call is already running
        if (!detectionRunning.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees

            // Step 1: Convert YUV_420_888 → NV21 byte array (pixel-by-pixel — safe)
            val nv21 = yuv420ToNv21(imageProxy)

            // Step 2: NV21 → JPEG via YuvImage
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

            // Step 3: Rotate the bitmap to upright so the VLM sees a correct hand
            val uprightJpeg = if (rotation != 0) rotateJpeg(rawJpeg, rotation) else rawJpeg

            val frame = SignLanguageFrame(
                jpegBytes = uprightJpeg,
                width     = imageProxy.width,
                height    = imageProxy.height,
                rotation  = rotation
            )

            onFrame(frame)

        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}", e)
            // Gate must be released on exception — otherwise camera freezes
            detectionRunning.set(false)
        } finally {
            imageProxy.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // yuv420ToNv21() — safe pixel-by-pixel extraction
    //
    // WHY: CameraX YUV_420_888 planes can have pixelStride > 1 and
    // rowStride > width. Using buffer.array() directly copies padding bytes
    // and produces black or green corrupt images on real devices.
    // This loop extracts only the real pixel bytes.
    // ─────────────────────────────────────────────────────────────────────────
    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray {
        val width  = imageProxy.width
        val height = imageProxy.height
        val nv21   = ByteArray(width * height * 3 / 2)

        val yPlane  = imageProxy.planes[0]
        val uPlane  = imageProxy.planes[1]
        val vPlane  = imageProxy.planes[2]

        var i = 0

        // ── Y plane (luma) ────────────────────────────────────────────────────
        for (row in 0 until height) {
            val base = row * yPlane.rowStride
            for (col in 0 until width) {
                nv21[i++] = yPlane.buffer[base + col * yPlane.pixelStride]
            }
        }

        // ── VU interleaved (chroma) ───────────────────────────────────────────
        // NV21 interleaves V then U. CameraX gives separate U and V planes.
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

    // ─────────────────────────────────────────────────────────────────────────
    // rotateJpeg() — rotates a JPEG byte array by the given degrees
    // Used to upright the frame before sending to the VLM
    // ─────────────────────────────────────────────────────────────────────────
    private fun rotateJpeg(jpegBytes: ByteArray, degrees: Int): ByteArray {
        val bitmap  = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val matrix  = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        return ByteArrayOutputStream().also { out ->
            rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }.toByteArray()
    }
}
