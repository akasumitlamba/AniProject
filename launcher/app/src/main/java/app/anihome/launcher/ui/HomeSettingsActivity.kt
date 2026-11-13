package app.anihome.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.anihome.launcher.R

/**
 * AniHome Settings: its own app icon and screen holding every launcher setting (layout, dock,
 * panel, gestures, app menu, banners…). Wide screens get the two-pane layout; phones a list.
 */
class HomeSettingsActivity : AppCompatActivity() {
    internal lateinit var updater: app.anihome.launcher.updates.AppUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = app.anihome.launcher.updates.AppUpdater(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        fun dp(v: Int) = ArtworkUi.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF161A24.toInt(), 0xFF0C0D12.toInt(), 0xFF141018.toInt()))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(16), dp(8)) }
        bar.addView(ArtworkUi.plainIcon(this, R.drawable.ic_back, "Back") { finish() }.apply { setPadding(dp(12), dp(12), dp(12), dp(12)) },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(TextView(this).apply {
            text = "AniHome Settings"; textSize = 22f; setTextColor(-1); setPadding(dp(8), 0, 0, 0)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        root.addView(bar)
        val wide = resources.configuration.screenWidthDp >= 840
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16)) }
        if (wide) root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        else root.addView(ScrollView(this).apply { addView(content, FrameLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        this.content = content
        populate()
    }

    private var content: LinearLayout? = null
    private var populated = false
    /** Settings act on the running launcher; if Android restarted it, wait for it, then fill in. */
    private fun populate() {
        val target = content ?: return
        if (populated) return
        val home = HomeActivity.live
        if (home == null) {
            if (target.childCount == 0) target.addView(TextView(this).apply {
                text = "Loading AniHome…"; textSize = 15f; setTextColor(0xFFC8CBD2.toInt())
                setPadding(ArtworkUi.dp(context, 8), ArtworkUi.dp(context, 24), 0, 0)
            })
            target.postDelayed({ if (!populated && HomeActivity.live == null) startActivity(Intent(this, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_OPEN_SETTINGS, true)) else populate() }, 400)
            return
        }
        populated = true
        target.removeAllViews()
        home.populateHomeSettings(target, this)
        target.alpha = 0f; target.animate().alpha(1f).setDuration(MotionUi.duration(target, 200)).start()
    }

    override fun onResume() { super.onResume(); populate() }
}
