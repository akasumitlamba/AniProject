package app.anihome.launcher.ui

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
    fun enter(view: View, direction: String) {
        view.alpha = 0f
        view.post {
            view.alpha = 1f
            if (!ValueAnimator.areAnimatorsEnabled()) return@post
            val distance = (if (direction == "UP" || direction == "DOWN") view.height else view.width).toFloat()
            view.translationX = when (direction) { "RIGHT" -> -distance; "LEFT" -> distance; else -> 0f }
            view.translationY = when (direction) { "UP" -> distance; "DOWN" -> -distance; else -> 0f }
            view.animate().translationX(0f).translationY(0f).setDuration(190).setInterpolator(DecelerateInterpolator()).start()
        }
    }
}
