package com.vcam.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vcam.R
import com.vcam.service.FloatWindowService
import com.vcam.utils.MediaSlotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SLOT = "preview_slot"
    }

    private lateinit var imageView: ImageView
    private lateinit var tvSlotLabel: TextView
    private lateinit var tvZoom: TextView
    private lateinit var btnPlus: View
    private lateinit var btnMinus: View
    private lateinit var btnClose: View

    private var slot = 1
    private val matrix = Matrix()
    private var scaleFactor = 1f
    private val minScale = 0.3f
    private val maxScale = 10f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var baseScale = 1f
    private var panX = 0
    private var panY = 0

    private lateinit var scaleDetector: ScaleGestureDetector

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            (resources.displayMetrics.heightPixels * 0.80).toInt()
        )

        imageView   = findViewById(R.id.iv_preview_image)
        tvSlotLabel = findViewById(R.id.tv_preview_slot_label)
        tvZoom      = findViewById(R.id.tv_preview_zoom)
        btnPlus     = findViewById(R.id.btn_preview_zoom_in)
        btnMinus    = findViewById(R.id.btn_preview_zoom_out)
        btnClose    = findViewById(R.id.btn_preview_close)

        slot = intent.getIntExtra(EXTRA_SLOT, 1)
        val isVideo = MediaSlotManager.isSlotVideo(this, slot)
        tvSlotLabel.text = if (isVideo) "\ud83c\udfac \u0641\u064a\u062f\u064a\u0648" else "\ud83d\udcf7 \u0635\u0648\u0631\u0629 $slot"

        btnClose.setOnClickListener { finish() }

        btnPlus.setOnClickListener  { applyZoom(0.25f) }
        btnMinus.setOnClickListener { applyZoom(-0.25f) }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
                val ds = newScale / scaleFactor
                matrix.postScale(ds, ds, detector.focusX, detector.focusY)
                scaleFactor = newScale
                imageView.imageMatrix = matrix
                updateZoomLabel()
                broadcastTransform()
                return true
            }
        })

        imageView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX; lastY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        if (!isDragging && (Math.abs(dx) > 4 || Math.abs(dy) > 4)) isDragging = true
                        if (isDragging) {
                            matrix.postTranslate(dx, dy)
                            imageView.imageMatrix = matrix
                            lastX = event.rawX; lastY = event.rawY
                            panX += dx.toInt()
                            panY += dy.toInt()
                            broadcastTransform()
                        }
                    }
                }
            }
            true
        }

        CoroutineScope(Dispatchers.Main).launch {
            val bmp = withContext(Dispatchers.IO) { MediaSlotManager.getThumbnail(this@PreviewActivity, slot) }
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
                imageView.scaleType = ImageView.ScaleType.MATRIX
                centerImage(bmp)
            } else {
                tvSlotLabel.text = "\u0644\u0627 \u062a\u0648\u062c\u062f \u0635\u0648\u0631\u0629 \u0641\u064a \u0647\u0630\u0627 \u0627\u0644\u062d\u0642\u0644"
            }
        }
    }

    private fun centerImage(bmp: Bitmap) {
        imageView.post {
            val vw = imageView.width.toFloat()
            val vh = imageView.height.toFloat()
            val scale = minOf(vw / bmp.width, vh / bmp.height)
            baseScale = scale
            scaleFactor = scale
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate((vw - bmp.width * scale) / 2f, (vh - bmp.height * scale) / 2f)
            imageView.imageMatrix = matrix
            updateZoomLabel()
        }
    }

    private fun applyZoom(delta: Float) {
        val newScale = (scaleFactor + delta).coerceIn(minScale, maxScale)
        val ds = newScale / scaleFactor
        val cx = imageView.width / 2f
        val cy = imageView.height / 2f
        matrix.postScale(ds, ds, cx, cy)
        scaleFactor = newScale
        imageView.imageMatrix = matrix
        updateZoomLabel()
        broadcastTransform()
    }

    private fun updateZoomLabel() {
        tvZoom.text = "${(scaleFactor / baseScale * 100).toInt()}%"
    }

    private fun broadcastTransform() {
        val zoom = (scaleFactor / baseScale).coerceIn(1f, 5f)
        sendBroadcast(Intent(FloatWindowService.ACTION_ZOOM_IN)
            .putExtra(FloatWindowService.EXTRA_ZOOM_FACTOR, zoom))
        sendBroadcast(Intent(FloatWindowService.ACTION_PAN_RIGHT)
            .putExtra(FloatWindowService.EXTRA_PAN_X, panX)
            .putExtra(FloatWindowService.EXTRA_PAN_Y, panY))
    }
}
