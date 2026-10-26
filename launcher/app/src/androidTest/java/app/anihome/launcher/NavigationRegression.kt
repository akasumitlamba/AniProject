package app.anihome.launcher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.KeyEvent
import android.widget.FrameLayout
import app.anihome.launcher.ui.AppDrawerActivity
import app.anihome.launcher.ui.HomeActivity
import app.anihome.launcher.ui.MotionUi
import app.anihome.launcher.ui.NotificationPanel
import android.view.ViewGroup
import android.widget.TextView

/** Real view attachment, animation frames and activity Back navigation on a QA emulator. */
internal object NavigationRegression {
    fun run(test: Instrumentation): String {
        var checks = 0
        val failures = mutableListOf<String>()
        fun verify(ok: Boolean, name: String) { checks++; if (!ok) failures += name }
        val home = test.startActivitySync(Intent(test.targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var parent: FrameLayout
        lateinit var view: View
        test.runOnMainSync {
            parent = home.window.decorView as FrameLayout
            view = View(home)
            parent.addView(view, FrameLayout.LayoutParams(240, 180))
        }
        test.waitForIdleSync()
        var first = 0; var second = 0
        test.runOnMainSync {
            view.translationY = 47f
            MotionUi.enter(view, "RIGHT") { first++ }
            MotionUi.enter(view, "LEFT") { second++ }
        }
        SystemClock.sleep(700)
        test.runOnMainSync {
            verify(first == 1 && second == 1, "replaced entrances release both completion callbacks exactly once")
            verify(view.alpha == 1f && view.translationX == 0f && view.translationY == 0f, "replaced entrance ends in its resting geometry")
            parent.removeView(view)
            view = View(home); parent.addView(view, FrameLayout.LayoutParams(240,180))
        }
        test.waitForIdleSync()
        var detached = 0
        test.runOnMainSync {
            MotionUi.enter(view, "UP") { detached++ }
            parent.removeView(view)
        }
        SystemClock.sleep(500)
        test.runOnMainSync {
            verify(detached == 1, "detaching before pre-draw releases the entrance waiter")
            verify(view.alpha == 1f && view.translationY == 0f, "detached entrance does not leave an invisible translated view")
            parent.addView(view, FrameLayout.LayoutParams(240,180))
        }
        test.waitForIdleSync()
        var canceled = 0
        test.runOnMainSync { MotionUi.enter(view, "RIGHT") { canceled++ } }
        SystemClock.sleep(130)
        test.runOnMainSync { view.animate().cancel() }
        SystemClock.sleep(400)
        test.runOnMainSync {
            verify(canceled == 1, "canceling a running entrance releases the waiter")
            verify(view.translationX == 0f && view.translationY == 0f, "canceling entrance resets the interrupted translation")
            parent.removeView(view)
        }
        // Repeatedly enter the real drawer and press system Back, including during entry.
        repeat(6) { cycle ->
            val drawer = test.startActivitySync(Intent(test.targetContext, AppDrawerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(if (cycle % 2 == 0) 50 else 500)
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            SystemClock.sleep(250)
            test.runOnMainSync { verify(drawer.isFinishing || drawer.isDestroyed, "drawer Back cycle $cycle finishes the drawer") }
        }
        val drawer = test.startActivitySync(Intent(test.targetContext, AppDrawerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(500)
        val recreatedMonitor = test.addMonitor(AppDrawerActivity::class.java.name, null, false)
        test.runOnMainSync { drawer.findViewById<android.widget.EditText>(R.id.search).setText("camera"); drawer.recreate() }
        val recreated = test.waitForMonitorWithTimeout(recreatedMonitor, 5000)
        test.removeMonitor(recreatedMonitor)
        verify(recreated != null, "drawer recreates after a configuration change")
        if (recreated != null) {
            SystemClock.sleep(600)
            test.runOnMainSync {
                verify(recreated.findViewById<android.widget.EditText>(R.id.search).text.toString() == "camera", "search survives drawer recreation")
                val root = recreated.findViewById<View>(R.id.root)
                verify(root.alpha == 1f && root.translationX == 0f && root.translationY == 0f, "restored drawer is visible at rest")
                recreated.findViewById<View>(R.id.btn_back).performClick()
            }
            SystemClock.sleep(250)
            test.runOnMainSync { verify(recreated.isFinishing || recreated.isDestroyed, "toolbar Back closes the restored drawer") }
        }
        lateinit var panel: NotificationPanel
        fun nodes(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { nodes(v.getChildAt(it)) } else emptyList()
        fun panelRoot(): View {
            val field = NotificationPanel::class.java.getDeclaredField("dialog").apply { isAccessible = true }
            return (field.get(panel) as android.app.Dialog).window!!.decorView
        }
        test.runOnMainSync {
            val field = HomeActivity::class.java.getDeclaredField("notificationPanel").apply { isAccessible = true }
            panel = field.get(home) as NotificationPanel
            panel.showSettings()
        }
        SystemClock.sleep(400)
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(200)
        test.runOnMainSync {
            verify(!panel.isShowing, "Back closes Home settings")
            panel.show()
        }
        SystemClock.sleep(400)
        test.runOnMainSync {
            verify(nodes(panelRoot()).filterIsInstance<TextView>().any { it.text.toString() == "Notifications" }, "opening Notifications after Settings returns to Notifications")
        }
        repeat(4) { cycle ->
            test.runOnMainSync {
                for (section in listOf("Wi-Fi", "Bluetooth", "Settings", "Wi-Fi")) {
                    nodes(panelRoot()).first { it.contentDescription?.toString()?.startsWith("$section,") == true }.performClick()
                }
            }
            var settled = false
            val deadline = SystemClock.uptimeMillis() + 2000
            while (!settled && SystemClock.uptimeMillis() < deadline) {
                test.runOnMainSync { settled = panel.isShowing && nodes(panelRoot()).all { it.translationX == 0f && it.translationY == 0f } }
                if (!settled) SystemClock.sleep(20)
            }
            verify(settled, "rapid panel section changes settle cycle $cycle")
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); SystemClock.sleep(150)
            test.runOnMainSync { verify(!panel.isShowing, "Back dismisses animated panel cycle $cycle"); panel.show() }
        }
        test.runOnMainSync { panel.dismiss(); home.finish() }
        check(failures.isEmpty()) { "$checks navigation/animation checks; failures: ${failures.joinToString("; ")}" }
        return "$checks navigation/animation checks passed"
    }
}
