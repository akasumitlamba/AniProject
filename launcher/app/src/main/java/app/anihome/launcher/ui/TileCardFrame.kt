package app.anihome.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Measures the artwork before the containing RecyclerView measures its complete row and label. */
class TileCardFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    var iconMode = false
    var iconLabelSpace = 0
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (iconMode) minOf(width, ArtworkUi.dp(context, 64), if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else (MeasureSpec.getSize(heightMeasureSpec) - iconLabelSpace).coerceAtLeast(ArtworkUi.dp(context, 24))) else (width / CropGeometry.ASPECT).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
