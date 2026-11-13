package app.anihome.launcher.data

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.*

/** The same crop mask is used for app artwork in the menu, dock and icon-style Home items. */
enum class IconShape(val label: String) {
    CIRCLE("Circle"), SQUIRCLE("Squircle"), ROUNDED_SQUARE("Rounded square"),
    SQUARE("Square"), TEARDROP("Teardrop"), HEXAGON("Hexagon"), PEBBLE("Pebble");

    fun path(size: Int): Path = Path().apply {
        val s = size.toFloat()
        when (this@IconShape) {
            CIRCLE -> addCircle(s / 2, s / 2, s / 2, Path.Direction.CW)
            SQUARE -> addRect(0f, 0f, s, s, Path.Direction.CW)
            ROUNDED_SQUARE -> addRoundRect(RectF(0f, 0f, s, s), s * .22f, s * .22f, Path.Direction.CW)
            TEARDROP -> addRoundRect(RectF(0f, 0f, s, s), floatArrayOf(s*.5f,s*.5f,s*.1f,s*.1f,s*.5f,s*.5f,s*.5f,s*.5f), Path.Direction.CW)
            PEBBLE -> addRoundRect(RectF(0f, 0f, s, s), floatArrayOf(s*.38f,s*.38f,s*.22f,s*.22f,s*.38f,s*.38f,s*.22f,s*.22f), Path.Direction.CW)
            HEXAGON -> {
                for (i in 0..5) {
                    val angle = (i * PI / 3 - PI / 2)
                    val x = s / 2 + s / 2 * cos(angle).toFloat()
                    val y = s / 2 + s / 2 * sin(angle).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            SQUIRCLE -> {
                for (i in 0..120) {
                    val angle = 2 * PI * i / 120
                    fun curve(v: Double) = sign(v) * abs(v).pow(.5)
                    val x = (s / 2 + s / 2 * curve(cos(angle))).toFloat()
                    val y = (s / 2 + s / 2 * curve(sin(angle))).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
    }
    companion object {
        fun selected(context: Context): IconShape = entries.firstOrNull {
            it.name == context.getSharedPreferences("drawer", Context.MODE_PRIVATE).getString("icon_shape", null)
        } ?: CIRCLE
        fun select(context: Context, shape: IconShape) {
            context.getSharedPreferences("drawer", Context.MODE_PRIVATE).edit().putString("icon_shape", shape.name).apply()
        }
    }
}
