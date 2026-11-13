package app.anihome.launcher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.Color
import app.anihome.launcher.banners.*
import app.anihome.launcher.data.*
import app.anihome.launcher.ui.AppFrequency
import java.io.File

/** Uses isolated preferences and files; does not change the user's Home or store selection. */
internal object AppearanceRegression {
    fun run(test: Instrumentation): String {
        var checks = 0
        fun verify(ok: Boolean, message: String) { check(ok) { message }; checks++ }
        val directory = File(test.targetContext.cacheDir, "appearance-regression").apply { mkdirs() }
        val fixture = object : android.content.ContextWrapper(test.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("appearance-regression-$name", mode)
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
        }
        fixture.getSharedPreferences("drawer", 0).edit().clear().commit()
        fixture.getSharedPreferences("app_frequency", 0).edit().clear().commit()
        fixture.getSharedPreferences("banner_store", 0).edit().clear().commit()
        val apps = listOf(InstalledApp("One", "fixture.one", "One"), InstalledApp("Two", "fixture.two", "Two"))
        verify(AppFrequency.recent(fixture, apps).isEmpty(), "fresh install has no recent apps")
        AppFrequency.record(fixture, "fixture.two", "Two")
        verify(AppFrequency.recent(fixture, apps) == listOf(apps[1]), "only opened apps enter recents")
        for (shape in IconShape.entries) {
            IconShape.select(fixture, shape)
            val icon = IconCache.dockIcon(fixture, test.targetContext.packageName, null)!!
            verify(icon.width == icon.height, "${shape.name}: square artwork bounds")
            verify(Color.alpha(icon.getPixel(icon.width / 2, icon.height / 2)) == 255, "${shape.name}: filled center")
            verify((Color.alpha(icon.getPixel(0, 0)) > 0) == (shape == IconShape.SQUARE), "${shape.name}: requested silhouette")
            verify(IconCache.cachedDockIcon(fixture, test.targetContext.packageName, null) === icon, "${shape.name}: cached independently")
        }
        val preferences = DrawerPreferences(fixture)
        preferences.setOpacity(31); verify(preferences.opacity() == 31, "menu opacity persists")
        preferences.setShowRecentFiles(false); verify(!DrawerPreferences(fixture).showRecentFiles(), "recent-files visibility persists")
        val repository = BannerRepository(fixture)
        val entry = repository.catalog().first()
        val image = repository.image(entry)
        verify(image.canonicalPath.startsWith(fixture.filesDir.canonicalPath + "/"), "store artwork is durable local content")
        val hash = BannerRules.hash(image.readBytes())
        fixture.cacheDir.deleteRecursively()
        verify(BannerRules.hash(BannerRepository(fixture).image(entry).readBytes()) == hash, "artwork survives disposable cache clearing")
        BannerSource.select(fixture, "https://art.example.org/store/catalog.json")
        verify(BannerSource.configured(fixture) == "https://art.example.org/store/", "alternate HTTPS store persists")
        verify(BannerRepository(fixture).image(entry).readBytes().contentEquals(image.readBytes()), "cached artwork remains usable with another store")
        directory.deleteRecursively()
        for (name in listOf("drawer", "app_frequency", "banner_store")) fixture.getSharedPreferences(name, 0).edit().clear().commit()
        return "$checks appearance/store checks passed"
    }
}
