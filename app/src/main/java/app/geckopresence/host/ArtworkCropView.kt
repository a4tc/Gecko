package app.geckopresence.host

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Square crop viewport with pan and pinch gestures. The source bitmap is never mutated. */
class ArtworkCropView(context: Context, private val source: Bitmap) : View(context) {
    enum class Mode { FIT, FILL, ORIGINAL }

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val imageMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scale
            scale = (scale * detector.scaleFactor).coerceIn(minScale() * 0.65f, maxScale())
            val factor = scale / oldScale
            offsetX = detector.focusX - (detector.focusX - offsetX) * factor
            offsetY = detector.focusY - (detector.focusY - offsetY) * factor
            invalidate()
            return true
        }
    })
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scaling = false

    init {
        isFocusable = true
        contentDescription = "Artwork crop area. Drag to move the image and pinch to zoom."
    }

    private fun cropRect(): RectF {
        val side = min(width.toFloat(), height.toFloat()) * 0.78f
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        return RectF(left, top, left + side, top + side)
    }

    private fun minScale(): Float {
        if (source.width <= 0 || source.height <= 0) return 1f
        val crop = cropRect()
        return max(crop.width() / source.width, crop.height() / source.height)
    }

    private fun maxScale(): Float = max(minScale() * 8f, 8f)

    fun setMode(value: Mode) {
        val crop = cropRect()
        val sx = crop.width() / source.width
        val sy = crop.height() / source.height
        scale = when (value) {
            Mode.FILL -> max(sx, sy)
            Mode.FIT -> min(sx, sy)
            Mode.ORIGINAL -> 1f
        }.coerceIn(0.05f, maxScale())
        offsetX = (width - source.width * scale) / 2f
        offsetY = (height - source.height * scale) / 2f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setMode(Mode.FILL)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF101318.toInt())
        imageMatrix.reset()
        imageMatrix.setScale(scale, scale)
        imageMatrix.postTranslate(offsetX, offsetY)
        canvas.drawBitmap(source, imageMatrix, imagePaint)
        val crop = cropRect()
        canvas.drawRect(0f, 0f, width.toFloat(), crop.top, overlayPaint)
        canvas.drawRect(0f, crop.bottom, width.toFloat(), height.toFloat(), overlayPaint)
        canvas.drawRect(0f, crop.top, crop.left, crop.bottom, overlayPaint)
        canvas.drawRect(crop.right, crop.top, width.toFloat(), crop.bottom, overlayPaint)
        canvas.drawLine(crop.left + crop.width() / 3f, crop.top, crop.left + crop.width() / 3f, crop.bottom, gridPaint)
        canvas.drawLine(crop.left + crop.width() * 2f / 3f, crop.top, crop.left + crop.width() * 2f / 3f, crop.bottom, gridPaint)
        canvas.drawLine(crop.left, crop.top + crop.height() / 3f, crop.right, crop.top + crop.height() / 3f, gridPaint)
        canvas.drawLine(crop.left, crop.top + crop.height() * 2f / 3f, crop.right, crop.top + crop.height() * 2f / 3f, gridPaint)
        canvas.drawRect(crop, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                scaling = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> scaling = true
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1 && !scaling) {
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> scaling = false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scaling = false
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun cropBitmap(): Bitmap {
        check(width > 0 && height > 0 && source.width > 0 && source.height > 0) { "Crop area is not ready" }
        val crop = cropRect()
        val requestedSide = (crop.width() / scale).toInt().coerceAtLeast(1)
        val side = min(requestedSide, min(source.width, source.height)).coerceAtLeast(1)
        val centerX = (crop.centerX() - offsetX) / scale
        val centerY = (crop.centerY() - offsetY) / scale
        val left = (centerX - side / 2f).toInt().coerceIn(0, source.width - side)
        val top = (centerY - side / 2f).toInt().coerceIn(0, source.height - side)
        val cropped = Bitmap.createBitmap(source, left, top, side, side)
        return if (cropped === source) {
            source.copy(Bitmap.Config.ARGB_8888, true) ?: error("Could not copy the source bitmap")
        } else cropped
    }
}
