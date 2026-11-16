package app.anihome.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Wallpaper framing for both orientations. The frame has this device's exact screen shape
 * (portrait, or the same screen turned sideways); the picture moves and zooms under it. Each
 * orientation remembers its own position and zoom, so the two can show different areas.
 */
internal class WallpaperCropView(context: Context, private val screenShort: Int, private val screenLong: Int) : View(context) {
    enum class Orientation { PORTRAIT, LANDSCAPE }

    /** Position as the frame centre in image fractions, plus zoom over the smallest fitting scale. */
    private class Framing(var cx: Float = .5f, var cy: Float = .5f, var zoom: Float = 1f)

    private var bitmap: Bitmap? = null
    private val framings = mutableMapOf(Orientation.PORTRAIT to Framing(), Orientation.LANDSCAPE to Framing())
    var orientation = Orientation.PORTRAIT
        set(value) { field = value; layoutFrame(); invalidate() }
    private val frame = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0xB3000000.toInt() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
    private var lastX = 0f
    private var lastY = 0f

    private val current get() = framings.getValue(orientation)
    private fun aspect(o: Orientation) = if (o == Orientation.PORTRAIT) screenShort.toFloat() / screenLong else screenLong.toFloat() / screenShort
    private fun minScale(image: Bitmap) = maxOf(frame.width() / image.width, frame.height() / image.height)
    private fun scale(image: Bitmap) = minScale(image) * current.zoom

    private val zoomDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val image = bitmap ?: return true
            val old = scale(image)
            current.zoom = (current.zoom * detector.scaleFactor).coerceIn(1f, 6f)
            val now = scale(image)
            // Keep the point under the fingers still while zooming.
            val focusImageX = (detector.focusX - left(image, old)) / old
            val focusImageY = (detector.focusY - top(image, old)) / old
            val newLeft = detector.focusX - focusImageX * now
            val newTop = detector.focusY - focusImageY * now
            current.cx = (frame.centerX() - newLeft) / (image.width * now)
            current.cy = (frame.centerY() - newTop) / (image.height * now)
            constrain(image); invalidate(); return true
        }
    })

    fun setImage(image: Bitmap) {
        bitmap?.takeIf { it !== image }?.recycle()
        bitmap = image
        framings.values.forEach { it.cx = .5f; it.cy = .5f; it.zoom = 1f }
        invalidate()
    }
    fun reset() { current.cx = .5f; current.cy = .5f; current.zoom = 1f; invalidate() }

    /** The chosen area in image pixels for one orientation. */
    fun crop(o: Orientation): Rect? {
        val image = bitmap ?: return null
        val a = aspect(o)
        val f = framings.getValue(o)
        // The visible window in image pixels: the largest [a]-shaped area, divided by zoom.
        var w = image.width.toFloat(); var h = w / a
        if (h > image.height) { h = image.height.toFloat(); w = h * a }
        w /= f.zoom; h /= f.zoom
        val l = (f.cx * image.width - w / 2f).coerceIn(0f, image.width - w)
        val t = (f.cy * image.height - h / 2f).coerceIn(0f, image.height - h)
        return Rect(l.toInt(), t.toInt(), (l + w).toInt().coerceAtMost(image.width), (t + h).toInt().coerceAtMost(image.height))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutFrame() }

    private fun layoutFrame() {
        if (width == 0 || height == 0) return
        val a = aspect(orientation)
        val margin = 24f * resources.displayMetrics.density
        val availW = width - margin * 2; val availH = height - margin * 2
        var fw = availW; var fh = fw / a
        if (fh > availH) { fh = availH; fw = fh * a }
        frame.set((width - fw) / 2f, (height - fh) / 2f, (width + fw) / 2f, (height + fh) / 2f)
        bitmap?.let(::constrain)
    }

    private fun left(image: Bitmap, s: Float) = frame.centerX() - current.cx * image.width * s
    private fun top(image: Bitmap, s: Float) = frame.centerY() - current.cy * image.height * s

    private fun constrain(image: Bitmap) {
        val s = scale(image)
        val halfW = frame.width() / 2f / (image.width * s); val halfH = frame.height() / 2f / (image.height * s)
        current.cx = current.cx.coerceIn(halfW, 1f - halfW)
        current.cy = current.cy.coerceIn(halfH, 1f - halfH)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xff101218.toInt())
        val image = bitmap ?: return
        val s = scale(image)
        val l = left(image, s); val t = top(image, s)
        // The whole picture stays visible, dimmed outside the screen frame, so you can see what to move toward.
        canvas.drawBitmap(image, null, RectF(l, t, l + image.width * s, t + image.height * s), paint)
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, shade)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, shade)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, shade)
        val r = 18f * resources.displayMetrics.density * (frame.width() / maxOf(screenShort, 1) * 2f).coerceIn(.3f, 1f)
        canvas.drawRoundRect(frame, r, r, outline)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return true
        zoomDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!zoomDetector.isInProgress && event.pointerCount == 1) {
                    val s = scale(image)
                    // Dragging moves the picture; the frame (the screen) stays put.
                    current.cx -= (event.x - lastX) / (image.width * s)
                    current.cy -= (event.y - lastY) / (image.height * s)
                    constrain(image); invalidate()
                }
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
}
