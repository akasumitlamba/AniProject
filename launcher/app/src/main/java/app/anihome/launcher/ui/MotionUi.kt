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
                else view.animate().translationX(0f).translationY(0f).setDuration(280)
                    .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f))
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
