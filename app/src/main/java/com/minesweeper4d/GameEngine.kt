package com.minesweeper4d


// ─── Data ────────────────────────────────────────────────────────────────────

data class Coord(val x: Int, val y: Int, val z: Int, val w: Int)

data class Cell(
    val coord: Coord,
    var isMine: Boolean = false,
    var isRevealed: Boolean = false,
    var isFlagged: Boolean = false,
    var adjacentMines: Int = 0
)

enum class GameState { IDLE, PLAYING, WIN, FAIL }

// ─── Engine ───────────────────────────────────────────────────────────────────

class GameEngine(
    val nx: Int,   // x dimension (columns within a block)
    val ny: Int,   // y dimension (rows within a block)
    val nz: Int,   // z dimension (vertical blocks)
    val nw: Int,   // w dimension (horizontal block columns)
    val minePercent: Int  // 1–90
) {
    // cells[x][y][z][w]
    val cells: Array<Array<Array<Array<Cell>>>> = Array(nx) { x ->
        Array(ny) { y ->
            Array(nz) { z ->
                Array(nw) { w -> Cell(Coord(x, y, z, w)) }
            }
        }
    }

    var totalMines: Int = 0
        private set
    var flagCount: Int = 0
        private set
    var state: GameState = GameState.IDLE
        private set
    var isGenerated: Boolean = false
        private set

    // ── Generation ────────────────────────────────────────────────────────────

    /**
     * Called on first reveal.
     *
     * Safety guarantees:
     *  1. The clicked cell itself is always mine-free.
     *  2. Every cell in the 3×3×3×3 cube centred on the first click is mine-free.
     *  3. If the safe zone is so large that not all requested mines fit outside it,
     *     the mine count is silently reduced — mines are NEVER placed inside the
     *     safe zone as a fallback.
     */
    fun generate(firstCoord: Coord) {
        val totalCells = nx * ny * nz * nw

        // Build safe zone: all cells within Chebyshev distance 1 of first click
        val safeZone = mutableSetOf<Coord>()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) for (dw in -1..1) {
            val sx = firstCoord.x + dx
            val sy = firstCoord.y + dy
            val sz = firstCoord.z + dz
            val sw = firstCoord.w + dw
            if (sx in 0 until nx && sy in 0 until ny &&
                sz in 0 until nz && sw in 0 until nw
            ) {
                safeZone.add(Coord(sx, sy, sz, sw))
            }
        }

        // Cells eligible for mines = everything outside the safe zone
        val available = mutableListOf<Coord>()
        for (x in 0 until nx) for (y in 0 until ny) for (z in 0 until nz) for (w in 0 until nw) {
            val c = Coord(x, y, z, w)
            if (c !in safeZone) available.add(c)
        }

        // Cap mine count: never exceed available positions (handles tiny grids or very high %)
        val requested = ((totalCells * minePercent) / 100).coerceAtLeast(1)
        totalMines = requested.coerceAtMost(available.size.coerceAtLeast(1))

        // Place mines — safe zone is strictly never touched
        available.shuffle()
        available.take(totalMines).forEach { c ->
            cells[c.x][c.y][c.z][c.w].isMine = true
        }

        // Recalculate adjacency counts
        for (x in 0 until nx) for (y in 0 until ny) for (z in 0 until nz) for (w in 0 until nw) {
            cells[x][y][z][w].adjacentMines = countAdjacentMines(x, y, z, w)
        }

        isGenerated = true
        state = GameState.PLAYING
    }

    // ── Adjacency ─────────────────────────────────────────────────────────────

    private fun countAdjacentMines(x: Int, y: Int, z: Int, w: Int): Int {
        var count = 0
        forEachNeighbour(x, y, z, w) { nx2, ny2, nz2, nw2 ->
            if (cells[nx2][ny2][nz2][nw2].isMine) count++
        }
        return count
    }

    /** Iterate over all valid 4D neighbours (up to 3⁴-1 = 80). */
    private inline fun forEachNeighbour(
        x: Int, y: Int, z: Int, w: Int,
        block: (Int, Int, Int, Int) -> Unit
    ) {
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) for (dw in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0 && dw == 0) continue
            val nx2 = x + dx; val ny2 = y + dy
            val nz2 = z + dz; val nw2 = w + dw
            if (nx2 in 0 until nx && ny2 in 0 until ny &&
                nz2 in 0 until nz && nw2 in 0 until nw
            ) {
                block(nx2, ny2, nz2, nw2)
            }
        }
    }

    fun getNeighbours(c: Coord): List<Cell> {
        val result = mutableListOf<Cell>()
        forEachNeighbour(c.x, c.y, c.z, c.w) { nx2, ny2, nz2, nw2 ->
            result.add(cells[nx2][ny2][nz2][nw2])
        }
        return result
    }

    /** All cells in a 3×3×3×3 cube centred at (x,y,z,w), excluding centre. */
    fun getRange3Neighbours(c: Coord): List<Coord> {
        val result = mutableListOf<Coord>()
        forEachNeighbour(c.x, c.y, c.z, c.w) { nx2, ny2, nz2, nw2 ->
            result.add(Coord(nx2, ny2, nz2, nw2))
        }
        return result
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /**
     * Reveal a cell. Returns true if a mine was hit (game over).
     * On first call also generates the field.
     */
    fun reveal(c: Coord): Boolean {
        if (state == GameState.WIN || state == GameState.FAIL) return false
        if (!isGenerated) generate(c)

        val cell = cells[c.x][c.y][c.z][c.w]
        if (cell.isRevealed || cell.isFlagged) return false

        cell.isRevealed = true
        if (cell.isMine) {
            state = GameState.FAIL
            return true
        }
        if (cell.adjacentMines == 0) floodFill(c)
        checkWin()
        return false
    }

    private fun floodFill(c: Coord) {
        // Iterative BFS to avoid stack overflow on large grids
        val queue = ArrayDeque<Coord>()
        queue.add(c)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            forEachNeighbour(cur.x, cur.y, cur.z, cur.w) { nx2, ny2, nz2, nw2 ->
                val adj = cells[nx2][ny2][nz2][nw2]
                if (!adj.isRevealed && !adj.isFlagged && !adj.isMine) {
                    adj.isRevealed = true
                    if (adj.adjacentMines == 0) queue.add(adj.coord)
                }
            }
        }
    }

    fun toggleFlag(c: Coord) {
        if (state == GameState.WIN || state == GameState.FAIL) return
        val cell = cells[c.x][c.y][c.z][c.w]
        if (cell.isRevealed) return
        if (cell.isFlagged) { cell.isFlagged = false; flagCount-- }
        else { cell.isFlagged = true; flagCount++ }
        checkWin()
    }

    /**
     * If the cell is a revealed number and enough adjacent flags exist,
     * reveal all remaining adjacent unrevealed cells.
     * Returns true if any mine was hit.
     */
    fun revealIfEnoughFlags(c: Coord): Boolean {
        val cell = cells[c.x][c.y][c.z][c.w]
        if (!cell.isRevealed || cell.adjacentMines == 0) return false

        val neighbours = getNeighbours(c)
        val adjFlags = neighbours.count { it.isFlagged }
        if (adjFlags < cell.adjacentMines) return false

        var hitMine = false
        neighbours.forEach { adj ->
            if (!adj.isRevealed && !adj.isFlagged) {
                if (reveal(adj.coord)) hitMine = true
            }
        }
        return hitMine
    }

    // ── Win check ────────────────────────────────────────────────────────────

    private fun checkWin() {
        if (state != GameState.PLAYING) return
        for (x in 0 until nx) for (y in 0 until ny) for (z in 0 until nz) for (w in 0 until nw) {
            val cell = cells[x][y][z][w]
            if (cell.isMine && !cell.isFlagged) return
            if (!cell.isMine && !cell.isRevealed) return
        }
        state = GameState.WIN
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    fun remainingMines(): Int = totalMines - flagCount

    fun cell(x: Int, y: Int, z: Int, w: Int): Cell = cells[x][y][z][w]
    fun cell(c: Coord): Cell = cells[c.x][c.y][c.z][c.w]

    /** All mine positions (for fail reveal). */
    fun allMines(): List<Coord> {
        val result = mutableListOf<Coord>()
        for (x in 0 until nx) for (y in 0 until ny) for (z in 0 until nz) for (w in 0 until nw) {
            if (cells[x][y][z][w].isMine) result.add(Coord(x, y, z, w))
        }
        return result
    }
}
