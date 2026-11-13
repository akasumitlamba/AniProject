package app.anihome.launcher.updates

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs AniBrave straight from AniProject's public GitHub release, with no store in between.
 * The download must match GitHub's published SHA-256 and be signed with AniBrave's own key
 * before Android's installer is shown.
 */
internal class AniBraveInstaller(private val activity: AppCompatActivity) {
    companion object {
        const val PACKAGE = "app.anibrave.main"
        private const val LATEST = "https://api.github.com/repos/akasumitlamba/AniProject/releases/latest"
        private const val REPOSITORY_PATH = "/akasumitlamba/AniProject/releases/download/"
        private const val MAX_BYTES = 400L * 1024 * 1024
        /** SHA-256 of AniBrave's release signing certificate. */
        private const val SIGNER = "1463257b778fbd8ef8ab4fdcbbd9ebae21990aefd934497ee70b4b7136216045"
        private val ASSET = Regex("AniBrave-[0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9.-]+)?-arm64-v8a\\.apk")
        fun installed(pm: PackageManager) = runCatching { pm.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)
    }
    private val file get() = File(File(activity.cacheDir, "app-updates").apply { mkdirs() }, "AniBrave.apk")
    private var job: Job? = null
    private val permission = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (activity.packageManager.canRequestPackageInstalls() && file.exists()) install()
    }

    /** Explains, then downloads and installs. */
    fun offer() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Install AniBrave?")
            .setMessage("AniBrave is the browser AniHome opens websites in: per-site playback speed, fullscreen site shortcuts and watch history. It downloads directly from AniHome's GitHub release (about 200 MB) and is checked before installing.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Download") { _, _ -> start() }
            .show()
    }

    private fun start() {
        if (job?.isActive == true) return
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            Toast.makeText(activity, "AniBrave needs a 64-bit ARM device.", Toast.LENGTH_LONG).show(); return
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true; setPadding(48, 24, 48, 8) }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity).setTitle("Downloading AniBrave").setView(bar)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }.setCancelable(false).show()
        job = activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { download { percent -> activity.runOnUiThread { bar.isIndeterminate = false; bar.progress = percent } } }
                dialog.dismiss()
                if (activity.packageManager.canRequestPackageInstalls()) install()
                else {
                    Toast.makeText(activity, "Allow AniHome to install apps, then come back.", Toast.LENGTH_LONG).show()
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
            } catch (cancel: CancellationException) { dialog.dismiss(); file.delete(); throw cancel }
            catch (_: Exception) { dialog.dismiss(); file.delete(); Toast.makeText(activity, "AniBrave couldn't be downloaded. Check your connection and try again.", Toast.LENGTH_LONG).show() }
        }
    }

    private suspend fun download(progress: (Int) -> Unit) {
        val release = JSONObject(fetch(LATEST))
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.single { ASSET.matches(it.getString("name")) }
        val size = asset.getLong("size"); require(size in 1..MAX_BYTES)
        val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
        require(digest.matches(Regex("[0-9a-f]{64}")))
        var url = asset.getString("browser_download_url")
        require(URL(url).let { it.protocol == "https" && it.host == "github.com" && it.path.startsWith(REPOSITORY_PATH) })
        val partial = File(file.parentFile, "AniBrave.part")
        try {
            repeat(6) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                try {
                    if (connection.responseCode in 300..399) {
                        url = URL(URL(url), connection.getHeaderField("Location")).toString()
                        require(URL(url).protocol == "https" && URL(url).host.let { it == "github.com" || it.endsWith(".githubusercontent.com") })
                        return@repeat
                    }
                    require(connection.responseCode == 200)
                    val hash = MessageDigest.getInstance("SHA-256"); var total = 0L
                    connection.inputStream.use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            total += count; require(total <= size)
                            hash.update(buffer, 0, count); output.write(buffer, 0, count)
                            progress((total * 100 / size).toInt())
                        }
                    } }
                    require(total == size)
                    require(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == digest)
                    verify(partial)
                    file.delete(); check(partial.renameTo(file)); return
                } finally { connection.disconnect() }
            }
            error("Too many redirects")
        } finally { partial.delete() }
    }

    @Suppress("DEPRECATION")
    private fun verify(apk: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = activity.packageManager.getPackageArchiveInfo(apk.path, flags) ?: error("Invalid APK")
        require(info.packageName == PACKAGE)
        val signers = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty()
            .map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }
        require(signers == listOf(SIGNER))
    }

    private fun fetch(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json"); connection.setRequestProperty("User-Agent", "AniHome")
        try { require(connection.responseCode == 200); return connection.inputStream.bufferedReader().use { it.readText() } }
        finally { connection.disconnect() }
    }

    private fun install() {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.history-transfer", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
