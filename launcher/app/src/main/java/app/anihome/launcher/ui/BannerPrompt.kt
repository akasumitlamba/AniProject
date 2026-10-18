package app.anihome.launcher.ui

/** A portable prompt users can paste into their preferred image-generation AI. */
internal object BannerPrompt {
    fun forTile(name: String, identity: String?): String = buildString {
        append("Create one standalone landscape banner for my AniHome home-screen tile. ")
        append("The exact app or website name is \"").append(name.trim()).append("\".")
        identity?.takeIf { it.isNotBlank() }?.let { append(" Identify this destination by: ").append(it.trim()).append(".") }
        append(" Use a 1.62:1 aspect ratio (for example 1296 × 800 pixels), with a full-bleed brand-colour background and a clear centred logo card composition. ")
        append("If I attach an original icon or reference, preserve its visual identity faithfully. Otherwise use only the correct identity of this exact app or website; ask me for a reference if it is ambiguous. ")
        append("Render the exact name clearly if you include text. Keep generous safe margins around the logo and name so I can crop or adjust the banner. ")
        append("Return a single finished image, with no border, frame, mockup, unrelated brands, collage, extra app tiles or invented logo.")
    }
}
