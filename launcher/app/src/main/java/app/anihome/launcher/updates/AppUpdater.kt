package app.anihome.launcher.updates

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Manual, anonymous updates. Only a correctly signed AniHome package reaches Android's installer. */
internal class AppUpdater(private val activity: AppCompatActivity) {
    private val directory get() = File(activity.cacheDir, "app-updates").apply { mkdirs() }
    private val pending get() = File(directory, "AniHome-update.apk")
    private var job: Job? = null
    private var messageAnchor: java.lang.ref.WeakReference<android.view.View>? = null
    private val installer = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val installPermission = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (activity.packageManager.canRequestPackageInstalls()) activity.lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { verifyPackage(pending) } }
                .onSuccess { if (it) install() else message("AniHome is up to date.") }
                .onFailure { message("Update couldn’t be verified. Please check for updates again.") }
        } else message("Update saved. Allow app installs to continue when you’re ready.")
    }
    private fun message(text: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val bar = Snackbar.make(messageAnchor?.get()?.takeIf { it.isAttachedToWindow } ?: activity.findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG)
        bar.view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text).apply {
            setSingleLine(false); maxLines = 3; ellipsize = null
        }
        bar.show()
    }
    fun check(anchor: android.view.View? = null) {
        messageAnchor = anchor?.let { java.lang.ref.WeakReference(it) }
        if (job?.isActive == true) return
        message("Checking for an AniHome update…")
        job = activity.lifecycleScope.launch {
            try {
                val candidate = withContext(Dispatchers.IO) {
                    val root = JSONObject(readApi())
                    require(!root.optBoolean("draft") && !root.optBoolean("prerelease"))
                    val rows = root.getJSONArray("assets")
                    val assets = (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
                        AppUpdatePolicy.Asset(row.getString("name"), row.getString("browser_download_url"), row.optLong("size", -1), row.optString("digest").takeIf { it.isNotBlank() && it != "null" })
                    } }
                    AppUpdatePolicy.choose(assets) ?: error("AniHome asset unavailable")
                }
                val alreadyInstalled = withContext(Dispatchers.IO) {
                    candidate.digest?.let { expected ->
                        val hash = MessageDigest.getInstance("SHA-256")
                        File(activity.applicationInfo.sourceDir).inputStream().use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) { break }
                                hash.update(buffer, 0, count)
                            }
                        }
                        AppUpdatePolicy.matchesDigest(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, expected)
                    } ?: false
                }
                if (alreadyInstalled) { message("AniHome is up to date."); return@launch }
                message("Downloading AniHome update…")
                val newer = withContext(Dispatchers.IO) { download(candidate); verifyPackage(pending) }
                if (!newer) { pending.delete(); message("AniHome is up to date."); return@launch }
                if (activity.packageManager.canRequestPackageInstalls()) install()
                else {
                    message("Allow AniHome to install this update, then return here.")
                    installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("Updates unavailable. Try again later.") }
        }
    }
    private suspend fun readApi(): String {
        val connection = URL(AppUpdatePolicy.LATEST_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false; connection.connectTimeout = 10_000; connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "AniHome-Updater")
        try {
            require(connection.responseCode == 200)
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) { break }
                    require(output.size() + count <= 2 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
    private suspend fun download(asset: AppUpdatePolicy.Asset) {
        var url = asset.url
        val partial = File(directory, "download.part")
        try {
            repeat(6) {
                currentCoroutineContext().ensureActive()
                require(AppUpdatePolicy.allowedDownloadUrl(url))
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false; connection.connectTimeout = 10_000; connection.readTimeout = 20_000
                try {
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) { url = URL(URL(url), connection.getHeaderField("Location") ?: error("Missing redirect")).toString(); return@repeat }
                    require(status == 200)
                    require(connection.contentLengthLong <= AppUpdatePolicy.MAX_APK_BYTES)
                    val hash = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    val deadline = System.nanoTime() + 180_000_000_000L
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                require(System.nanoTime() < deadline)
                                val count = input.read(buffer)
                                if (count < 0) { break }
                                total += count
                                require(total <= AppUpdatePolicy.MAX_APK_BYTES && total <= asset.bytes)
                                hash.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    require(total == asset.bytes)
                    val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    require(AppUpdatePolicy.matchesDigest(actual, asset.digest))
                    pending.delete(); check(partial.renameTo(pending)); return
                } finally { connection.disconnect() }
            }
            error("Too many redirects")
        } finally { partial.delete() }
    }
    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File): Boolean {
        require(file.isFile && file.length() in 1..AppUpdatePolicy.MAX_APK_BYTES)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = activity.packageManager
        val update = pm.getPackageArchiveInfo(file.path, flags) ?: error("Invalid APK")
        val installed = pm.getPackageInfo(activity.packageName, flags)
        require(update.packageName == activity.packageName)
        require((update.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0)
        fun signers(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }?.toSet().orEmpty()
        val expected = signers(installed)
        require(expected.isNotEmpty() && signers(update) == expected)
        val version = if (Build.VERSION.SDK_INT >= 28) update.longVersionCode else update.versionCode.toLong()
        val current = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        return version > current
    }
    private fun install() {
        if (!pending.exists()) { message("Please check for updates again."); return }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.history-transfer", pending)
        installer.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
