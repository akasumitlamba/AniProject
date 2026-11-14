package app.anihome.launcher.controls

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.shizuku.Shizuku

/**
 * Shizuku unlocks AniHome's extra controls without root. Most people don't have it, so nothing
 * depends on it: controls fall back to Android's own settings with at most a small daily hint,
 * and a step-by-step guide (AniHome Settings → Shizuku) covers install, start and allow.
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

    fun ready(context: Context) = state(context) == State.READY

    /** One-line summary for settings. */
    fun summary(context: Context) = when (state(context)) {
        State.READY -> "Connected"
        State.NO_PERMISSION -> "Running — allow AniHome"
        State.NOT_RUNNING -> "Installed — not started"
        State.NOT_INSTALLED -> "Not installed (optional)"
    }

    /**
     * Gentle notice for a control that works better with Shizuku. Never blocks: the caller still
     * does its fallback (usually opening Android's own setting). Shown at most once a day per feature.
     */
    fun hint(context: Context, feature: String) {
        val prefs = context.getSharedPreferences("shizuku_hints", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(feature, 0) < 24 * 60 * 60_000L) return
        prefs.edit().putLong(feature, now).apply()
        val step = when (state(context)) {
            State.NOT_INSTALLED -> "with Shizuku"
            State.NOT_RUNNING -> "once Shizuku is started"
            State.NO_PERMISSION -> "once AniHome is allowed in Shizuku"
            State.READY -> return
        }
        android.widget.Toast.makeText(context, "$feature works in one tap $step. Set it up in AniHome Settings → Shizuku.", android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * For settings the user turns on deliberately. Returns true when ready; otherwise opens the
     * step-by-step setup guide (no scary dialogs) and returns false.
     */
    fun require(context: Context, feature: String): Boolean {
        if (ready(context)) return true
        showSetup(context, feature)
        return false
    }

    /**
     * Step-by-step guide: 1 install, 2 start, 3 allow. Only the current step has a button; steps
     * tick themselves off as the user comes back from Shizuku.
     */
    fun showSetup(context: Context, feature: String? = null) {
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val body = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), dp(4)) }
        val intro = android.widget.TextView(context).apply {
            textSize = 14f; setTextColor(0xFFC9CCD3.toInt()); setLineSpacing(0f, 1.15f)
            text = (if (feature != null) "$feature needs Shizuku. " else "") +
                "Shizuku is a free, open-source helper that lets AniHome change system settings in one tap, without root. Everything else in AniHome works without it."
        }
        val steps = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
        body.addView(intro); body.addView(steps, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val dialog = MaterialAlertDialogBuilder(context).setTitle("Set up Shizuku").setView(body).setNegativeButton("Close", null).create()
        fun render() {
            steps.removeAllViews()
            val state = state(context)
            val current = when (state) { State.NOT_INSTALLED -> 0; State.NOT_RUNNING -> 1; State.NO_PERMISSION -> 2; State.READY -> 3 }
            val items = listOf(
                Triple("Install Shizuku", "Free from Google Play or GitHub.", "Get Shizuku" to { install(context) }),
                Triple("Start Shizuku", "Open Shizuku and tap Start under \"Start via Wireless debugging\" (Android 11+), or follow its guide. Start it again after restarting your device.", "Open Shizuku" to {
                    context.packageManager.getLaunchIntentForPackage(PACKAGE)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: Unit }),
                Triple("Allow AniHome", "Android asks once. AniHome only uses it for the controls you tap.", "Allow" to { runCatching { Shizuku.requestPermission(420) }; Unit }))
            items.forEachIndexed { i, (title, detail, action) ->
                val done = i < current; val active = i == current
                val row = android.widget.LinearLayout(context).apply { setPadding(0, dp(8), 0, dp(8)) }
                row.addView(android.widget.TextView(context).apply {
                    text = if (done) "✓" else "${i + 1}"; gravity = android.view.Gravity.CENTER; textSize = 13f
                    setTextColor(if (done || active) 0xFF0B1220.toInt() else 0xFFC9CCD3.toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(when { done -> 0xFF34C759.toInt(); active -> 0xFF8AB4FF.toInt(); else -> 0x33FFFFFF })
                    }
                }, android.widget.LinearLayout.LayoutParams(dp(26), dp(26)))
                val text = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
                text.addView(android.widget.TextView(context).apply { this.text = title; textSize = 15f; setTextColor(if (done) 0xFF9EA2AB.toInt() else -1) })
                if (active) {
                    text.addView(android.widget.TextView(context).apply { this.text = detail; textSize = 13f; setTextColor(0xFFB3B6BE.toInt()) })
                    text.addView(com.google.android.material.button.MaterialButton(context).apply {
                        this.text = action.first; setOnClickListener { action.second() }
                    }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                    if (i == 1) text.addView(android.widget.TextView(context).apply {
                        this.text = "Setup guide"; textSize = 13f; setTextColor(0xFF8AB4FF.toInt()); setPadding(0, dp(6), 0, 0)
                        setOnClickListener { open(context, "https://shizuku.rikka.app/guide/setup/") }
                    })
                }
                row.addView(text, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
                steps.addView(row)
            }
            if (state == State.READY) steps.addView(android.widget.TextView(context).apply {
                text = "All set. One-tap controls, per-app volume and the Bluetooth and Wi-Fi extras are ready."
                textSize = 14f; setTextColor(0xFF34C759.toInt()); setPadding(0, dp(8), 0, 0)
            })
        }
        val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> body.post { render() } }
        val alive = Shizuku.OnBinderReceivedListener { body.post { render() } }
        runCatching { Shizuku.addRequestPermissionResultListener(permission); Shizuku.addBinderReceivedListenerSticky(alive) }
        dialog.setOnShowListener {
            dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { focused -> if (focused) render() }
        }
        dialog.setOnDismissListener { runCatching { Shizuku.removeRequestPermissionResultListener(permission); Shizuku.removeBinderReceivedListener(alive) } }
        render()
        dialog.show()
    }

    private fun install(context: Context) {
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (store.resolveActivity(context.packageManager) != null && runCatching { context.startActivity(store) }.isSuccess) return
        open(context, "https://github.com/RikkaApps/Shizuku/releases/latest")
    }

    private fun open(context: Context, url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
