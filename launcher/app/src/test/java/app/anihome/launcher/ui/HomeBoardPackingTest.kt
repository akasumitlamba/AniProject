package app.anihome.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class HomeBoardPackingTest {
    private fun checkNoOverlap(placements: List<HomeBoardPacking.Placement>, columns: Int) {
        val occupied = hashSetOf<Pair<Int, Int>>()
        for (p in placements) {
            assertTrue(p.x >= 0 && p.x + p.columns <= columns && p.y >= 0)
            for (y in p.y until p.y + p.rows) for (x in p.x until p.x + p.columns) assertTrue(occupied.add(x to y))
        }
    }
    @Test fun widgetCanMoveBetweenBanners() {
        val items = (0..3).map { HomeBoardPacking.Item("tile:$it", 2, 2) } + HomeBoardPacking.Item("widget:1", 2, 4)
        val result = HomeBoardPacking.pack(items, 8, mapOf("widget:1" to HomeBoardPacking.Cell(2, 0)), "widget:1")
        assertEquals(HomeBoardPacking.Cell(2, 0), result.last().let { HomeBoardPacking.Cell(it.x, it.y) })
        assertTrue(result.any { it.key.startsWith("tile") && it.x > 2 && it.y == 0 })
        checkNoOverlap(result, 8)
    }
    @Test fun intentionalEmptySpaceSurvivesReload() {
        val items = listOf(HomeBoardPacking.Item("tile", 2, 2), HomeBoardPacking.Item("widget", 2, 3))
        val result = HomeBoardPacking.pack(items, 8, mapOf("widget" to HomeBoardPacking.Cell(4, 6)), "widget")
        val saved = result.associate { it.key to HomeBoardPacking.Cell(it.x, it.y) }
        assertEquals(result, HomeBoardPacking.pack(items, 8, saved))
        assertEquals(6, result.last().y)
    }
    @Test fun resizedWidgetKeepsAnchorAndMovesOnlyCollisions() {
        val items = listOf(HomeBoardPacking.Item("tile", 2, 2), HomeBoardPacking.Item("widget", 4, 4), HomeBoardPacking.Item("far", 2, 2))
        val result = HomeBoardPacking.pack(items, 8, mapOf("tile" to HomeBoardPacking.Cell(2, 0), "widget" to HomeBoardPacking.Cell(0, 0), "far" to HomeBoardPacking.Cell(6, 6)), "widget")
        assertEquals(0, result[1].x); assertEquals(0, result[1].y)
        assertEquals(6, result[2].x); assertEquals(6, result[2].y)
        checkNoOverlap(result, 8)
    }
    @Test fun narrowOrientationReflowsWithoutDroppingItems() {
        val items = listOf(HomeBoardPacking.Item("wide", 8, 2), HomeBoardPacking.Item("widget", 2, 4), HomeBoardPacking.Item("tile", 2, 2))
        val result = HomeBoardPacking.pack(items, 4, mapOf("widget" to HomeBoardPacking.Cell(6, 0)))
        assertEquals(3, result.size); checkNoOverlap(result, 4)
    }
    @Test fun smallerItemsFillSpaceBesideTallWidgets() {
        val items = listOf(HomeBoardPacking.Item("widget", 2, 4), HomeBoardPacking.Item("first", 2, 2), HomeBoardPacking.Item("second", 2, 2))
        val result = HomeBoardPacking.pack(items, 4, emptyMap())
        assertEquals(2, result[2].x); assertEquals(2, result[2].y)
        checkNoOverlap(result, 4)
    }
    @Test fun deletingRowsBringsOffscreenItemsUpInVisualOrder() {
        val items = (0..19).map { HomeBoardPacking.Item("tile:$it", 2, 2) }
        val before = HomeBoardPacking.pack(items, 4, emptyMap())
        val remaining = items.drop(16)
        val compact = HomeBoardPacking.compact(remaining, 4, before)
        assertEquals(listOf(0, 0, 2, 2), compact.map { it.y })
        checkNoOverlap(compact, 4)
    }
    @Test fun deletionCompactsMixedWidgetsAndIconsWithoutOverlap() {
        val items = listOf(HomeBoardPacking.Item("widget", 2, 4), HomeBoardPacking.Item("icon", 1, 1), HomeBoardPacking.Item("tile", 2, 2))
        val before = HomeBoardPacking.pack(items, 6, mapOf("widget" to HomeBoardPacking.Cell(0, 8)))
        val compact = HomeBoardPacking.compact(items, 6, before)
        assertTrue(compact.maxOf { it.y + it.rows } <= 4)
        checkNoOverlap(compact, 6)
    }
    @Test fun invalidSavedCellsFallBackSafely() {
        val result = HomeBoardPacking.pack(listOf(HomeBoardPacking.Item("widget", 2, 2)), 4, mapOf("widget" to HomeBoardPacking.Cell(-2, Int.MAX_VALUE)))
        assertEquals(0, result.single().x); assertEquals(0, result.single().y)
    }
}
