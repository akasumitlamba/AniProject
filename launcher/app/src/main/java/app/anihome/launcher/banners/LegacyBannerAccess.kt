package app.anihome.launcher.banners

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore

/** Remove credentials retained by earlier private-catalog builds. Public refresh never uses them. */
internal object LegacyBannerAccess {
    fun clear(context: Context) {
        AtomicFile(File(context.noBackupFilesDir, "banner-store-access.enc")).delete()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("anihome-banner-store")
        }
    }
}
