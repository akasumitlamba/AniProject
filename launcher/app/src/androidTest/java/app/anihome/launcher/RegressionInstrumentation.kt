package app.anihome.launcher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Bundle
import app.anihome.launcher.data.*
import app.anihome.launcher.ui.TileAdapter
import java.io.File

/** Uses a dedicated QA cache directory and database, never the user's launcher data. */
class RegressionInstrumentation : Instrumentation() {
    private var checks = 0
    private fun verify(ok: Boolean, name: String) { check(ok) { name }; checks++ }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        try {
            val qaDirectory = File(targetContext.cacheDir,"regression-fixture").apply { mkdirs() }
            val fixture = object : android.content.ContextWrapper(targetContext) {
                override fun getApplicationContext(): Context = this
                override fun getCacheDir() = qaDirectory
                override fun getDatabasePath(name: String) = File(qaDirectory,name)
                override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
            }
            verify(fixture.cacheDir.name == "regression-fixture", "isolated fixture storage")
            verify(SiteIcons.normalizeUrl("HTTPS://Example.com/Path") == "https://Example.com/Path", "uppercase scheme normalized without changing case-sensitive path")
            verify(SiteIcons.normalizeUrl("HTTP://Example.com/Path") == "http://Example.com/Path", "uppercase HTTP normalized")
            val photo = File(fixture.cacheDir, "qa-artwork.png")
            Bitmap.createBitmap(1300,800,Bitmap.Config.ARGB_8888).also { image -> photo.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle() }
            val decoded = IconCache.fileIcon(photo.path)!!
            verify(maxOf(decoded.width,decoded.height)<=640,"artwork decode bounded")
            verify(decoded.allocationByteCount<1300*800*4,"artwork uses less memory than original")
            photo.delete();IconCache.invalidateFile(photo.path)
            val tiles = (1L..4L).map { HomeTile(it,HomeTile.Kind.WEB,"Fixture $it",url="https://fixture.invalid/$it",position=it.toInt()) }
            runOnMainSync {
                val adapter=TileAdapter({},{},{},{})
                adapter.submitList(tiles)
                adapter.move(0,1);adapter.move(1,2);adapter.move(2,3)
                verify(adapter.currentList.map { it.id }==listOf(2L,3L,4L,1L),"rapid multi-step drag keeps latest order")
                adapter.move(3,0)
                verify(adapter.currentList.map { it.id }==listOf(1L,2L,3L,4L),"reverse drag order")
            }
            // A warm generic app icon must not suppress a cold pinned-shortcut icon.
            val shortcutArt = File(qaDirectory, "shortcut-priority.png")
            Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).also { image ->
                image.eraseColor(android.graphics.Color.MAGENTA)
                shortcutArt.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                image.recycle()
            }
            IconCache.invalidateFile(shortcutArt.path)
            val pkg = targetContext.packageName
            verify(IconCache.appIcon(targetContext, pkg, null) != null, "generic app icon is warm before shortcut binding")
            lateinit var imageHolder: TileAdapter.Holder
            lateinit var imageAdapter: TileAdapter
            lateinit var registry: androidx.lifecycle.LifecycleRegistry
            runOnMainSync {
                val owner = object : android.view.ContextThemeWrapper(targetContext, R.style.Theme_AniHome), androidx.lifecycle.LifecycleOwner {
                    override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
                }
                registry = owner.lifecycle
                registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
                imageAdapter = TileAdapter({},{},{},{})
                imageAdapter.submitList(listOf(HomeTile(900, HomeTile.Kind.APP, "Shortcut fixture", packageName=pkg,
                    shortcutId="fixture", shortcutUser=0, iconPath=shortcutArt.path, position=0)))
                imageHolder = imageAdapter.onCreateViewHolder(android.widget.FrameLayout(owner),0)
                imageAdapter.onBindViewHolder(imageHolder,0)
            }
            var loadedShortcut = false
            val deadline = android.os.SystemClock.uptimeMillis()+5000
            while (!loadedShortcut && android.os.SystemClock.uptimeMillis()<deadline) {
                runOnMainSync {
                    val image = imageHolder.itemView.findViewById<android.widget.ImageView>(R.id.icon)
                    val bitmap = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    loadedShortcut = bitmap?.getPixel(0,0) == android.graphics.Color.MAGENTA
                }
                if (!loadedShortcut) Thread.sleep(20)
            }
            verify(loadedShortcut, "cold shortcut artwork replaces a cached generic app icon")
            runOnMainSync {
                imageHolder.itemView.alpha = .45f
                imageAdapter.editMode = true
                imageAdapter.onBindViewHolder(imageHolder,0,mutableListOf("badge","edit"))
                verify(imageHolder.itemView.findViewById<android.view.View>(R.id.remove).visibility == android.view.View.VISIBLE,
                    "combined badge and edit payload keeps remove control visible")
                verify(imageHolder.itemView.alpha == 1f, "edit payload restores fully opaque tile after interrupted entry")
                imageHolder.itemView.alpha = .45f
                imageAdapter.onBindViewHolder(imageHolder, 0)
                verify(imageHolder.itemView.alpha == 1f, "full rebind restores fully opaque tile")
                imageAdapter.onViewRecycled(imageHolder)
                verify(imageHolder.itemView.alpha == 1f, "recycling clears stale tile opacity")
                registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
            }
            shortcutArt.delete(); IconCache.invalidateFile(shortcutArt.path)
            // Real SQLite transitions: late automatic responses must never replace a manual choice.
            SQLiteDatabase.create(null).use { database ->
                LauncherDb(targetContext).onCreate(database)
                val store = TileStore(targetContext, database)
                val tile = store.addApp("Banner fixture", "fixture.banner.app", "fixture.banner.app.Main", null)
                verify(tile.artworkOrigin == "pending", "new app requests an automatic banner")
                val auto = File(qaDirectory, "automatic.webp").apply { writeText("fixture") }
                verify(store.finishAutomaticArtwork(tile.id, auto.path), "automatic banner attaches once")
                verify(!store.finishAutomaticArtwork(tile.id, auto.path), "automatic choice cannot repeat")
                store.setArtwork(tile.id, null)
                verify(!store.finishAutomaticArtwork(tile.id, auto.path), "manual icon reset blocks late downloads")
                val manual = File(qaDirectory, "manual.jpg").apply { writeText("custom") }
                store.setArtwork(tile.id, manual.path)
                verify(!store.finishAutomaticArtwork(tile.id, auto.path), "manual banner blocks late downloads")
                val snapshot = store.byId(tile.id)!!
                store.remove(tile.id, preserveAssets = true)
                verify(manual.exists(), "undo window retains custom artwork")
                store.restore(snapshot)
                verify(store.byId(tile.id) == snapshot, "undo restores tile identity and manual choice")
                store.remove(tile.id)
                val replacement = store.addApp("Banner fixture", "fixture.banner.app", "fixture.banner.app.Main", null)
                verify(replacement.id != tile.id && replacement.artworkOrigin == "pending", "remove and re-add permits a fresh automatic choice")
                verify(!store.finishAutomaticArtwork(tile.id, auto.path), "removed tile rejects a late response")
                verify(store.finishAutomaticArtwork(replacement.id, null) && store.byId(replacement.id)?.artworkOrigin == "icon", "no banner settles on app icon")
                val compact = store.addApp("Compact fixture", "fixture.compact", "fixture.compact.Main", null)
                store.setTileStyle(compact.id, true)
                val icon = store.byId(compact.id)!!
                verify(icon.iconOnly && icon.artworkOrigin == "manual", "icon selection is durable and ends automatic artwork selection")
                verify(!store.finishAutomaticArtwork(compact.id, auto.path), "compact app icon rejects a late banner download")
                store.remove(compact.id, preserveAssets = true); store.restore(icon)
                verify(store.byId(compact.id)?.iconOnly == true, "Undo retains compact icon presentation")
                store.setTileStyle(compact.id, false)
                verify(store.byId(compact.id)?.iconOnly == false, "banner presentation can be restored")
                val shortcut = store.addShortcut("Original site", "app.anibrave.main", "site:example", 0, null)
                store.rename(shortcut.id, "  My anime  ")
                val renamed = store.byId(shortcut.id)!!
                verify(renamed.label == "My anime", "shortcut label is editable")
                verify(renamed.copy(label = shortcut.label) == shortcut, "rename preserves shortcut destination, identity and placement")
                val repinned = store.addShortcut("Publisher title", "app.anibrave.main", "site:example", 0, null)
                verify(repinned == renamed, "re-pinning cannot overwrite a personal shortcut name")
                store.rename(shortcut.id, "   ")
                verify(store.byId(shortcut.id) == renamed, "blank rename preserves the shortcut")
                store.rename(shortcut.id, "x".repeat(250))
                verify(store.byId(shortcut.id)!!.label.length == 200, "shortcut label length is bounded")
                val colouring = store.addWeb("Colour fixture", "https://fixture.invalid/colour", null)
                store.setIcon(colouring.id, "new-icon.png", null)
                verify(!store.backfillColor(colouring.id, null, 123), "late colour result rejects a replaced icon")
                verify(store.byId(colouring.id)?.iconPath == "new-icon.png", "colour work never restores an old icon")
                verify(store.backfillColor(colouring.id, "new-icon.png", 456), "matching icon receives its missing colour")
                verify(!store.backfillColor(colouring.id, "new-icon.png", 789), "existing colour is preserved")
                auto.delete(); manual.delete()
            }
            SQLiteDatabase.create(null).use { database ->
                database.execSQL("CREATE TABLE tiles(id INTEGER PRIMARY KEY, artwork_path TEXT)")
                database.execSQL("INSERT INTO tiles VALUES(1, 'existing-custom.jpg')")
                LauncherDb(targetContext).onUpgrade(database, 4, 5)
                database.rawQuery("SELECT artwork_path, artwork_origin FROM tiles", null).use { row ->
                    verify(row.moveToFirst() && row.getString(0) == "existing-custom.jpg" && row.getString(1) == "manual", "upgrade protects existing artwork")
                }
            }
            val browserContext = object : android.content.ContextWrapper(targetContext) {
                override fun getSharedPreferences(name: String, mode: Int) = targetContext.getSharedPreferences("qa-browser-$name", mode)
            }
            app.anihome.launcher.ui.DockBrowser.select(browserContext, null)
            val web = app.anihome.launcher.ui.DockBrowser.intent(browserContext, "fixture query", "https://example.invalid/", false)
            verify(web?.action == android.content.Intent.ACTION_VIEW && web.`package` == null, "default web search uses the system browser")
            app.anihome.launcher.ui.DockBrowser.select(browserContext, "fixture.browser")
            val address = app.anihome.launcher.ui.DockBrowser.intent(browserContext, "example.invalid", "https://example.invalid/", true)
            verify(address?.`package` == "fixture.browser", "explicit browser selection is retained")
            // SQLiteOpenHelper must roll back a failed old-schema migration.
            val dbName="qa-upgrade.db"
            fixture.deleteDatabase(dbName)
            val file=fixture.getDatabasePath(dbName);file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                db.execSQL("CREATE TABLE anime(key TEXT PRIMARY KEY,title TEXT,muted INTEGER,updated_at INTEGER)")
                db.execSQL("CREATE TABLE episode_log(anime_key TEXT,episode INTEGER,host TEXT)")
                db.execSQL("INSERT INTO episode_log VALUES('example',9,'hianime.to')")
                db.version=1
            }
            val isolated=object:android.content.ContextWrapper(fixture) {
                override fun getApplicationContext():Context=this
                override fun getDatabasePath(name:String)=file
                override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(file,factory)
                override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:android.database.DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(file.path,factory,handler)
            }
            var failed=false
            LauncherDb(isolated).use { helper -> try { helper.writableDatabase } catch (_:android.database.SQLException) { failed=true } }
            verify(failed,"malformed legacy copy aborts upgrade")
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                verify(db.version==1,"failed upgrade keeps schema version")
                db.rawQuery("SELECT episode FROM episode_log",null).use { verify(it.moveToFirst()&&it.getInt(0)==9,"failed upgrade keeps original watch log") }
                db.rawQuery("SELECT name FROM sqlite_master WHERE name='episode_log_v2'",null).use { verify(!it.moveToFirst(),"failed upgrade rolls back replacement table") }
            }
            fixture.deleteDatabase(dbName)
            results.putString("stream","$checks launcher Android integration checks passed\n")
            finish(Activity.RESULT_OK,results)
        } catch (failure:Throwable) { results.putString("stream",failure.stackTraceToString());finish(Activity.RESULT_CANCELED,results) }
    }
}
