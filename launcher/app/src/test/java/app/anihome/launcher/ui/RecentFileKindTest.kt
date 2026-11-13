package app.anihome.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFileKindTest {
    @Test fun recognizesProviderMimeWithoutFilenameExtension() {
        assertEquals(RecentFileKind.PDF, RecentFileKind.from("Download", "application/pdf"))
        assertEquals(RecentFileKind.DOCUMENT, RecentFileKind.from("Report", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(RecentFileKind.SPREADSHEET, RecentFileKind.from("Budget", "application/vnd.oasis.opendocument.spreadsheet"))
        assertEquals(RecentFileKind.PRESENTATION, RecentFileKind.from("Slides", "application/vnd.ms-powerpoint"))
        assertEquals(RecentFileKind.IMAGE, RecentFileKind.from("Photo", "image/heic"))
        assertEquals(RecentFileKind.APP, RecentFileKind.from("Installer", "application/vnd.android.package-archive"))
    }
    @Test fun genericDownloadsUseCaseInsensitiveExtensionFallback() {
        mapOf("Book.PDF" to RecentFileKind.PDF, "App.APK" to RecentFileKind.APP,
            "Notes.docx" to RecentFileKind.DOCUMENT, "Sheet.csv" to RecentFileKind.SPREADSHEET,
            "Slides.pptx" to RecentFileKind.PRESENTATION, "Photo.PNG" to RecentFileKind.IMAGE,
            "Clip.mkv" to RecentFileKind.VIDEO, "Voice.opus" to RecentFileKind.AUDIO,
            "Backup.7z" to RecentFileKind.ARCHIVE, "Notes.md" to RecentFileKind.TEXT,
            "Unknown" to RecentFileKind.FILE).forEach { (name, expected) ->
            assertEquals(name, expected, RecentFileKind.from(name, "application/octet-stream"))
        }
    }
    @Test fun mimeParametersDoNotHideTheFileType() {
        assertEquals(RecentFileKind.PDF, RecentFileKind.from("Book", "APPLICATION/PDF; charset=binary"))
    }
}
