package app.anihome.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import app.anihome.launcher.controls.SystemBar

/**
 * Transparent host that shows AniHome's notification panel and Control Center over whatever
 * app is open. Started by the edge-swipe overlay; finishes as soon as the panel closes, so the
 * app underneath simply continues.
 */
class PanelActivity : AppCompatActivity() {
    private lateinit var panel: NotificationPanel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        panel = NotificationPanel(this, ::settings)
        panel.onDismissed = { finish(); overridePendingTransition(0, 0) }
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!panel.isShowing) show(intent)
    }

    private fun show(intent: Intent) {
        val edge = runCatching { SystemBar.Edge.valueOf(intent.getStringExtra(EXTRA_EDGE) ?: "") }.getOrDefault(SystemBar.edge(this))
        panel.rightSide = edge == SystemBar.Edge.RIGHT
        window.decorView.post {
            panel.show(when (edge) { SystemBar.Edge.TOP -> "DOWN"; SystemBar.Edge.LEFT -> "RIGHT"; SystemBar.Edge.RIGHT -> "LEFT" })
        }
    }

    override fun onResume() { super.onResume(); panel.resume() }

    // Full Home settings live in AniHome itself; offer a direct jump there.
    private fun settings(parent: LinearLayout) {
        val panelUi = SettingsPanel(this, twoPane = SettingsPanel.twoPane(this))
        panelUi.section("AniHome", app.anihome.launcher.R.drawable.ic_settings) {
            panelUi.text(this, "Layout, dock, gestures and edge-panel options are in Home settings.")
            panelUi.action(this, "Open Home settings") {
                startActivity(Intent(this@PanelActivity, HomeActivity::class.java)
                    .putExtra(HomeActivity.EXTRA_OPEN_SETTINGS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                panel.dismiss()
            }
        }
        panelUi.attach(parent)
    }

    override fun onDestroy() { if (panel.isShowing) panel.dismissNow(); super.onDestroy() }

    companion object { const val EXTRA_EDGE = "edge" }
}
