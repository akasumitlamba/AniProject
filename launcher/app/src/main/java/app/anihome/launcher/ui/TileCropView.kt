package app.anihome.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** A 1.62:1 viewport with bounded pan and pinch zoom, exported at the same aspect ratio. */
class TileCropView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val frame = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f
    private var minimumScale = 1f
    private var x = 0f
    private var y = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val zoom = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale
            scale = (scale * detector.scaleFactor).coerceIn(minimumScale, minimumScale * 8f)
            val factor = scale / old
            x = detector.focusX - (detector.focusX - x) * factor
            y = detector.focusY - (detector.focusY - y) * factor
            constrain(); invalidate()
            return true
        }
    })

    fun setImage(image: Bitmap) { bitmap?.takeIf { it !== image }?.recycle(); bitmap = image; resetCrop() }
    fun resetCrop() {
        val image = bitmap ?: return
        if (frame.width() == 0f) return
        minimumScale = CropGeometry.minimumScale(frame.width(), frame.height(), image.width, image.height)
        scale = minimumScale
        x = frame.centerX() - image.width * scale / 2f
        y = frame.centerY() - image.height * scale / 2f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val width = minOf(w.toFloat() - 8f, (h - 8f) * CropGeometry.ASPECT).coerceAtLeast(1f)
        val height = width / CropGeometry.ASPECT
        frame.set((w - width) / 2f, (h - height) / 2f, (w + width) / 2f, (h + height) / 2f)
        resetCrop()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xff17191f.toInt())
        paint.color = Color.BLACK; paint.style = Paint.Style.FILL
        canvas.drawRoundRect(frame, 16f, 16f, paint)
        bitmap?.let { image ->
            canvas.save(); canvas.clipRect(frame)
            canvas.drawBitmap(image, null, RectF(x, y, x + image.width * scale, y + image.height * scale), paint)
            canvas.restore()
        }
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        canvas.drawRect(frame, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        zoom.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!zoom.isInProgress && event.pointerCount == 1) { x += event.x - lastX; y += event.y - lastY; constrain(); invalidate() }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(index); lastY = event.getY(index)
            }
            MotionEvent.ACTION_UP -> { parent.requestDisallowInterceptTouchEvent(false); performClick() }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private fun constrain() {
        val image = bitmap ?: return
        x = CropGeometry.boundedOffset(x, frame.left, frame.right, image.width * scale)
        y = CropGeometry.boundedOffset(y, frame.top, frame.bottom, image.height * scale)
    }

    fun exportBitmap(): Bitmap? {
        val image = bitmap ?: return null
        if (frame.width() <= 0f) return null
        val output = Bitmap.createBitmap(1296, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val factor = output.width / frame.width()
        canvas.drawBitmap(image, null, RectF((x - frame.left) * factor, (y - frame.top) * factor,
            (x + image.width * scale - frame.left) * factor, (y + image.height * scale - frame.top) * factor), paint)
        return output
    }
}
