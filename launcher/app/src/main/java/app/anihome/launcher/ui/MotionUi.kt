package app.anihome.launcher.ui

import androidx.core.view.doOnPreDraw
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.transition.AutoTransition
import android.transition.TransitionManager

/** Short motion follows the invoking gesture and respects Android's animator setting. */
internal object MotionUi {
    fun expand(parent: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled() || !parent.isLaidOut) return
        TransitionManager.beginDelayedTransition(parent, AutoTransition().apply { duration = 160; ordering = android.transition.TransitionSet.ORDERING_TOGETHER })
    }
    fun enter(view: View, direction: String, completed: () -> Unit = {}) {
        view.alpha = 0f
        view.doOnPreDraw {
            view.alpha = 1f
            if (!ValueAnimator.areAnimatorsEnabled()) { completed(); return@doOnPreDraw }
            val distance = (if (direction == "UP" || direction == "DOWN") view.height else view.width).toFloat()
            view.translationX = when (direction) { "RIGHT" -> -distance; "LEFT" -> distance; else -> 0f }
            view.translationY = when (direction) { "UP" -> distance; "DOWN" -> -distance; else -> 0f }
            // Let the initial layout/draw finish before starting the visible movement.
            // Otherwise a cold first frame consumes most of the animation duration.
            view.postOnAnimation {
                view.postOnAnimation {
                    view.animate().translationX(0f).translationY(0f).setDuration(280).setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).withLayer().withEndAction { completed() }.start()
                }
            }
        }
    }
}
