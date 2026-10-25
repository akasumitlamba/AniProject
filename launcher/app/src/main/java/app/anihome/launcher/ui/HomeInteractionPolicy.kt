package app.anihome.launcher.ui

import kotlin.math.abs
import kotlin.math.roundToInt

internal object HomeInteractionPolicy {
    enum class Swipe(val label: String) { UP("Swipe up"), DOWN("Swipe down"), LEFT("Swipe left"), RIGHT("Swipe right") }
    enum class Action(val label: String) { NONE("Do nothing"), APPS("Applications"), RECENTS("Recent apps"), NOTIFICATIONS("Notifications"), SEARCH("Search"), LOCK("Lock screen") }
    fun defaultAction(direction: Swipe): Action = when (direction) {
        Swipe.UP -> Action.RECENTS
        Swipe.LEFT -> Action.APPS
        Swipe.RIGHT -> Action.NOTIFICATIONS
        Swipe.DOWN -> Action.NONE
    }
    fun action(stored: String?, direction: Swipe, tablet: Boolean = true): Action = Action.entries.firstOrNull { it.name == stored }
        ?: if (tablet) defaultAction(direction) else when (direction) {
            Swipe.UP -> Action.APPS
            Swipe.DOWN -> Action.NOTIFICATIONS
            else -> Action.NONE
        }
    fun swipe(dx: Float, dy: Float, velocityX: Float, velocityY: Float, distance: Float, velocity: Float): Swipe? {
        if (abs(dx) >= distance && abs(dx) > abs(dy) * 1.5f && abs(velocityX) >= velocity && dx * velocityX > 0) return if (dx > 0) Swipe.RIGHT else Swipe.LEFT
        if (abs(dy) >= distance && abs(dy) > abs(dx) * 1.5f && abs(velocityY) >= velocity && dy * velocityY > 0) return if (dy > 0) Swipe.DOWN else Swipe.UP
        return null
    }
    fun gestureAllowed(direction: Swipe, canScrollUp: Boolean, canScrollDown: Boolean, scrolled: Boolean): Boolean = when (direction) {
        Swipe.UP -> !canScrollDown && !scrolled
        Swipe.DOWN -> !canScrollUp && !scrolled
        else -> true
    }
    fun validMove(from: Int, to: Int, size: Int) = from in 0 until size && to in 0 until size && from != to
    fun scrollStep(y: Float, height: Int, edge: Int, maxStep: Int): Int {
        if (height <= 0 || edge <= 0) return 0
        val zone = edge.coerceAtMost(height / 2).coerceAtLeast(1)
        val factor = when {
            y < zone -> -((zone - y) / zone).coerceAtMost(1f)
            y > height - zone -> ((y - (height - zone)) / zone).coerceAtMost(1f)
            else -> 0f
        }
        return (factor * maxStep).roundToInt()
    }
}
