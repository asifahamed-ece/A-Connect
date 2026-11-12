package com.aplusconnect.companion.qr

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans the computer's pairing QR code.
 *
 * The camera is used only while this screen is open: the phone never listens
 * on the network for the computer, it dials out. That is why pairing is done
 * with a picture rather than with discovery.
 */
@Composable
fun QrScannerView(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = Executors.newSingleThreadExecutor()
    val handled = AtomicBoolean(false)

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext)
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()

                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(previewView.surfaceProvider)

                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysis.setAnalyzer(executor) { image ->
                            decode(image, handled, onScanned)
                        }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "could not start the camera: ${e.message}")
                    }
                }, ContextCompatMainExecutor(viewContext))

                previewView
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }
}

private fun decode(
    image: ImageProxy,
    handled: AtomicBoolean,
    onScanned: (String) -> Unit,
) {
    try {
        if (handled.get()) return

        val bitmap = image.toBitmapSafe() ?: return
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = ByteArray(width * height) { index ->
            val pixel = pixels[index]
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff
            (((r * 299 + g * 587 + b * 114) shr 10).toByte())
        }

        val source = PlanarYUVLuminanceSource(
            luminance, width, height, 0, 0, width, height, false
        )
        val result = reader.decode(BinaryBitmap(HybridBinarizer(source)))

        if (handled.compareAndSet(false, true)) {
            onScanned(result.text)
        }
    } catch (e: Exception) {
        // A frame that does not contain a code is the normal case.
    } finally {
        image.close()
    }
}

private val reader = MultiFormatReader().apply {
    val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    setHints(hints)
}

private fun ImageProxy.toBitmapSafe(): Bitmap? = try {
    toBitmap()
} catch (e: Exception) {
    null
}

/** A main thread executor without pulling in another dependency. */
private fun ContextCompatMainExecutor(context: android.content.Context): java.util.concurrent.Executor =
    androidx.core.content.ContextCompat.getMainExecutor(context)

private const val TAG = "AplusConnect"
