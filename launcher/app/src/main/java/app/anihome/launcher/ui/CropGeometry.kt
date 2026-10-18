package app.anihome.launcher.ui

import kotlin.math.max

/** Geometry shared by the preview and its exported image, independent of Android drawing. */
internal object CropGeometry {
    const val ASPECT = 1.62f
    fun minimumScale(frameWidth: Float, frameHeight: Float, imageWidth: Int, imageHeight: Int): Float =
        max(frameWidth / imageWidth, frameHeight / imageHeight)

    // Floating-point roundoff can make a perfectly fitted image a fraction smaller than the frame.
    fun boundedOffset(offset: Float, start: Float, end: Float, scaledImageSize: Float): Float =
        offset.coerceIn(minOf(end - scaledImageSize, start), start)
}
