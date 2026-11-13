package app.anihome.launcher.ui

import java.util.Locale

internal enum class RecentFileKind {
    PDF, DOCUMENT, SPREADSHEET, PRESENTATION, IMAGE, VIDEO, AUDIO, APP, ARCHIVE, TEXT, FILE;

    companion object {
        fun from(name: String, mime: String): RecentFileKind {
            val type = mime.lowercase(Locale.ROOT).substringBefore(';').trim()
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            // Providers sometimes return octet-stream or no MIME type, so retain extension fallback.
            return when {
                type == "application/pdf" || extension == "pdf" -> PDF
                type == "application/vnd.android.package-archive" || extension in setOf("apk", "apks", "xapk", "aab") -> APP
                type.startsWith("image/") || extension in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "svg") -> IMAGE
                type.startsWith("video/") || extension in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp") -> VIDEO
                type.startsWith("audio/") || extension in setOf("mp3", "m4a", "ogg", "opus", "flac", "wav", "aac") -> AUDIO
                "spreadsheet" in type || "ms-excel" in type || extension in setOf("xls", "xlsx", "ods", "csv") -> SPREADSHEET
                "presentation" in type || "powerpoint" in type || extension in setOf("ppt", "pptx", "odp") -> PRESENTATION
                "wordprocessing" in type || type == "application/msword" || extension in setOf("doc", "docx", "odt", "rtf") -> DOCUMENT
                type in setOf("application/zip", "application/gzip", "application/x-7z-compressed", "application/vnd.rar", "application/x-rar-compressed", "application/x-tar") || extension in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz") -> ARCHIVE
                type.startsWith("text/") || extension in setOf("txt", "md", "json", "xml", "log", "kt", "java", "js", "py", "html") -> TEXT
                else -> FILE
            }
        }
    }
}
