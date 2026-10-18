package app.anihome.launcher.ui

import android.content.pm.ActivityInfo
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

internal object RotationControl {
    @Suppress("DEPRECATION")
    fun portraitAngle(activity: AppCompatActivity): Int {
        val metrics = android.util.DisplayMetrics()
        activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        return QuickSettingsPolicy.lockRotation(false, metrics.widthPixels > metrics.heightPixels,
            activity.windowManager.defaultDisplay.rotation)
    }
    fun mode(activity: AppCompatActivity): String {
        if (Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1) return "Auto"
        return if (Settings.System.getInt(activity.contentResolver, Settings.System.USER_ROTATION, 0) % 2 == portraitAngle(activity) % 2) "Portrait" else "Landscape"
    }
    // Some tablet firmware ignores USER_ROTATION for an unspecified HOME activity.
    // Honor the same system preference explicitly here, including after activity recreation.
    fun applyHome(activity: AppCompatActivity) {
        val orientation = when (mode(activity)) {
            "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        if (activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
    }
}
