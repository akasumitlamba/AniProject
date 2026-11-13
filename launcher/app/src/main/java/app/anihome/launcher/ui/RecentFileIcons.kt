package app.anihome.launcher.ui

import app.anihome.launcher.R

internal object RecentFileIcons {
    private fun kind(file: RecentFiles.File) = RecentFileKind.from(file.name, file.mime)
    fun resource(file: RecentFiles.File): Int = when (kind(file)) {
        RecentFileKind.PDF -> R.drawable.file_type_pdf
        RecentFileKind.DOCUMENT -> R.drawable.file_type_document
        RecentFileKind.SPREADSHEET -> R.drawable.file_type_sheet
        RecentFileKind.PRESENTATION -> R.drawable.file_type_slides
        RecentFileKind.IMAGE -> R.drawable.file_type_image
        RecentFileKind.VIDEO -> R.drawable.file_type_video
        RecentFileKind.AUDIO -> R.drawable.file_type_audio
        RecentFileKind.APP -> R.drawable.file_type_app
        RecentFileKind.ARCHIVE -> R.drawable.file_type_archive
        RecentFileKind.TEXT -> R.drawable.file_type_text
        RecentFileKind.FILE -> R.drawable.file_type_file
    }
}
