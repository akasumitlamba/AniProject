package app.anihome.launcher.ui
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.TextView
import app.anihome.launcher.R
internal object BadgeUi {
    fun create(context: Context) = TextView(context).apply {
        setTextColor(-1); textSize = 11f; gravity = Gravity.CENTER; minWidth = ArtworkUi.dp(context, 20)
        setPadding(ArtworkUi.dp(context, 4), 0, ArtworkUi.dp(context, 4), 0)
        setBackgroundResource(R.drawable.bg_notification_badge); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }
    fun bind(view: TextView, count: Int) {
        view.text = if (count > 99) "99+" else count.toString()
        view.visibility = if (count > 0) View.VISIBLE else View.GONE
    }
}
