package app.anihome.launcher.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Compatibility entry point for old links. Browser behavior and watch tracking belong to AniBrave. */
class WebActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = (intent.dataString ?: intent.getStringExtra(EXTRA_URL))?.let { Uri.parse(it).normalizeScheme().toString() }
        if (url == null || Uri.parse(url).scheme !in listOf("https", "http")) { finish(); return }
        try {
            startActivity(browserIntent(url))
            finish()
        } catch (_: ActivityNotFoundException) { missingBrowser() }
          catch (_: SecurityException) { missingBrowser() }
    }

    private fun missingBrowser() {
        MaterialAlertDialogBuilder(this).setTitle("AniBrave needed")
            .setMessage("Install or update AniBrave to open websites and search from AniHome. Your home tiles are preserved.")
            .setPositiveButton("OK") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }

    companion object {
        private const val EXTRA_URL = "url"
        fun intent(context: Context, url: String) = Intent(context, WebActivity::class.java).setData(Uri.parse(url))
        fun browserIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setClassName("app.anibrave.main", "app.anibrave.ShortcutLaunchActivity")
    }
}
