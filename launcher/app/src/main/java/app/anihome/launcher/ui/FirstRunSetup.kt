package app.anihome.launcher.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.anihome.launcher.notifications.HomeNotifications
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Asked once, on first launch: only what AniHome needs to work at all (being the home app, and
 * notification access for the panel). Everything else (controls, Shizuku, media, location,
 * Bluetooth, system settings) is asked at the moment a feature needs it.
 */
internal object FirstRunSetup {
    private const val KEY = "first_run_setup_v1"
    private var showing: androidx.appcompat.app.AlertDialog? = null

    fun maybeShow(activity: AppCompatActivity, isDefaultHome: () -> Boolean, makeDefault: () -> Unit) {
        val prefs = activity.getSharedPreferences("anihome", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false) || showing?.isShowing == true) return
        if (isDefaultHome() && HomeNotifications.enabled(activity)) { prefs.edit().putBoolean(KEY, true).apply(); return }
        // Not marked done until both are granted or the user chooses Later: granting the home role
        // recreates Home, which used to close this card after the first permission for good.
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), dp(4)) }
        body.addView(TextView(activity).apply {
            text = "Two things make AniHome work. Everything else is asked only when you use it."
            textSize = 14f; setTextColor(0xFFC9CCD3.toInt())
        })
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        fun render() {
            rows.removeAllViews()
            listOf(
                Triple("Make AniHome your home app", "So the Home button opens AniHome.", isDefaultHome() to { makeDefault() }),
                Triple("Allow notification access", "So your notifications show in AniHome's panel. They stay on this device.", HomeNotifications.enabled(activity) to {
                    runCatching { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }; Unit
                }),
            ).forEach { (title, detail, state) ->
                val (done, action) = state
                val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(6)) }
                row.addView(TextView(activity).apply { text = (if (done) "✓  " else "") + title; textSize = 15f; setTextColor(if (done) 0xFF34C759.toInt() else -1) })
                if (!done) {
                    row.addView(TextView(activity).apply { text = detail; textSize = 13f; setTextColor(0xFFB3B6BE.toInt()) })
                    row.addView(MaterialButton(activity).apply { text = "Allow"; setOnClickListener { action() } },
                        LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.START; topMargin = dp(4) })
                }
                rows.addView(row)
            }
        }
        render()
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("Set up AniHome").setView(body)
            .setPositiveButton("Done") { _, _ -> prefs.edit().putBoolean(KEY, true).apply() }
            .setNegativeButton("Later") { _, _ -> prefs.edit().putBoolean(KEY, true).apply() }
            .setCancelable(false).create()
        dialog.setOnShowListener { dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { focused ->
            if (!focused) return@addOnWindowFocusChangeListener
            render()
            if (isDefaultHome() && HomeNotifications.enabled(activity)) { prefs.edit().putBoolean(KEY, true).apply(); dialog.dismiss() }
        } }
        dialog.setOnDismissListener { if (showing === dialog) showing = null }
        showing = dialog
        dialog.show()
    }
}
