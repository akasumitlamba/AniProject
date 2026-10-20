package app.anihome.launcher.ui

import android.content.pm.ActivityInfo
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

internal object RotationControl {
    @Suppress("DEPRECATION")
    fun portraitAngle(activity: AppCompatActivity): Int {
        // App-compatible window metrics can be portrait while the display rotation is
        // landscape. The display mode's natural dimensions do not change with that window.
        val mode = activity.applicationContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(activity.windowManager.defaultDisplay.displayId)?.mode ?: activity.windowManager.defaultDisplay.mode
        return if (mode.physicalWidth > mode.physicalHeight) 1 else 0
    }
    private fun preferences(activity: AppCompatActivity) = activity.getSharedPreferences("anihome", 0)
    fun select(activity: AppCompatActivity, mode: String) {
        require(mode in listOf("Portrait", "Landscape", "Auto"))
        preferences(activity).edit().putString("rotation_mode", mode).apply()
        if (Settings.System.canWrite(activity)) runCatching {
            val target = if (mode == "Landscape") (portraitAngle(activity) + 1) % 2 else portraitAngle(activity)
            android.util.Log.d("AniHomeRotation", "selection=$mode target=$target")
            if (mode != "Auto") check(Settings.System.putInt(activity.contentResolver, Settings.System.USER_ROTATION, target))
            check(Settings.System.putInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (mode == "Auto") 1 else 0))
        }
        applyHome(activity)
    }
    fun followDevice(activity: AppCompatActivity) { preferences(activity).edit().remove("rotation_mode").apply(); applyHome(activity) }
    fun mode(activity: AppCompatActivity): String {
        preferences(activity).getString("rotation_mode", null)?.takeIf { it in listOf("Portrait", "Landscape", "Auto") }?.let { return it }
        if (Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1) return "Auto"
        return if (Settings.System.getInt(activity.contentResolver, Settings.System.USER_ROTATION, 0) % 2 == portraitAngle(activity) % 2) "Portrait" else "Landscape"
    }
    // Some tablet firmware ignores USER_ROTATION for an unspecified HOME activity.
    // Honor the same system preference explicitly here, including after activity recreation.
    fun applyHome(activity: AppCompatActivity) {
        val orientation = when (mode(activity)) {
            "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> if (preferences(activity).contains("rotation_mode")) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        if (activity.requestedOrientation != orientation) {
            android.util.Log.d("AniHomeRotation", "requested=$orientation previous=${activity.requestedOrientation}")
            activity.requestedOrientation = orientation
            android.util.Log.d("AniHomeRotation", "effective=${activity.requestedOrientation}")
        }
    }
}
