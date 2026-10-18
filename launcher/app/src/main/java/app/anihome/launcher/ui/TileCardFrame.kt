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
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width / CropGeometry.ASPECT).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
