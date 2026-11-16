package app.anihome.launcher.controls

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * One explained path to AniHome controls (the accessibility service behind screen off, recent
 * apps, keep-awake, the swipe-down panel and the volume panel). Never drops the user into
 * Android settings without saying what to do there. With Shizuku it is switched on in one tap.
 */
internal object ControlsAccess {
    private val service = ComponentName("app.anihome.launcher", "app.anihome.launcher.controls.HomeControlsService")

    /**
     * True when the controls are already on. Otherwise explains and offers a route, then false.
     * [grant] asks Shizuku for the one-time permission that lets AniHome switch it on itself.
     */
    fun ensure(activity: Activity, feature: String, grant: ((done: () -> Unit) -> Unit)? = null): Boolean {
        if (HomeControlsService.connected()) return true
        if (canSelfEnable(activity)) { enable(activity); return false }
        val shizuku = ShizukuGate.ready(activity)
        val restricted = Build.VERSION.SDK_INT >= 33
        val message = buildString {
            append("$feature uses AniHome controls, an Android accessibility service. It never reads what's on your screen.\n\n")
            if (shizuku && grant != null) append("Shizuku is ready, so AniHome can switch it on for you.")
            else {
                append("In Accessibility settings, open AniHome controls and turn it on.")
                if (restricted) append(" If Android says it's a restricted setting, tap App info, then ⋮ → Allow restricted settings, and try again.")
                append("\n\nWith Shizuku, AniHome can switch it on for you instead.")
            }
        }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Turn on AniHome controls").setMessage(message)
            .setNegativeButton("Not now", null)
        if (shizuku && grant != null) {
            dialog.setPositiveButton("Turn on") { _, _ ->
                grant {
                    if (canSelfEnable(activity)) enable(activity)
                    else Toast.makeText(activity, "Shizuku couldn't grant access. Use Accessibility settings instead.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            dialog.setPositiveButton("Open settings") { _, _ -> open(activity, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            if (restricted) dialog.setNeutralButton("App info") { _, _ ->
                open(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
            } else dialog.setNeutralButton("Use Shizuku") { _, _ -> ShizukuGate.showSetup(activity, feature) }
        }
        dialog.show()
        return false
    }

    private fun canSelfEnable(activity: Activity) =
        activity.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun enable(activity: Activity) {
        val ok = runCatching {
            val resolver = activity.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() }.mapNotNull { ComponentName.unflattenFromString(it) }
            if (service !in current) Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (current + service).joinToString(":") { it.flattenToString() })
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.isSuccess
        Toast.makeText(activity, if (ok) "AniHome controls are on" else "Android didn't allow it. Use Accessibility settings.", Toast.LENGTH_SHORT).show()
    }

    private fun open(activity: Activity, intent: Intent) = runCatching { activity.startActivity(intent) }
        .onFailure { Toast.makeText(activity, "That settings page isn't available on this device", Toast.LENGTH_LONG).show() }
}
