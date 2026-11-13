package app.anihome.launcher.updates

import java.net.URI

internal object AppUpdatePolicy {
    const val REPOSITORY = "akasumitlamba/AniProject"
    const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    data class Asset(val name: String, val url: String, val bytes: Long, val digest: String?)
    private val name = Regex("AniHome-Launcher-[0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9.-]+)?-release\\.apk")
    fun choose(assets: List<Asset>): Asset? = assets.filter { asset ->
        name.matches(asset.name) && asset.bytes in 1..MAX_APK_BYTES && runCatching {
            val url = URI(asset.url)
            url.scheme == "https" && url.host == "github.com" && url.rawUserInfo == null && url.port == -1 &&
                url.rawQuery == null && url.rawFragment == null &&
                url.path.startsWith("/$REPOSITORY/releases/download/") && url.path.substringAfterLast('/') == asset.name
        }.getOrDefault(false)
    }.singleOrNull()
    fun allowedDownloadUrl(value: String): Boolean = runCatching {
        val url = URI(value)
        url.scheme == "https" && url.rawUserInfo == null && url.port == -1 &&
            url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }.getOrDefault(false)
    fun matchesDigest(actual: String, expected: String?): Boolean = expected == null ||
        expected.matches(Regex("sha256:[a-fA-F0-9]{64}")) && expected.substringAfter(':').equals(actual, true)
}
