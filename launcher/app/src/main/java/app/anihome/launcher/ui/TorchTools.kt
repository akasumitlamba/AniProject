package app.anihome.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Flashlight extras (hold the Flashlight control): brightness where the phone supports torch
 * levels, an SOS signal, and the screen light. Brightness is only offered when Android reports
 * more than one level, so it never shows a slider that does nothing.
 */
internal object TorchTools {
    /** Morse "SOS" in units: positive = light on, negative = off. One unit is 200 ms. */
    private val SOS = intArrayOf(1, -1, 1, -1, 1, -3, 3, -1, 3, -1, 3, -3, 1, -1, 1, -1, 1, -7)
    private const val UNIT_MS = 200L
    private val handler = Handler(Looper.getMainLooper())
    private var sos: Runnable? = null
    val sosRunning get() = sos != null

    /** Repeats [SOS] through [light] until [stopSos]. */
    fun runPattern(light: (Boolean) -> Unit): Runnable {
        var index = 0
        val step = object : Runnable {
            override fun run() {
                val units = SOS[index]; index = (index + 1) % SOS.size
                light(units > 0)
                handler.postDelayed(this, kotlin.math.abs(units) * UNIT_MS)
            }
        }
        handler.post(step)
        return step
    }
    fun stopPattern(step: Runnable?) { step?.let(handler::removeCallbacks) }

    fun startSos(camera: CameraManager?, id: String) {
        stopSos()
        sos = runPattern { on -> runCatching { camera?.setTorchMode(id, on) } }
    }
    fun stopSos(camera: CameraManager? = null, id: String? = null) {
        stopPattern(sos); sos = null
        if (camera != null && id != null) runCatching { camera.setTorchMode(id, false) }
    }

    private fun maxLevel(camera: CameraManager?, id: String): Int =
        if (Build.VERSION.SDK_INT >= 33) runCatching { camera?.getCameraCharacteristics(id)?.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1 }.getOrDefault(1) else 1

    fun show(activity: Activity, camera: CameraManager?, id: String?, changed: () -> Unit) {
        if (id == null) return
        fun dp(v: Int) = ArtworkUi.dp(activity, v)
        val ctx = MaterialUi.themed(activity)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(4)) }
        val prefs = activity.getSharedPreferences("anihome", Context.MODE_PRIVATE)
        val max = maxLevel(camera, id)
        if (max > 1 && Build.VERSION.SDK_INT >= 33) {
            body.addView(TextView(ctx).apply { text = "Brightness"; textSize = 14f; setTextColor(MaterialUi.ON_SURFACE) })
            body.addView(com.google.android.material.slider.Slider(ctx).apply {
                valueFrom = 1f; valueTo = max.toFloat(); stepSize = 1f
                value = prefs.getInt("torch_level", max).coerceIn(1, max).toFloat()
                addOnChangeListener { _, v, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    stopSos()
                    runCatching { camera?.turnOnTorchWithStrengthLevel(id, v.toInt()) }
                    prefs.edit().putInt("torch_level", v.toInt()).apply(); changed()
                }
            })
        }
        val sosButton = MaterialUi.tonal(ctx, if (sosRunning) "Stop SOS" else "SOS signal", 0) {}
        sosButton.setOnClickListener {
            if (sosRunning) stopSos(camera, id) else startSos(camera, id)
            sosButton.text = if (sosRunning) "Stop SOS" else "SOS signal"; changed()
        }
        body.addView(sosButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        body.addView(MaterialUi.tonal(ctx, "Screen light", 0) {
            dialog.dismiss()
            activity.startActivity(Intent(activity, ScreenLightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(TextView(ctx).apply {
            textSize = 12f; setTextColor(MaterialUi.ON_SURFACE_VARIANT); setPadding(0, dp(10), 0, 0)
            text = if (max > 1) "SOS flashes ··· — — — ··· until you stop it or tap Flashlight." else "This phone's flashlight has one brightness. SOS flashes ··· — — — ··· until you stop it."
        })
        dialog = MaterialAlertDialogBuilder(activity).setTitle("Flashlight").setView(body).setPositiveButton("Done", null).create()
        dialog.show()
    }
}
