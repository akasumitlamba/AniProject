package app.anihome.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** Cap against the parent's actual available width, after page padding and safe insets. */
class DrawerSearchBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) widthMeasureSpec else
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY)
        super.onMeasure(capped, heightMeasureSpec)
    }
}
