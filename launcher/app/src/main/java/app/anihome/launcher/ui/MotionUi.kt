package app.anihome.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import androidx.core.view.OneShotPreDrawListener
import app.anihome.launcher.R

/** Short motion follows the invoking gesture and respects Android's animator setting. */
internal object MotionUi {
    /** Material 3 emphasized curves: fast start with a long settle reads as smooth on 60 Hz panels. */
    val EMPHASIZED_DECELERATE = android.view.animation.PathInterpolator(.05f, .7f, .1f, 1f)
    val EMPHASIZED_ACCELERATE = android.view.animation.PathInterpolator(.3f, 0f, .8f, .15f)
    val STANDARD = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)

    /** Zero when the user disabled animations, so end states apply immediately. */
    @Suppress("UNUSED_PARAMETER")
    fun duration(view: View, millis: Long): Long = if (ValueAnimator.areAnimatorsEnabled()) millis else 0L

    /** Slide a sheet back toward the edge it came from, then run [done] exactly once. */
    fun exit(view: View, direction: String, done: () -> Unit) {
        (view.getTag(R.id.motion_entry_state) as? Entrance)?.finish()
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow || view.width == 0) { done(); return }
        var ran = false
        fun once() { if (!ran) { ran = true; done() } }
        val distance = (if (direction == "UP" || direction == "DOWN") view.height else view.width).toFloat()
        view.animate().cancel()
        view.animate()
            .translationX(when (direction) { "RIGHT" -> -distance; "LEFT" -> distance; else -> 0f })
            .translationY(when (direction) { "UP" -> distance; "DOWN" -> -distance; else -> 0f })
            .alpha(.6f).setDuration(200).setInterpolator(EMPHASIZED_ACCELERATE).withLayer()
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = once()
                override fun onAnimationCancel(animation: Animator) = once()
            }).start()
    }

    /** Press feedback for custom tiles: a quick, interruptible scale that never sticks. */
    fun press(view: View, pressed: Boolean) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().scaleX(if (pressed) .96f else 1f).scaleY(if (pressed) .96f else 1f)
            .setDuration(if (pressed) 90 else 160).setInterpolator(STANDARD).start()
    }
    fun expand(parent: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled() || !parent.isLaidOut) return
        TransitionManager.beginDelayedTransition(parent, AutoTransition().apply {
            duration = 160; ordering = android.transition.TransitionSet.ORDERING_TOGETHER
        })
    }

    fun enter(view: View, direction: String, completed: () -> Unit = {}) {
        (view.getTag(R.id.motion_entry_state) as? Entrance)?.finish()
        view.animate().cancel()
        view.translationX = 0f; view.translationY = 0f; view.alpha = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) { completed(); return }
        Entrance(view, direction, completed).start()
    }

    /** Completion also releases drawer loads when Back, detach or a new entry cancels motion. */
    private class Entrance(val view: View, val direction: String, val completed: () -> Unit) : View.OnAttachStateChangeListener {
        private var finished = false
        private var preDraw: OneShotPreDrawListener? = null
        private val animate = Runnable {
            if (!finished) {
                if (!view.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) finish()
                else view.animate().translationX(0f).translationY(0f).setDuration(320)
                    .setInterpolator(EMPHASIZED_DECELERATE)
                    .withLayer().setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: Animator) = finish()
                        override fun onAnimationEnd(animation: Animator) = finish()
                    }).start()
            }
        }
        private val nextFrame = Runnable { if (!finished) view.postOnAnimation(animate) }
        fun start() {
            view.setTag(R.id.motion_entry_state, this)
            view.addOnAttachStateChangeListener(this)
            view.alpha = 0f
            preDraw = OneShotPreDrawListener.add(view) {
                preDraw = null
                if (!finished) {
                    view.alpha = 1f
                    val distance = (if (direction == "UP" || direction == "DOWN") view.height else view.width).toFloat()
                    view.translationX = when (direction) { "RIGHT" -> -distance; "LEFT" -> distance; else -> 0f }
                    view.translationY = when (direction) { "UP" -> distance; "DOWN" -> -distance; else -> 0f }
                    // Allow the cold first draw to finish before visible movement.
                    view.postOnAnimation(nextFrame)
                }
            }
        }
        fun finish() {
            if (finished) return
            finished = true
            preDraw?.removeListener(); preDraw = null
            view.removeCallbacks(nextFrame); view.removeCallbacks(animate)
            view.removeOnAttachStateChangeListener(this)
            view.animate().setListener(null).withEndAction(null).cancel()
            view.translationX = 0f; view.translationY = 0f; view.alpha = 1f
            if (view.getTag(R.id.motion_entry_state) === this) view.setTag(R.id.motion_entry_state, null)
            completed()
        }
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = finish()
    }
}
