package com.nphil.marquee.bigscreen

/** Which way the phone remote moves the big screen's focus. */
enum class FocusMove {
	Up, Down, Left, Right;

	val isHorizontal: Boolean get() = this == Left || this == Right
}

/**
 * Where the focus sits on a page: rows of items, each row as long as its count.
 *
 * On a shelves page every row keeps its own place, as TV home screens do; on a grid the column carries from row to
 * row, held where a shorter row clamped it. Empty rows are passed over. A move past the last item reports
 * [Outcome.Edge], which the remote plays as the edge cue.
 */
class FocusGrid(rows: List<Int> = emptyList(), val style: Style = Style.Shelves) {
	enum class Style { Shelves, Grid }
	enum class Outcome { Moved, Edge }

	var rows: List<Int> = rows
		private set
	var row = rows.indexOfFirst { it > 0 }.coerceAtLeast(0)
		private set
	var column = 0
		private set

	/** The column each row was left at (shelves). */
	private var kept = List(rows.size) { 0 }

	/** The column a grid returns to once a shorter row has clamped it. */
	private var wanted = 0

	val isEmpty: Boolean get() = rows.none { it > 0 }

	/** The column a row shows: the focus in the focused row; on shelves, where each other row was left. */
	fun column(inRow: Int): Int {
		if (inRow == row) return column
		return if (style == Style.Shelves) kept.getOrElse(inRow) { 0 } else 0
	}

	fun move(direction: FocusMove): Outcome {
		if (isEmpty) return Outcome.Edge
		when (direction) {
			FocusMove.Left, FocusMove.Right -> {
				val next = column + if (direction == FocusMove.Right) 1 else -1
				if (row !in rows.indices || next !in 0 until rows[row]) return Outcome.Edge
				column = next
				remember()
			}
			FocusMove.Up, FocusMove.Down -> {
				val step = if (direction == FocusMove.Down) 1 else -1
				var target = row + step
				while (target in rows.indices && rows[target] == 0) target += step
				if (target !in rows.indices) return Outcome.Edge
				row = target
				column = minOf(if (style == Style.Shelves) kept[target] else wanted, rows[target] - 1)
			}
		}
		return Outcome.Moved
	}

	/** Puts the focus on one item, as when a page reopens where it was left. */
	fun focus(row: Int, column: Int) {
		if (row !in rows.indices || rows[row] <= 0) return
		this.row = row
		this.column = column.coerceIn(0, rows[row] - 1)
		remember()
	}

	/** New counts as a page's content loads or changes; the focus stays put where it still fits. Answers whether anything changed. */
	fun update(rows: List<Int>): Boolean {
		if (rows == this.rows) return false
		kept = rows.indices.map { kept.getOrElse(it) { 0 } }
		this.rows = rows
		if (isEmpty) {
			row = 0
			column = 0
			return true
		}
		if (row !in rows.indices || rows[row] == 0) {
			val earlier = rows.indices.take(minOf(row, rows.size)).lastOrNull { rows[it] > 0 }
			row = earlier ?: rows.indexOfFirst { it > 0 }.coerceAtLeast(0)
		}
		column = minOf(column, rows[row] - 1)
		return true
	}

	private fun remember() {
		wanted = column
		if (row in kept.indices) kept = kept.toMutableList().also { it[row] = column }
	}

	companion object {
		/** Lays a flat list into rows of [columns]. */
		fun gridRows(count: Int, columns: Int): List<Int> {
			if (count <= 0 || columns <= 0) return emptyList()
			return List(count / columns) { columns } + if (count % columns > 0) listOf(count % columns) else emptyList()
		}
	}
}
