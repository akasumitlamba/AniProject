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
