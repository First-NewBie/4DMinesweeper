package com.minesweeper4d

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

enum class ViewMode { MODE_4D, MODE_3D, MODE_2D }
enum class Tool     { DIG, FLAG, RANGE, MOVE }

interface GameViewListener {
    fun onMineHit(); fun onWin(); fun onCellChanged()
}

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Public ────────────────────────────────────────────────────────────────
    var engine: GameEngine? = null
    var activeTool: Tool = Tool.DIG
    var listener: GameViewListener? = null

    var wSlice = 0
        private set
    var zSlice = 0
        private set

    // ── View mode + spread animation ──────────────────────────────────────────
    var viewMode: ViewMode = ViewMode.MODE_3D
        set(value) { if (field != value) { onModeChanged(field, value); field = value } }

    // 0 = all layers merged (looks like 3D cube), 1 = fully spread
    private var zSpread = 0f   // for 2D staircase
    private var wSpread = 0f   // for 4D multi-cube
    private var spreadAnim: ValueAnimator? = null

    // ── Layer alpha animation (sine-eased) ────────────────────────────────────
    private var prevZSlice = 0
    private var prevWSlice = 0
    private var layerAnimT = 1f
    private var layerAnim: ValueAnimator? = null

    // ── Rotation (3D/4D view) ─────────────────────────────────────────────────
    private var azimuth = (PI / 4.0).toFloat()  // 45° default

    // ── Highlights ────────────────────────────────────────────────────────────
    private val rangeHL   = mutableSetOf<Coord>()
    private val failMines = mutableSetOf<Coord>()

    // ── ISO tile dimensions ───────────────────────────────────────────────────
    private val BASE_W  = 72f   // tile diamond full width
    private val BASE_H  = 36f   // tile diamond full height (= BASE_W/2)
    private val BASE_D  = 28f   // depth of unrevealed cube face
    private val BASE_DR = 5f    // depth of revealed cube face

    private fun iW() = BASE_W  * scaleFactor
    private fun iH() = BASE_H  * scaleFactor
    private fun iD() = BASE_D  * scaleFactor
    private fun iDR()= BASE_DR * scaleFactor

    // ── Paint ─────────────────────────────────────────────────────────────────
    private val pFill  = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL }
    private val pEdge  = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.strokeWidth = 1.4f; it.color = 0x55000000 }
    private val pText  = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.textAlign = Paint.Align.CENTER; it.isFakeBoldText = true }
    private val pInfo  = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.textAlign = Paint.Align.CENTER; it.color = 0xFF555555.toInt() }
    private val pHL    = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0x35FFFFFF; it.style = Paint.Style.FILL }
    private val pMineX = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0xFFEF5350.toInt(); it.style = Paint.Style.STROKE
        it.strokeWidth = 3f; it.strokeCap = Paint.Cap.ROUND }

    private val numColors = intArrayOf(0,
        0xFF4FC3F7.toInt(), 0xFF66BB6A.toInt(), 0xFFEF5350.toInt(), 0xFF7E57C2.toInt(),
        0xFFFF7043.toInt(), 0xFF26C6DA.toInt(), 0xFFEC407A.toInt(), 0xFF9E9E9E.toInt())

    // ── Pan / zoom ────────────────────────────────────────────────────────────
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val prev = scaleFactor
                scaleFactor = (scaleFactor * d.scaleFactor).coerceIn(0.15f, 6f)
                offsetX = d.focusX - (d.focusX - offsetX) * (scaleFactor / prev)
                offsetY = d.focusY - (d.focusY - offsetY) * (scaleFactor / prev)
                invalidate(); return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (activeTool == Tool.MOVE && viewMode != ViewMode.MODE_2D) {
                    azimuth -= dx * 0.013f
                    invalidate()
                } else {
                    offsetX -= dx; offsetY -= dy; invalidate()
                }
                return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (activeTool != Tool.MOVE) handleTap(e.x, e.y)
                return true
            }
        })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(e)
        return true
    }

    // ── Mode change ───────────────────────────────────────────────────────────
    private fun onModeChanged(from: ViewMode, to: ViewMode) {
        spreadAnim?.cancel()
        val anim: Pair<Float, Float> = when {
            from == ViewMode.MODE_3D && to == ViewMode.MODE_2D -> 0f to 1f
            from == ViewMode.MODE_2D && to == ViewMode.MODE_3D -> 1f to 0f
            from == ViewMode.MODE_3D && to == ViewMode.MODE_4D -> 0f to 1f
            from == ViewMode.MODE_4D && to == ViewMode.MODE_3D -> 1f to 0f
            from == ViewMode.MODE_4D && to == ViewMode.MODE_2D -> { wSpread = 0f; 0f to 1f }
            from == ViewMode.MODE_2D && to == ViewMode.MODE_4D -> { zSpread = 0f; 0f to 1f }
            else -> return
        }
        val isZ = (to == ViewMode.MODE_2D || from == ViewMode.MODE_2D)
        if (isZ) zSpread = anim.first else wSpread = anim.first

        spreadAnim = ValueAnimator.ofFloat(anim.first, anim.second).apply {
            duration = 700
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                if (isZ) zSpread = v else wSpread = v
                invalidate()
            }
            start()
        }
        post { centerGrid() }
    }

    // ── Layer change (sine-eased alpha) ───────────────────────────────────────
    fun changeLayer(newZ: Int? = null, newW: Int? = null) {
        prevZSlice = zSlice; prevWSlice = wSlice
        if (newZ != null) zSlice = newZ
        if (newW != null) wSlice = newW
        layerAnimT = 0f
        layerAnim?.cancel()
        layerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280; interpolator = LinearInterpolator()
            addUpdateListener { layerAnimT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Sine-eased alpha for a given layer index vs current/previous. */
    private fun layerAlphaInt(idx: Int, current: Int, prev: Int): Int {
        val target = if (idx == current) 1f else 0.2f
        val from   = if (idx == prev)    1f else 0.2f
        val t = sin((PI / 2.0) * layerAnimT).toFloat()
        return ((from + (target - from) * t) * 255).toInt().coerceIn(0, 255)
    }

    // ── Projection ────────────────────────────────────────────────────────────
    /**
     * Grid (gx, gy, gz) → screen position of the top vertex of the top diamond.
     * offX/offY: additional pixel offset (for staircase / multi-cube spread).
     * zVisual: override for gz in the projection (for smooth z-spread).
     */
    private fun gridToScreen(
        gx: Float, gy: Float, gz: Float,
        offX: Float = 0f, offY: Float = 0f,
        zVisual: Float = gz
    ): PointF {
        val eng = engine ?: return PointF(offsetX + offX, offsetY + offY)
        val cosA = cos(azimuth); val sinA = sin(azimuth)
        val rx = gx * cosA - gy * sinA
        val ry = gx * sinA + gy * cosA
        val sx = offsetX + offX + rx * iW() / 2f
        val sy = offsetY + offY + ry * iH() / 2f + (eng.nz - 1 - zVisual) * iD()
        return PointF(sx, sy)
    }

    // ── Staircase / multi-cube offsets ────────────────────────────────────────
    // Returns (screenOffX, screenOffY, zVisualOverride)
    private fun staircaseParams(z: Int): Triple<Float, Float, Float> {
        val relZ   = z - zSlice
        val offX   =  iW() * 0.40f * zSpread * relZ
        val offY   = -iD() * 1.15f * zSpread * relZ
        val zVis   =  z    * (1f - zSpread)          // flatten as spread increases
        return Triple(offX, offY, zVis)
    }

    private fun multiCubeParams(w: Int): Pair<Float, Float> {
        val eng  = engine ?: return Pair(0f, 0f)
        val relW = w - wSlice
        val stepX = ((eng.nx + eng.ny) * iW() / 2f * 0.72f + iW() * 0.2f) * wSpread * relW
        val stepY = eng.ny * iH() / 2f * 0.45f * wSpread * relW
        return Pair(stepX, stepY)
    }

    // ── Center grid ───────────────────────────────────────────────────────────
    fun centerGrid() {
        val eng = engine ?: return
        val gridW = (eng.nx + eng.ny) * iW() / 2f
        val gridH = (eng.nx + eng.ny) * iH() / 2f + (eng.nz - 1) * iD() + iD()
        offsetX = width / 2f
        offsetY = max(24f * scaleFactor, (height - gridH) / 2f)
        invalidate()
    }

    fun resetView()     { scaleFactor = 1f; centerGrid() }
    fun clearHighlight(){ rangeHL.clear(); invalidate() }
    fun revealAllMines(){ failMines.addAll(engine?.allMines() ?: emptyList()); invalidate() }

    // ── Touch action ──────────────────────────────────────────────────────────
    private fun handleTap(cx: Float, cy: Float) {
        val eng = engine ?: return
        if (eng.state == GameState.FAIL || eng.state == GameState.WIN) return
        val coord = when (viewMode) {
            ViewMode.MODE_3D -> tap3D(cx, cy)
            ViewMode.MODE_2D -> tap2D(cx, cy)
            ViewMode.MODE_4D -> tap4D(cx, cy)
        } ?: return
        doAction(coord, eng)
    }

    private fun doAction(coord: Coord, eng: GameEngine) {
        when (activeTool) {
            Tool.DIG -> {
                val cell = eng.cell(coord)
                val hit  = if (cell.isRevealed && cell.adjacentMines > 0)
                    eng.revealIfEnoughFlags(coord) else eng.reveal(coord)
                if (hit) { failMines.addAll(eng.allMines()); listener?.onMineHit() }
                else if (eng.state == GameState.WIN) listener?.onWin()
                listener?.onCellChanged()
            }
            Tool.FLAG -> {
                eng.toggleFlag(coord)
                if (eng.state == GameState.WIN) listener?.onWin()
                listener?.onCellChanged()
            }
            Tool.RANGE -> {
                rangeHL.clear(); rangeHL.add(coord)
                rangeHL.addAll(eng.getRange3Neighbours(coord))
            }
            Tool.MOVE -> {}
        }
        invalidate()
    }

    // ── Tap detection ─────────────────────────────────────────────────────────
    private fun tap3D(cx: Float, cy: Float): Coord? {
        val eng = engine ?: return null
        for (c in sorted3D(eng, front = true)) {
            val cell = eng.cell(c[0], c[1], c[2], wSlice)
            val sp   = gridToScreen(c[0].toFloat(), c[1].toFloat(), c[2].toFloat())
            if (cubeHit(cx, cy, sp, if (cell.isRevealed) iDR() else iD()))
                return Coord(c[0], c[1], c[2], wSlice)
        }
        return null
    }

    // Only current zSlice is tappable in 2D mode
    private fun tap2D(cx: Float, cy: Float): Coord? {
        val eng = engine ?: return null
        val (offX, offY, zVis) = staircaseParams(zSlice)
        val iw = iW(); val ih = iH()
        for (c in sorted3D(eng, front = true)) {
            if (c[2] != zSlice) continue
            val sp  = gridToScreen(c[0].toFloat(), c[1].toFloat(), zVis, offX, offY, zVis)
            val top = PointF(sp.x, sp.y)
            val rgt = PointF(sp.x + iw/2f, sp.y + ih/2f)
            val bot = PointF(sp.x,         sp.y + ih)
            val lft = PointF(sp.x - iw/2f, sp.y + ih/2f)
            if (pip(cx, cy, arrayOf(top, rgt, bot, lft)))
                return Coord(c[0], c[1], zSlice, wSlice)
        }
        return null
    }

    // Only current wSlice is tappable in 4D mode
    private fun tap4D(cx: Float, cy: Float): Coord? {
        val eng = engine ?: return null
        val (offX, offY) = multiCubeParams(wSlice)
        for (c in sorted3D(eng, front = true)) {
            val cell = eng.cell(c[0], c[1], c[2], wSlice)
            val sp   = gridToScreen(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), offX, offY)
            if (cubeHit(cx, cy, sp, if (cell.isRevealed) iDR() else iD()))
                return Coord(c[0], c[1], c[2], wSlice)
        }
        return null
    }

    private fun cubeHit(cx: Float, cy: Float, sp: PointF, depth: Float): Boolean {
        val iw = iW(); val ih = iH()
        val top = PointF(sp.x, sp.y)
        val rgt = PointF(sp.x + iw/2f, sp.y + ih/2f)
        val bot = PointF(sp.x,         sp.y + ih)
        val lft = PointF(sp.x - iw/2f, sp.y + ih/2f)
        val bR  = PointF(rgt.x, rgt.y + depth)
        val bB  = PointF(bot.x, bot.y + depth)
        val bL  = PointF(lft.x, lft.y + depth)
        return pip(cx, cy, arrayOf(top, rgt, bot, lft)) ||
               pip(cx, cy, arrayOf(rgt, bR, bB, bot)) ||
               pip(cx, cy, arrayOf(lft, bot, bB, bL))
    }

    /** Point-in-convex-polygon (CCW winding) */
    private fun pip(px: Float, py: Float, pts: Array<PointF>): Boolean {
        val n = pts.size
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i + 1) % n]
            if ((b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x) < 0) return false
        }
        return true
    }

    // ── Sorted cell list ──────────────────────────────────────────────────────
    private fun sorted3D(eng: GameEngine, front: Boolean): List<IntArray> {
        val cosA = cos(azimuth); val sinA = sin(azimuth)
        val cells = ArrayList<IntArray>(eng.nx * eng.ny * eng.nz)
        for (x in 0 until eng.nx) for (y in 0 until eng.ny) for (z in 0 until eng.nz)
            cells.add(intArrayOf(x, y, z))
        // depth key: dot product with view direction
        val cmp = compareBy<IntArray> { (it[0] * sinA + it[1] * cosA).toLong() }
            .thenBy { it[2] }
        cells.sortWith(if (front) cmp.reversed() else cmp)
        return cells
    }

    // ── Draw ──────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF0F0F0F.toInt())
        val eng = engine ?: return
        when (viewMode) {
            ViewMode.MODE_3D -> draw3D(canvas, eng)
            ViewMode.MODE_2D -> draw2D(canvas, eng)
            ViewMode.MODE_4D -> draw4D(canvas, eng)
        }
    }

    // ── 3D iso ────────────────────────────────────────────────────────────────
    private fun draw3D(canvas: Canvas, eng: GameEngine) {
        for (c in sorted3D(eng, front = false))
            drawCube(canvas, eng, c[0], c[1], c[2], wSlice, 0f, 0f, c[2].toFloat(), 255)
        label(canvas, "3D  w=${wSlice}")
    }

    // ── Staircase 2D ─────────────────────────────────────────────────────────
    private fun draw2D(canvas: Canvas, eng: GameEngine) {
        // Draw z-layers back→front (highest z first in default iso)
        for (z in eng.nz - 1 downTo 0) {
            val (offX, offY, zVis) = staircaseParams(z)
            val alpha = layerAlphaInt(z, zSlice, prevZSlice)
            val layerPaint = Paint().apply { this.alpha = alpha }
            canvas.saveLayer(null, layerPaint)
            for (c in sorted3D(eng, front = false)) {
                if (c[2] != z) continue
                // flat depth lerped with zSpread
                val flatDepth = iDR() * 0.5f + (iD() - iDR() * 0.5f) * (1f - zSpread)
                val cell = eng.cell(c[0], c[1], z, wSlice)
                val d = if (cell.isRevealed) iDR() * 0.5f else flatDepth
                drawCube(canvas, eng, c[0], c[1], z, wSlice, offX, offY, zVis, 255,
                         depthOverride = d)
            }
            canvas.restore()
        }
        label(canvas, "2D  w=${wSlice}  z=${zSlice}")
    }

    // ── Multi-cube 4D ─────────────────────────────────────────────────────────
    private fun draw4D(canvas: Canvas, eng: GameEngine) {
        // Draw w-cubes from far side to near side
        val wOrder = (0 until eng.nw).sortedBy { -(it - wSlice) }
        for (w in wOrder) {
            val (offX, offY) = multiCubeParams(w)
            val alpha = layerAlphaInt(w, wSlice, prevWSlice)
            val layerPaint = Paint().apply { this.alpha = alpha }
            canvas.saveLayer(null, layerPaint)
            for (c in sorted3D(eng, front = false))
                drawCube(canvas, eng, c[0], c[1], c[2], w, offX, offY, c[2].toFloat(), alpha)
            canvas.restore()
        }
        label(canvas, "4D  w=${wSlice}")
    }

    // ── Draw one isometric cube ───────────────────────────────────────────────
    private fun drawCube(
        canvas: Canvas, eng: GameEngine,
        x: Int, y: Int, z: Int, w: Int,
        offX: Float, offY: Float, zVisual: Float,
        layerAlpha: Int,
        depthOverride: Float? = null
    ) {
        val cell  = eng.cell(x, y, z, w)
        val coord = Coord(x, y, z, w)
        val sp    = gridToScreen(x.toFloat(), y.toFloat(), z.toFloat(), offX, offY, zVisual)
        val iw    = iW(); val ih = iH()
        val depth = depthOverride ?: if (cell.isRevealed) iDR() else iD()

        // Cull off-screen
        if (sp.x + iw/2f < 0 || sp.x - iw/2f > width) return
        if (sp.y + ih + depth < 0 || sp.y > height) return

        val top = PointF(sp.x,        sp.y)
        val rgt = PointF(sp.x + iw/2f, sp.y + ih/2f)
        val bot = PointF(sp.x,        sp.y + ih)
        val lft = PointF(sp.x - iw/2f, sp.y + ih/2f)
        val bR  = PointF(rgt.x, rgt.y + depth)
        val bB  = PointF(bot.x, bot.y + depth)
        val bL  = PointF(lft.x, lft.y + depth)

        val base = baseColor(cell, coord)
        val hl   = rangeHL.contains(coord)

        face(canvas, arrayOf(rgt, bR, bB, bot), darken(base, 0.62f), hl)
        face(canvas, arrayOf(lft, bot, bB, bL), darken(base, 0.50f), hl)
        face(canvas, arrayOf(top, rgt, bot, lft), base, hl)

        val fcx = sp.x; val fcy = sp.y + ih / 2f
        val ts  = (iw * 0.28f).coerceIn(8f, 26f)
        cellContent(canvas, cell, coord, fcx, fcy, ts)
    }

    private fun face(canvas: Canvas, pts: Array<PointF>, color: Int, hl: Boolean) {
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }
        pFill.color = color; canvas.drawPath(path, pFill)
        if (hl) { pFill.color = 0x25FFFFFF; canvas.drawPath(path, pFill) }
        canvas.drawPath(path, pEdge)
    }

    private fun cellContent(canvas: Canvas, cell: Cell, coord: Coord,
                             cx: Float, cy: Float, ts: Float) {
        when {
            failMines.contains(coord) && cell.isMine && !cell.isFlagged -> mine(canvas, cx, cy, ts)
            failMines.isNotEmpty() && cell.isFlagged && !cell.isMine -> {
                pText.color = 0xFF888888.toInt(); pText.textSize = ts
                canvas.drawText("⚑", cx, cy + ts * 0.35f, pText)
                pMineX.color = 0xFFEF5350.toInt()
                canvas.drawLine(cx-ts*0.5f, cy-ts*0.5f, cx+ts*0.5f, cy+ts*0.5f, pMineX)
            }
            cell.isFlagged -> {
                pText.color = 0xFFFFB300.toInt(); pText.textSize = ts * 1.1f
                canvas.drawText("⚑", cx, cy + ts * 0.35f, pText)
            }
            cell.isRevealed && cell.isMine -> mine(canvas, cx, cy, ts)
            cell.isRevealed && cell.adjacentMines > 0 -> {
                pText.color = numColors[cell.adjacentMines.coerceIn(1,8)]
                pText.textSize = ts
                canvas.drawText(cell.adjacentMines.toString(), cx, cy + ts*0.35f, pText)
            }
        }
    }

    private fun mine(canvas: Canvas, cx: Float, cy: Float, ts: Float) {
        pFill.color = 0xFFEF5350.toInt()
        canvas.drawCircle(cx, cy, ts * 0.45f, pFill)
        val a = ts * 0.3f
        canvas.drawLine(cx-a, cy-a, cx+a, cy+a, pMineX)
        canvas.drawLine(cx+a, cy-a, cx-a, cy+a, pMineX)
    }

    private fun baseColor(cell: Cell, coord: Coord): Int = when {
        failMines.contains(coord) && cell.isMine -> 0xFF8B1A1A.toInt()
        cell.isFlagged   -> 0xFF4A3A00.toInt()
        cell.isRevealed  -> 0xFF1E1E1E.toInt()
        else             -> 0xFF383838.toInt()
    }

    private fun darken(c: Int, f: Float) = Color.argb(
        Color.alpha(c),
        (Color.red(c)   * f).toInt().coerceIn(0,255),
        (Color.green(c) * f).toInt().coerceIn(0,255),
        (Color.blue(c)  * f).toInt().coerceIn(0,255))

    private fun label(canvas: Canvas, text: String) {
        val ls = (13f * scaleFactor).coerceIn(8f, 18f)
        pInfo.textSize = ls
        canvas.drawText(text, width / 2f, ls + 6f, pInfo)
    }
}
