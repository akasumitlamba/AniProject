package app.anihome.launcher.ui

/** Shared cell occupancy for banners and widgets; saved empty space remains intentional. */
internal object HomeBoardPacking {
    data class Item(val key: String, val columns: Int, val rows: Int, val alignment: Int = 1)
    data class Cell(val x: Int, val y: Int)
    data class Placement(val key: String, val x: Int, val y: Int, val columns: Int, val rows: Int)
    /** Banners snap to complete tile slots; smaller icons and widgets retain cell-level sizing. */
    fun snap(item: Item, columns: Int, x: Int, y: Int): Cell {
        val step = item.alignment.coerceIn(1, columns)
        val maximumX = (columns - item.columns.coerceIn(1, columns)) / step * step
        fun round(value: Int) = (kotlin.math.round(value.coerceIn(0, 10000).toFloat() / step).toInt() * step)
        return Cell(round(x).coerceAtMost(maximumX), round(y).coerceAtMost(10000 / step * step))
    }
    /** Deletion closes gaps in visual order, including cells formerly below the viewport. */
    fun compact(items: List<Item>, columns: Int, previous: List<Placement>): List<Placement> {
        val order = previous.sortedWith(compareBy<Placement> { it.y }.thenBy { it.x }).map { it.key }
        val sorted = items.sortedBy { order.indexOf(it.key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        val packed = pack(sorted, columns, emptyMap()).associateBy { it.key }
        return items.map { packed.getValue(it.key) }
    }
    fun pack(items: List<Item>, columns: Int, desired: Map<String, Cell>, priority: String? = null): List<Placement> {
        require(columns > 0)
        val occupied = hashSetOf<Long>()
        fun code(x: Int, y: Int) = (y.toLong() shl 32) or x.toLong()
        fun fits(x: Int, y: Int, w: Int, h: Int, alignment: Int): Boolean = x >= 0 && y >= 0 && y <= 10000 && x + w <= columns && x % alignment == 0 && y % alignment == 0 &&
            (y until y + h).all { row -> (x until x + w).all { col -> code(col, row) !in occupied } }
        val result = linkedMapOf<String, Placement>()
        for (item in items.sortedBy { when { it.key == priority -> 0; it.key in desired -> 1; else -> 2 } }) {
            val w = item.columns.coerceIn(1, columns); val h = item.rows.coerceIn(1, 100)
            val alignment = item.alignment.coerceIn(1, columns)
            val requested = desired[item.key]?.takeIf { it.y in 0..10000 }?.let { snap(item, columns, it.x, it.y) }
            var cell = requested?.takeIf { fits(it.x, it.y, w, h, alignment) }
            if (cell == null) {
                var y = 0
                while (cell == null) {
                    for (x in 0..columns - w) if (fits(x, y, w, h, alignment)) { cell = Cell(x, y); break }
                    y++
                }
            }
            val p = Placement(item.key, cell.x, cell.y, w, h)
            result[item.key] = p
            for (y in p.y until p.y + h) for (x in p.x until p.x + w) occupied.add(code(x, y))
        }
        return items.map { result.getValue(it.key) }
    }
}
