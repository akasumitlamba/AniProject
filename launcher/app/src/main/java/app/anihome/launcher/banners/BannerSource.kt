package app.anihome.launcher.banners

/** Public, anonymous catalog source. Paths remain constrained to the audited banner directory. */
internal object BannerSource {
    const val REPOSITORY = "akasumitlamba/AniProject"
    fun url(path: String): String {
        require(path == "catalog.json" || BannerRules.safeFile(path))
        return "https://raw.githubusercontent.com/$REPOSITORY/main/banners/$path"
    }
}
