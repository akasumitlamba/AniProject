package app.anihome.launcher.ui

import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import app.anihome.launcher.data.IconCache
import app.anihome.launcher.data.TileEvents
import app.anihome.launcher.data.TileStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.UUID

/** Only Android's valid, one-use pin token can authorize a shortcut; arbitrary intents cannot. */
class PinShortcutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcher = getSystemService(LauncherApps::class.java)
        val request = runCatching { launcher.getPinItemRequest(intent) }.getOrNull()
        val info = request?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && it.isValid }?.shortcutInfo
        if (info == null) { finish(); return }
        val label = info.shortLabel?.toString()?.take(200)?.takeIf { it.isNotBlank() }
        val user = getSystemService(UserManager::class.java).getSerialNumberForUser(info.userHandle)
        if (label == null || user < 0) { finish(); return }
        val icon = runCatching { launcher.getShortcutBadgedIconDrawable(info, resources.displayMetrics.densityDpi) }.getOrNull()
        val publisher = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.`package`, 0)).toString() }.getOrDefault(info.`package`)
        MaterialAlertDialogBuilder(this).setTitle("Add to Home").setMessage("$label\n$publisher").setIcon(icon)
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .setPositiveButton("Add") { _, _ ->
                var image: File? = null
                runCatching {
                    // Keep the token valid until the explicit confirmation; never replay a saved intent.
                    check(request.isValid && request.accept()) { "Shortcut request expired" }
                    val iconPath = runCatching {
                        icon?.let {
                            val folder = File(filesDir, "shortcut_icons").apply { mkdirs() }
                            val file = File(folder, "${UUID.randomUUID()}.png")
                            image = file
                            val bitmap = it.toBitmap(192, 192)
                            file.outputStream().use { output -> check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) }
                            file.absolutePath
                        }
                    }.getOrNull()
                    val store = TileStore(this)
                    val tile = store.addShortcut(label, info.`package`, info.id, user, iconPath)
                    TileEvents.notifyChanged()
                    if (tile.iconPath != image?.absolutePath) image?.delete()
                    Toast.makeText(this, "Added to Home", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    image?.let { IconCache.invalidateFile(it.absolutePath); it.delete() }
                    Toast.makeText(this, "Could not add shortcut. Request it again from the app.", Toast.LENGTH_LONG).show()
                }
                finish()
            }.show()
    }
}
