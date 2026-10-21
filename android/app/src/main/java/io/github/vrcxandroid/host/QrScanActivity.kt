package io.github.vrcxandroid.host

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import io.github.vrcxandroid.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * In-app QR scanner, used when the Google code scanner cannot run ([QrScanner]): a CameraX preview whose analysis
 * frames go to [QrFrameDecoder]. Answers RESULT_OK with [EXTRA_VALUE] for the first QR code found, RESULT_CANCELED when
 * the user backs out, or [RESULT_NO_CAMERA] when no camera can be opened. The caller holds the camera permission.
 */
class QrScanActivity : ComponentActivity() {
    private lateinit var analysisExecutor: ExecutorService
    private val done = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        analysisExecutor = Executors.newSingleThreadExecutor()

        val density = resources.displayMetrics.density
        val gutter = (16 * density).toInt()
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val cancel = TextView(this).apply {
            setText(R.string.qr_scan_cancel)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(gutter, gutter / 2, gutter, gutter / 2)
            minHeight = (48 * density).toInt()
            gravity = Gravity.CENTER_VERTICAL
            val ripple = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) setBackgroundResource(ripple.resourceId)
            setOnClickListener { finishWith(RESULT_CANCELED, null) }
        }
        val hint = TextView(this).apply {
            setText(R.string.qr_scan_hint)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(gutter, gutter, gutter, gutter)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(ViewfinderView(context), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(cancel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
            addView(hint, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            cancel.updateLayoutParams<FrameLayout.LayoutParams> { setMargins(bars.left, bars.top, bars.right, 0) }
            hint.updateLayoutParams<FrameLayout.LayoutParams> { setMargins(bars.left, 0, bars.right, bars.bottom + gutter) }
            insets
        }
        startCamera(preview)
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera(preview: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                val selector = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                    .firstOrNull { provider.hasCamera(it) } ?: throw IllegalStateException("no camera")
                val previewCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, previewCase, analysis)
            } catch (e: Exception) {
                Log.w(TAG, "camera unavailable", e)
                finishWith(RESULT_NO_CAMERA, null)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Analysis thread.
    private fun analyze(image: ImageProxy) {
        image.use {
            if (done.get()) return
            val plane = it.planes[0]
            val buffer = plane.buffer
            val luma = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
            val value = QrFrameDecoder.decode(luma, it.width, it.height, plane.rowStride) ?: return
            runOnUiThread { finishWith(RESULT_OK, value) }
        }
    }

    private fun finishWith(resultCode: Int, value: String?) {
        if (!done.compareAndSet(false, true) || isFinishing) return
        setResult(resultCode, value?.let { Intent().putExtra(EXTRA_VALUE, it) })
        finish()
    }

    /** Dims the preview outside a centred square and outlines the square. */
    private class ViewfinderView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val scrim = Paint().apply { color = 0x99000000.toInt() }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = Color.WHITE
        }
        private val box = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val side = min(width, height) * 0.7f
            box.set((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
            val radius = 16 * density
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            path.addRoundRect(box, radius, radius, Path.Direction.CW)
            canvas.drawPath(path, scrim)
            canvas.drawRoundRect(box, radius, radius, outline)
        }
    }

    companion object {
        private const val TAG = "VRCXQr"
        const val EXTRA_VALUE = "value"
        const val RESULT_NO_CAMERA = RESULT_FIRST_USER
    }
}
