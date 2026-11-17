package app.anihome.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The screen as a light: full brightness in any colour (a colour wheel plus quick presets),
 * with an SOS mode that flashes it. Controls fade away after a moment; tap to bring them back.
 */
class ScreenLightActivity : AppCompatActivity() {
    private lateinit var light: View
    private lateinit var controls: View
    private lateinit var sosButton: com.google.android.material.button.MaterialButton
    private var color = Color.WHITE
    private var sos: Runnable? = null
    private val hide = Runnable { controls.animate().alpha(0f).setDuration(250).withEndAction { controls.visibility = View.GONE }.start() }
    private fun dp(v: Int) = ArtworkUi.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        color = getSharedPreferences("anihome", Context.MODE_PRIVATE).getInt("screen_light_color", Color.WHITE)
        light = View(this).apply { setBackgroundColor(color); setOnClickListener { showControls() } }
        val wheel = ColorWheel(this) { picked -> setColor(picked) }
        val presets = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf("White" to Color.WHITE, "Warm" to 0xFFFFD8A8.toInt(), "Night red" to 0xFFFF2A1A.toInt()).forEach { (name, value) ->
            presets.addView(MaterialUi.tonal(this, name, 0) { setColor(value) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        sosButton = MaterialUi.tonal(this, "SOS", 0) { toggleSos() }
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(sosButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            addView(MaterialUi.filled(this@ScreenLightActivity, "Close", 0) { finish() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(20), dp(20), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xE61D2027.toInt()); cornerRadius = dp(28).toFloat() }
            addView(TextView(this@ScreenLightActivity).apply { text = "Screen light"; textSize = 18f; setTextColor(MaterialUi.ON_SURFACE) })
            addView(wheel, LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(12); bottomMargin = dp(12) })
            addView(presets, LinearLayout.LayoutParams(-1, -2))
            addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        setContentView(FrameLayout(this).apply {
            addView(light, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(dp(320), -2, Gravity.CENTER))
        })
        showControls()
    }

    private fun setColor(value: Int) {
        color = value
        if (sos == null) light.setBackgroundColor(value)
        getSharedPreferences("anihome", Context.MODE_PRIVATE).edit().putInt("screen_light_color", value).apply()
        showControls()
    }
    private fun toggleSos() {
        if (sos != null) { TorchTools.stopPattern(sos); sos = null; light.setBackgroundColor(color); sosButton.text = "SOS" }
        else { sos = TorchTools.runPattern { on -> light.setBackgroundColor(if (on) color else Color.BLACK) }; sosButton.text = "Stop SOS" }
        showControls()
    }
    private fun showControls() {
        controls.removeCallbacks(hide)
        controls.visibility = View.VISIBLE; controls.animate().alpha(1f).setDuration(150).start()
        controls.postDelayed(hide, 3500)
    }
    override fun onDestroy() { TorchTools.stopPattern(sos); sos = null; super.onDestroy() }

    /** Hue around the circle, saturation from the centre (white) outwards. */
    private class ColorWheel(context: Context, private val picked: (Int) -> Unit) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * context.resources.displayMetrics.density; color = Color.WHITE }
        private var mx = -1f; private var my = -1f
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val r = min(w, h) / 2f
            val sweep = SweepGradient(w / 2f, h / 2f, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
            val radial = RadialGradient(w / 2f, h / 2f, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
            paint.shader = ComposeShader(sweep, radial, PorterDuff.Mode.SRC_OVER)
        }
        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) / 2f
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
            if (mx >= 0) canvas.drawCircle(mx, my, 10f * resources.displayMetrics.density, marker)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f
            val dx = event.x - cx; val dy = event.y - cy
            val distance = hypot(dx, dy).coerceAtMost(r)
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat().let { if (it < 0) it + 360f else it }
            mx = cx + dx * distance / hypot(dx, dy).coerceAtLeast(1f); my = cy + dy * distance / hypot(dx, dy).coerceAtLeast(1f)
            picked(Color.HSVToColor(floatArrayOf(angle, distance / r, 1f)))
            invalidate()
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
