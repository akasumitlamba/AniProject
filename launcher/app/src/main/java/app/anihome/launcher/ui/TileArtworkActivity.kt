package app.anihome.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.anihome.launcher.R
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.anihome.launcher.data.TileEvents
import app.anihome.launcher.data.TileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Copies a selected photo to private storage; no long-lived gallery permission is needed. */
class TileArtworkActivity : AppCompatActivity() {
    private lateinit var crop: TileCropView
    private lateinit var save: Button
    private lateinit var choose: Button
    private var tileId = -1L
    private var selectedUri: Uri? = null
    private var imageRequest = 0
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) load(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tileId = intent.getLongExtra(EXTRA_ID, -1)
        val tile = TileStore(this).byId(tileId) ?: run { finish(); return }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff0b1423.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(ArtworkUi.icon(this, R.drawable.ic_back, "Cancel editing") { finish() })
        toolbar.addView(ArtworkUi.title(this, tile.label), LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(12) })
        toolbar.addView(ArtworkUi.icon(this, R.drawable.ic_refresh, "Reset crop") { crop.resetCrop() })
        root.addView(toolbar)
        val sources = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8)) }
        sources.addView(ArtworkUi.chip(this, "Store", R.drawable.ic_apps) {
            BannerStoreDialog(this@TileArtworkActivity, tile,
                onSelect = { file -> load(Uri.fromFile(file)) },
                onGallery = { picker.launch("image/*") },
                onAI = { showBannerPrompt(BannerPrompt.forTile(tile.label, tile.packageName ?: tile.url)) }).show()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        choose = ArtworkUi.chip(this, "Gallery", R.drawable.ic_gallery) { picker.launch("image/*") }
        sources.addView(choose, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        sources.addView(ArtworkUi.chip(this, "AI", R.drawable.ic_sparkles) {
            showBannerPrompt(BannerPrompt.forTile(tile.label, tile.packageName ?: tile.url))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        root.addView(sources)
        crop = TileCropView(this)
        root.addView(crop, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Drag to move · Pinch to zoom"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(0xffaabbd0.toInt()); setPadding(0, dp(10), 0, dp(12))
        })
        save = ArtworkUi.chip(this, "Use banner", R.drawable.ic_check) { saveArtwork() }.apply { isEnabled = false }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left + dp(20), top = bars.top + dp(16), right = bars.right + dp(20), bottom = bars.bottom + dp(16))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        val restored = savedInstanceState?.getString("uri")?.let(Uri::parse)
        if (restored != null) load(restored)
        else tile.artworkPath?.let(::File)?.takeIf { it.isFile }?.let { load(Uri.fromFile(it)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("uri", selectedUri?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun showBannerPrompt(prompt: String) {
        val text = TextView(this).apply {
            this.text = "Copy this prompt into any AI image generator. Attach the app icon for a closer match.\n\n" + prompt
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        MaterialAlertDialogBuilder(this).setTitle("Generate a banner with AI")
            .setView(ScrollView(this).apply { addView(text) })
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy prompt") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AniHome banner prompt", prompt))
                Toast.makeText(this, "Prompt copied. Paste it into your AI, then choose the saved image here.", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun load(uri: Uri) {
        val request = ++imageRequest
        save.isEnabled = false
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val largest = maxOf(info.size.width, info.size.height)
                        if (largest > 2400) decoder.setTargetSampleSize((largest + 2399) / 2400)
                    } else decodeLegacy(uri)
                }.getOrNull()
            }
            if (request != imageRequest) { bitmap?.recycle(); return@launch }
            if (bitmap == null) Toast.makeText(this@TileArtworkActivity, "Couldn't read this image. Choose another image.", Toast.LENGTH_LONG).show()
            else { selectedUri = uri; crop.setImage(bitmap); save.isEnabled = true; choose.text = "Gallery" }
        }
    }

    private fun decodeLegacy(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val image = contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Invalid image")
        val orientation = contentResolver.openInputStream(uri).use {
            if (it == null) ExifInterface.ORIENTATION_NORMAL else ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return image
        return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true).also { if (it != image) image.recycle() }
    }

    private fun saveArtwork() {
        val bitmap = crop.exportBitmap() ?: return
        save.isEnabled = false
        lifecycleScope.launch {
            val success = try { withContext(Dispatchers.IO) {
                val directory = File(filesDir, "tile-artwork").apply { mkdirs() }
                val file = File(directory, "tile-$tileId-${System.currentTimeMillis()}.jpg")
                runCatching {
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) }
                    val store = TileStore(this@TileArtworkActivity)
                    if (store.byId(tileId) == null) { file.delete(); error("Tile removed") }
                    store.setArtwork(tileId, file.absolutePath)
                    TileEvents.notifyChanged()
                }.onFailure { file.delete() }.isSuccess
            } } finally { bitmap.recycle() }
            if (success) finish() else { save.isEnabled = true; Toast.makeText(this@TileArtworkActivity, "Couldn't save the tile image.", Toast.LENGTH_LONG).show() }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private const val EXTRA_ID = "tile_id"
        fun intent(context: Context, tileId: Long) = Intent(context, TileArtworkActivity::class.java).putExtra(EXTRA_ID, tileId)
    }
}
