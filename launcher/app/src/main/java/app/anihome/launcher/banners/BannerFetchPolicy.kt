package app.anihome.launcher.banners

/** Pure request policy: loading offline never grants network access; an explicit retry can reset a pause. */
object BannerFetchPolicy {
    const val COOLDOWN_MILLIS = 15 * 60 * 1000L
    data class Pause(val untilMillis: Long = 0, val status: Int = 0)
    fun afterHttpFailure(status: Int, nowMillis: Long): Pause =
        if (status in setOf(401, 403, 404, 410, 429)) Pause(nowMillis + COOLDOWN_MILLIS, status) else Pause()
    fun permits(pause: Pause, nowMillis: Long, explicitRetry: Boolean = false): Boolean =
        explicitRetry || nowMillis >= pause.untilMillis
    fun reason(status: Int) = when (status) {
        401 -> "GitHub access is invalid or expired."
        403 -> "GitHub denied access or limited requests."
        404 -> "The banner source is private or unavailable."
        410 -> "The banner source is no longer available."
        429 -> "GitHub's request limit was reached."
        else -> "GitHub is unavailable."
    }
}

class BannerApiException(val httpStatus: Int?, val paused: Boolean = false, val friendlyReason: String =
    if (httpStatus == null) "This banner couldn't be downloaded." else BannerFetchPolicy.reason(httpStatus)) :
    java.io.IOException(friendlyReason)
