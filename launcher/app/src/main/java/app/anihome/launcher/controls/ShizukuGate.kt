package app.anihome.launcher.controls

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.shizuku.Shizuku

/**
 * Shizuku unlocks AniHome's extra controls without root. Most people don't have it, so every
 * feature that needs it explains why and walks through the one missing step: install, start,
 * or allow access.
 */
internal object ShizukuGate {
    private const val PACKAGE = "moe.shizuku.privileged.api"
    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    fun state(context: Context): State = when {
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isFailure -> State.NOT_INSTALLED
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> State.NOT_RUNNING
        runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
        else -> State.READY
    }

    /** Returns true when ready; otherwise shows the right prompt for [feature]. */
    fun require(context: Context, feature: String): Boolean {
        val state = state(context)
        if (state == State.READY) return true
        val builder = MaterialAlertDialogBuilder(context).setNegativeButton("Not now", null)
        when (state) {
            State.NOT_INSTALLED -> builder.setTitle("$feature needs Shizuku")
                .setMessage("Shizuku is a free, open-source app that lets AniHome use advanced Android controls safely, without root. Install it, start it once, then come back.")
                .setPositiveButton("Get Shizuku") { _, _ -> open(context, "https://github.com/RikkaApps/Shizuku/releases/latest") }
                .setNeutralButton("How it works") { _, _ -> open(context, "https://shizuku.rikka.app/guide/setup/") }
            State.NOT_RUNNING -> builder.setTitle("Start Shizuku")
                .setMessage("Shizuku is installed but not running. Open it and tap Start (via Wireless debugging, no computer needed on Android 11+). It needs starting again after a restart.")
                .setPositiveButton("Open Shizuku") { _, _ ->
                    context.packageManager.getLaunchIntentForPackage(PACKAGE)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                .setNeutralButton("Setup guide") { _, _ -> open(context, "https://shizuku.rikka.app/guide/setup/") }
            else -> builder.setTitle("Allow AniHome in Shizuku")
                .setMessage("$feature needs AniHome to be allowed in Shizuku. Android will ask you once.")
                .setPositiveButton("Allow") { _, _ -> runCatching { Shizuku.requestPermission(420) } }
        }
        builder.show()
        return false
    }

    private fun open(context: Context, url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
