package app.anihome.launcher

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.data.*
import app.anihome.launcher.ui.*

/** Runs on an isolated QA device; board preferences use their own fixture storage. */
internal object HomeLayoutRegression {
    fun run(test: Instrumentation): String {
        var checks = 0
        fun verify(ok: Boolean, message: String) { check(ok) { message }; checks++ }
        val widgetPrefs = test.targetContext.getSharedPreferences("home_widgets", Context.MODE_PRIVATE)
        val savedWidgets = widgetPrefs.getString("items", "[]")
        widgetPrefs.edit().putString("items", "[{\"id\":-2,\"columns\":2,\"rows\":2,\"kind\":\"clock\"}]").commit()
        val home = test.startActivitySync(Intent(test.targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeActivity
        widgetPrefs.edit().putString("items", savedWidgets).commit()
        test.waitForIdleSync()
        val fixture = object : android.content.ContextWrapper(home) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("layout-regression-$name", mode)
        }
        fixture.getSharedPreferences("home_board", 0).edit().clear().commit()
        test.runOnMainSync {
            val grid = RecyclerView(home)
            val tiles = TileAdapter({}, {}, {}, {})
            val widgets = HomeActivity::class.java.getDeclaredField("widgets").apply { isAccessible = true }.get(home) as WidgetController
            val board = HomeBoardAdapter(fixture, tiles, widgets)
            val items = (1L..4L).map { HomeTile(it, HomeTile.Kind.WEB, "Fixture $it", url = "https://fixture.invalid/$it", position = it.toInt()) }
            tiles.submitList(items)
            board.loaded = true
            val original = board.pack(8)
            tiles.submitList(items + HomeTile(5, HomeTile.Kind.WEB, "Added", url = "https://fixture.invalid/5", position = 5))
            val added = board.pack(8).associateBy { it.key }
            verify(original.all { added[it.key] == it }, "adding an app preserves existing tile and widget cells")
            val stable = board.pack(8)
            val saved = fixture.getSharedPreferences("home_board", 0).getString("cells", "")
            board.beginDrag()
            verify(board.previewMove("tile:1", 2, 0, 8), "drag updates before drop")
            verify(board.placements[1] != stable[1], "neighbor moves before drop")
            verify(board.pack(8) == board.placements, "measurement retains live preview")
            verify(fixture.getSharedPreferences("home_board", 0).getString("cells", "") == saved, "preview does not persist")
            board.finishDrag(true)
            verify(board.placements == stable, "cancel restores every cell")
            board.beginDrag(); board.previewMove("tile:1", 2, 0, 8); board.finishDrag(false)
            val restored = HomeBoardAdapter(fixture, tiles, widgets).pack(8)
            verify(restored == board.placements, "drop persists preview without a second rearrangement")
            val layout = HomeBoardLayoutManager(home, board) { false }
            grid.layoutManager = layout; grid.adapter = board
            grid.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.AT_MOST))
            grid.layout(0, 0, 1000, grid.measuredHeight)
            board.beginDrag()
            val moving = board.placements.first()
            val neighbor = board.placements.first { it.key != moving.key }
            val neighborView = grid.findViewHolderForAdapterPosition(1)!!.itemView
            val beforeX = neighborView.left; val beforeY = neighborView.top
            board.previewMove(moving.key, neighbor.x, neighbor.y, layout.columns)
            layout.preview(moving.key)
            verify(beforeX != neighborView.left || beforeY != neighborView.top, "preview changes attached neighbor bounds")
            // Simulate a rebind canceling a neighbor halfway through its animation.
            neighborView.animate().cancel()
            neighborView.translationX = 17f; neighborView.translationY = 21f
            layout.settlePreview(moving.key)
            verify(neighborView.translationX == 0f && neighborView.translationY == 0f, "interrupted preview offsets clear at drop")
            board.finishDrag(false)
            grid.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.AT_MOST))
            grid.layout(0, 0, 1000, grid.measuredHeight)
            verify((0 until grid.childCount).all { grid.getChildAt(it).translationX == 0f && grid.getChildAt(it).translationY == 0f }, "rebound board has no fractional movement offsets")
            verify((0 until grid.childCount).all { index ->
                val view = grid.getChildAt(index)
                val p = board.placements[grid.getChildAdapterPosition(view)]
                view.left == grid.paddingLeft + (p.x * layout.cellWidth).toInt() + ArtworkUi.dp(home, 8) &&
                    view.top == grid.paddingTop + (p.y * layout.cellHeight).toInt() + ArtworkUi.dp(home, 8)
            }, "every dropped item rests on its grid cell")
        }
        val icon = IconCache.dockIcon(test.targetContext, test.targetContext.packageName, null)!!
        verify(Color.alpha(icon.getPixel(0, 0)) == 0, "normalized icon has transparent corners")
        verify(Color.alpha(icon.getPixel(icon.width / 2, icon.height / 2)) > 0, "normalized icon keeps center artwork")
        fixture.getSharedPreferences("home_board", 0).edit().clear().commit()
        test.runOnMainSync { home.finish() }
        return "$checks home layout checks passed"
    }
}
