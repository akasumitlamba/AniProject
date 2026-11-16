package app.anihome.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * The whole picture, with two frames on it at once: this screen held upright (portrait) and
 * turned sideways (landscape), both at the screen's exact shape. Touch a frame to pick it,
 * drag to move it and pinch to make it smaller (zoom in) or bigger (zoom out). Each frame
 * is the area shown in that orientation.
 */
internal class WallpaperCropView(context: Context, private val screenShort: Int, private val screenLong: Int) : View(context) {
    enum class Orientation { PORTRAIT, LANDSCAPE }

    /** A frame: centre in image pixels and zoom (1 = the largest frame that fits the picture). */
    private class Frame(var cx: Float = 0f, var cy: Float = 0f, var zoom: Float = 1f)

    private var bitmap: Bitmap? = null
    private val frames = mapOf(Orientation.PORTRAIT to Frame(), Orientation.LANDSCAPE to Frame())
    /** The frame being adjusted. */
    var orientation = Orientation.PORTRAIT
        set(value) { field = value; onSelect?.invoke(value); invalidate() }
    var onSelect: ((Orientation) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val imageRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * resources.displayMetrics.scaledDensity; color = 0xFF0B1220.toInt(); isFakeBoldText = true }
    private var lastX = 0f
    private var lastY = 0f

    private fun aspect(o: Orientation) = if (o == Orientation.PORTRAIT) screenShort.toFloat() / screenLong else screenLong.toFloat() / screenShort
    private fun color(o: Orientation) = if (o == Orientation.PORTRAIT) PORTRAIT_COLOR else LANDSCAPE_COLOR

    /** Largest frame of this shape inside the picture, in image pixels. */
    private fun maxSize(image: Bitmap, o: Orientation): Pair<Float, Float> {
        val a = aspect(o)
        var w = image.width.toFloat(); var h = w / a
        if (h > image.height) { h = image.height.toFloat(); w = h * a }
        return w to h
    }

    /** A frame's area in image pixels, kept inside the picture. */
    private fun area(image: Bitmap, o: Orientation): RectF {
        val f = frames.getValue(o)
        val (mw, mh) = maxSize(image, o)
        val w = mw / f.zoom; val h = mh / f.zoom
        f.cx = f.cx.coerceIn(w / 2f, image.width - w / 2f); f.cy = f.cy.coerceIn(h / 2f, image.height - h / 2f)
        return RectF(f.cx - w / 2f, f.cy - h / 2f, f.cx + w / 2f, f.cy + h / 2f)
    }
    private fun toView(r: RectF): RectF {
        val image = bitmap ?: return RectF()
        val k = imageRect.width() / image.width
        return RectF(imageRect.left + r.left * k, imageRect.top + r.top * k, imageRect.left + r.right * k, imageRect.top + r.bottom * k)
    }

    private val zoomDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Fingers apart = a bigger frame (zoomed out); together = smaller (zoomed in).
            val f = frames.getValue(orientation)
            f.zoom = (f.zoom / detector.scaleFactor).coerceIn(1f, 6f); invalidate(); return true
        }
    })

    fun setImage(image: Bitmap) {
        bitmap?.takeIf { it !== image }?.recycle()
        bitmap = image
        frames.values.forEach { it.cx = image.width / 2f; it.cy = image.height / 2f; it.zoom = 1f }
        layoutImage(); invalidate()
    }
    fun reset() { bitmap?.let { b -> frames.getValue(orientation).apply { cx = b.width / 2f; cy = b.height / 2f; zoom = 1f } }; invalidate() }

    /** The chosen area in image pixels for one orientation. */
    fun crop(o: Orientation): Rect? {
        val image = bitmap ?: return null
        val r = area(image, o)
        return Rect(r.left.toInt().coerceAtLeast(0), r.top.toInt().coerceAtLeast(0), r.right.toInt().coerceAtMost(image.width), r.bottom.toInt().coerceAtMost(image.height))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutImage()
    private fun layoutImage() {
        val image = bitmap ?: return
        if (width == 0) return
        val margin = 12f * density
        val k = minOf((width - 2 * margin) / image.width, (height - 2 * margin) / image.height)
        val w = image.width * k; val h = image.height * k
        imageRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        canvas.drawBitmap(image, null, imageRect, paint)
        // Dim what neither orientation shows.
        val inactive = if (orientation == Orientation.PORTRAIT) Orientation.LANDSCAPE else Orientation.PORTRAIT
        val a = toView(area(image, orientation)); val b = toView(area(image, inactive))
        canvas.save()
        canvas.clipOutRect(a); canvas.clipOutRect(b)
        canvas.drawRect(imageRect, shade)
        canvas.restore()
        drawFrame(canvas, b, inactive, active = false)
        drawFrame(canvas, a, orientation, active = true)
    }

    private fun drawFrame(canvas: Canvas, r: RectF, o: Orientation, active: Boolean) {
        val radius = 10f * density
        stroke.color = color(o); stroke.strokeWidth = (if (active) 3f else 2f) * density
        stroke.pathEffect = if (active) null else DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
        stroke.alpha = if (active) 255 else 200
        canvas.drawRoundRect(r, radius, radius, stroke)
        // Label tab on the frame's top edge.
        val label = if (o == Orientation.PORTRAIT) "Portrait" else "Landscape"
        val tw = tagText.measureText(label) + 16f * density; val th = tagText.textSize + 10f * density
        val left = r.left + 8f * density; val top = r.top + 8f * density
        tag.color = color(o); tag.alpha = if (active) 255 else 190
        canvas.drawRoundRect(RectF(left, top, left + tw, top + th), th / 2, th / 2, tag)
        canvas.drawText(label, left + 8f * density, top + th / 2 - (tagText.descent() + tagText.ascent()) / 2, tagText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return true
        zoomDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true); lastX = event.x; lastY = event.y
                // Touching the other frame (and not the current one) selects it.
                val other = if (orientation == Orientation.PORTRAIT) Orientation.LANDSCAPE else Orientation.PORTRAIT
                if (!toView(area(image, orientation)).contains(event.x, event.y) && toView(area(image, other)).contains(event.x, event.y)) orientation = other
            }
            MotionEvent.ACTION_MOVE -> {
                if (!zoomDetector.isInProgress && event.pointerCount == 1) {
                    val k = imageRect.width() / image.width
                    frames.getValue(orientation).apply { cx += (event.x - lastX) / k; cy += (event.y - lastY) / k }
                    invalidate()
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

    companion object {
        const val PORTRAIT_COLOR = 0xFFA8C7FA.toInt()
        const val LANDSCAPE_COLOR = 0xFFFFB68A.toInt()
    }
}
