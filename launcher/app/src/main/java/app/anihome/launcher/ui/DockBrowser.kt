package app.anihome.launcher.ui

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager

/** The dock preference changes only dock launches, never the Android default-browser role. */
internal object DockBrowser {
    data class Choice(val label: String, val packageName: String?)
    private fun prefs(context: Context) = context.getSharedPreferences("anihome", Context.MODE_PRIVATE)
    fun selected(context: Context): String? = prefs(context).getString("dock_browser", null)
    fun select(context: Context, packageName: String?) { prefs(context).edit().putString("dock_browser", packageName).apply() }
    fun choices(context: Context): List<Choice> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.exported && it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { Choice(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
            .sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
        return listOf(Choice("System default", null)) + browsers
    }
    fun intent(context: Context, query: String, url: String, isAddress: Boolean): Intent? {
        val browser = selected(context) ?: return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (!isAddress) {
            val search = Intent(Intent.ACTION_WEB_SEARCH).setPackage(browser).putExtra(SearchManager.QUERY, query)
            if (search.resolveActivity(context.packageManager) != null) return search
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser)
    }
}
