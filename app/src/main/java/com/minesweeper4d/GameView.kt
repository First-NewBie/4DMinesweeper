package com.minesweeper4d

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

// ─── View mode ────────────────────────────────────────────────────────────────

enum class ViewMode { MODE_4D, MODE_3D, MODE_2D }
enum class Tool     { DIG, FLAG, RANGE }

// ─── Callback ─────────────────────────────────────────────────────────────────

interface GameViewListener {
    fun onMineHit()
    fun onWin()
    fun onCellChanged()
}

// ─── GameView ─────────────────────────────────────────────────────────────────

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Public state ──────────────────────────────────────────────────────────
    var engine: GameEngine? = null
    var activeTool: Tool = Tool.DIG
    var listener: GameViewListener? = null

    // Current slice coordinates controlled by sliders
    var wSlice: Int = 0
    var zSlice: Int = 0

    // View mode: 4D shows all, 3D fixes w, 2D fixes w+z
    var viewMode: ViewMode = ViewMode.MODE_4D
        set(value) {
            if (field != value) {
                startSweepAnimation(field, value)
                field = value
            }
        }

    // ── Cell highlight sets ───────────────────────────────────────────────────
    private val rangeHighlight = mutableSetOf<Coord>()
    private val failRevealSet  = mutableSetOf<Coord>()

    // ── Layout constants ──────────────────────────────────────────────────────
    private val BASE_CELL   = 52f
    private val BLOCK_GAP   = 14f
    private val AXIS_MARGIN = 44f

    // ── Paint objects ─────────────────────────────────────────────────────────
    private val pCell      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBorder    = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.strokeWidth = 1f; it.color = 0xFF383838.toInt() }
    private val pBlockBrd  = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.strokeWidth = 2f; it.color = 0xFF555555.toInt() }
    private val pText      = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.textAlign = Paint.Align.CENTER; it.isFakeBoldText = true }
    private val pAxis      = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.textAlign = Paint.Align.CENTER }
    private val pHighlight = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0x30FFFFFF; it.style = Paint.Style.FILL }
    private val pMineX     = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0xFFEF5350.toInt(); it.style = Paint.Style.STROKE
        it.strokeWidth = 3f; it.strokeCap = Paint.Cap.ROUND }
    private val pSweep     = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.FILL }
    // Dim overlay for out-of-slice blocks in 3D/2D mode
    private val pDim       = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0xAA0F0F0F.toInt(); it.style = Paint.Style.FILL }

    private val numColours = intArrayOf(
        0,
        0xFF4FC3F7.toInt(), // 1 blue
        0xFF66BB6A.toInt(), // 2 green
        0xFFEF5350.toInt(), // 3 red
        0xFF7E57C2.toInt(), // 4 purple
        0xFFFF7043.toInt(), // 5 orange
        0xFF26C6DA.toInt(), // 6 cyan
        0xFFEC407A.toInt(), // 7 pink
        0xFF9E9E9E.toInt()  // 8 grey
    )

    // ── Sweep animation ───────────────────────────────────────────────────────
    // sweepFraction: 0.0 = fully old view, 1.0 = fully new view
    private var sweepFraction = 1f
    private var sweepAnimator: ValueAnimator? = null
    // Cached bitmap of the view just before the transition starts
    private var preSweepBitmap: Bitmap? = null

    private fun startSweepAnimation(from: ViewMode, to: ViewMode) {
        // Capture current canvas into a bitmap
        val bmp = Bitmap.createBitmap(
            width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888
        )
        val c = Canvas(bmp)
        drawContent(c)
        preSweepBitmap = bmp

        sweepFraction = 0f
        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                sweepFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ── Pan / zoom ────────────────────────────────────────────────────────────
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val prev = scaleFactor
                scaleFactor = (scaleFactor * d.scaleFactor).coerceIn(0.2f, 5f)
                offsetX = d.focusX - (d.focusX - offsetX) * (scaleFactor / prev)
                offsetY = d.focusY - (d.focusY - offsetY) * (scaleFactor / prev)
                clampOffset(); invalidate(); return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                offsetX -= dx; offsetY -= dy; clampOffset(); invalidate(); return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x, e.y); return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────
    private fun cs()  = BASE_CELL   * scaleFactor
    private fun gap() = BLOCK_GAP   * scaleFactor
    private fun ax()  = AXIS_MARGIN * scaleFactor

    /** Canvas origin of block (z, w). In 2D/3D modes unused dimensions are 0. */
    private fun blockOrigin(z: Int, w: Int): PointF {
        val eng = engine ?: return PointF(0f, 0f)
        return when (viewMode) {
            ViewMode.MODE_4D -> PointF(
                offsetX + ax() + w * (eng.nx * cs() + gap()),
                offsetY + ax() + z * (eng.ny * cs() + gap())
            )
            ViewMode.MODE_3D -> PointF(
                offsetX + ax(),
                offsetY + ax() + z * (eng.ny * cs() + gap())
            )
            ViewMode.MODE_2D -> PointF(offsetX + ax(), offsetY + ax())
        }
    }

    private fun cellRect(x: Int, y: Int, z: Int, w: Int): RectF {
        val o = blockOrigin(z, w); val c = cs()
        return RectF(o.x + x * c, o.y + y * c, o.x + (x + 1) * c, o.y + (y + 1) * c)
    }

    /** Convert canvas tap → Coord, respecting current view mode. */
    private fun canvasToCell(cx: Float, cy: Float): Coord? {
        val eng = engine ?: return null
        val c = cs(); val g = gap(); val a = ax()
        return when (viewMode) {
            ViewMode.MODE_4D -> {
                val bw = eng.nx * c + g; val bh = eng.ny * c + g
                val w = ((cx - offsetX - a) / bw).toInt()
                val z = ((cy - offsetY - a) / bh).toInt()
                if (w !in 0 until eng.nw || z !in 0 until eng.nz) return null
                val ix = cx - offsetX - a - w * bw
                val iy = cy - offsetY - a - z * bh
                if (ix < 0 || ix >= eng.nx * c || iy < 0 || iy >= eng.ny * c) return null
                Coord((ix / c).toInt(), (iy / c).toInt(), z, w)
            }
            ViewMode.MODE_3D -> {
                val bh = eng.ny * c + g
                val z = ((cy - offsetY - a) / bh).toInt()
                if (z !in 0 until eng.nz) return null
                val ix = cx - offsetX - a
                val iy = cy - offsetY - a - z * bh
                if (ix < 0 || ix >= eng.nx * c || iy < 0 || iy >= eng.ny * c) return null
                Coord((ix / c).toInt(), (iy / c).toInt(), z, wSlice)
            }
            ViewMode.MODE_2D -> {
                val ix = cx - offsetX - a
                val iy = cy - offsetY - a
                if (ix < 0 || ix >= eng.nx * c || iy < 0 || iy >= eng.ny * c) return null
                Coord((ix / c).toInt(), (iy / c).toInt(), zSlice, wSlice)
            }
        }
    }

    // ── Clamp pan ─────────────────────────────────────────────────────────────
    private fun clampOffset() {
        val eng = engine ?: return
        val totalW: Float; val totalH: Float
        when (viewMode) {
            ViewMode.MODE_4D -> {
                totalW = ax() + eng.nw * (eng.nx * cs() + gap())
                totalH = ax() + eng.nz * (eng.ny * cs() + gap())
            }
            ViewMode.MODE_3D -> {
                totalW = ax() + eng.nx * cs()
                totalH = ax() + eng.nz * (eng.ny * cs() + gap())
            }
            ViewMode.MODE_2D -> {
                totalW = ax() + eng.nx * cs()
                totalH = ax() + eng.ny * cs()
            }
        }
        val minOX = min(0f, width  - totalW)
        val minOY = min(0f, height - totalH)
        offsetX = offsetX.coerceIn(minOX, max(0f, totalW - width))
        offsetY = offsetY.coerceIn(minOY, max(0f, totalH - height))
    }

    fun centerGrid() {
        val eng = engine ?: return
        scaleFactor = 1f
        val totalW: Float; val totalH: Float
        when (viewMode) {
            ViewMode.MODE_4D -> {
                totalW = ax() + eng.nw * (eng.nx * cs() + gap())
                totalH = ax() + eng.nz * (eng.ny * cs() + gap())
            }
            ViewMode.MODE_3D -> {
                totalW = ax() + eng.nx * cs()
                totalH = ax() + eng.nz * (eng.ny * cs() + gap())
            }
            ViewMode.MODE_2D -> {
                totalW = ax() + eng.nx * cs()
                totalH = ax() + eng.ny * cs()
            }
        }
        offsetX = max(0f, (width  - totalW) / 2f)
        offsetY = max(0f, (height - totalH) / 2f)
        invalidate()
    }

    fun resetView() { scaleFactor = 1f; offsetX = 0f; offsetY = 0f; invalidate() }

    // ── Touch → action ────────────────────────────────────────────────────────
    private fun handleTap(cx: Float, cy: Float) {
        val eng = engine ?: return
        if (eng.state == GameState.FAIL || eng.state == GameState.WIN) return
        val coord = canvasToCell(cx, cy) ?: return

        when (activeTool) {
            Tool.DIG -> {
                val cell = eng.cell(coord)
                val hit = if (cell.isRevealed && cell.adjacentMines > 0)
                    eng.revealIfEnoughFlags(coord)
                else
                    eng.reveal(coord)
                if (hit) { failRevealSet.addAll(eng.allMines()); listener?.onMineHit() }
                else if (eng.state == GameState.WIN) listener?.onWin()
                listener?.onCellChanged()
            }
            Tool.FLAG -> {
                eng.toggleFlag(coord)
                if (eng.state == GameState.WIN) listener?.onWin()
                listener?.onCellChanged()
            }
            Tool.RANGE -> {
                rangeHighlight.clear()
                rangeHighlight.add(coord)
                rangeHighlight.addAll(eng.getRange3Neighbours(coord))
            }
        }
        invalidate()
    }

    fun clearHighlight() { rangeHighlight.clear(); invalidate() }
    fun revealAllMines() {
        failRevealSet.addAll(engine?.allMines() ?: emptyList()); invalidate()
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF0F0F0F.toInt())

        val bmp = preSweepBitmap
        if (bmp != null && sweepFraction < 1f) {
            // Draw old bitmap, then sweep the new content in from the left
            canvas.drawBitmap(bmp, 0f, 0f, null)
            val sweepX = width * sweepFraction
            canvas.save()
            canvas.clipRect(0f, 0f, sweepX, height.toFloat())
            drawContent(canvas)
            canvas.restore()
            // Draw sweep edge line
            pSweep.color = 0x884FC3F7.toInt()
            canvas.drawRect(sweepX - 3f, 0f, sweepX + 3f, height.toFloat(), pSweep)
        } else {
            preSweepBitmap = null
            drawContent(canvas)
        }
    }

    private fun drawContent(canvas: Canvas) {
        val eng = engine ?: return
        when (viewMode) {
            ViewMode.MODE_4D -> drawAll4D(canvas, eng)
            ViewMode.MODE_3D -> drawSlice3D(canvas, eng)
            ViewMode.MODE_2D -> drawSlice2D(canvas, eng)
        }
        drawAxisLabels(canvas, eng)
    }

    // ── 4D: draw all blocks ───────────────────────────────────────────────────
    private fun drawAll4D(canvas: Canvas, eng: GameEngine) {
        for (w in 0 until eng.nw)
            for (z in 0 until eng.nz) {
                drawBlock(canvas, eng, z, w)
                drawBlockBorder(canvas, eng, z, w)
            }
    }

    // ── 3D: draw all z-rows at fixed w = wSlice ───────────────────────────────
    private fun drawSlice3D(canvas: Canvas, eng: GameEngine) {
        for (z in 0 until eng.nz) {
            drawBlock(canvas, eng, z, wSlice, forceW = true)
            drawBlockBorder(canvas, eng, z, 0)   // blocks rendered at w=0 column
        }
    }

    // ── 2D: draw single block at (zSlice, wSlice) ────────────────────────────
    private fun drawSlice2D(canvas: Canvas, eng: GameEngine) {
        drawBlock(canvas, eng, zSlice, wSlice, forceW = true, forceZ = true)
        drawBlockBorder(canvas, eng, 0, 0)
    }

    /**
     * Draw a single (z,w) block on canvas.
     * forceW / forceZ: render at position (0,0) for 3D/2D modes.
     */
    private fun drawBlock(
        canvas: Canvas, eng: GameEngine,
        z: Int, w: Int,
        forceW: Boolean = false, forceZ: Boolean = false
    ) {
        val renderZ = if (forceZ) 0 else z
        val renderW = if (forceW) 0 else w
        // Use actual z,w for data lookup; renderZ/renderW for position
        val origO = blockOrigin(renderZ, renderW)
        val origZ = if (forceZ) z else renderZ
        val origW = if (forceW) w else renderW
        for (x in 0 until eng.nx)
            for (y in 0 until eng.ny)
                drawCell(canvas, eng, x, y, origZ, origW, renderZ, renderW)
    }

    private fun drawCell(
        canvas: Canvas, eng: GameEngine,
        x: Int, y: Int,
        dataZ: Int, dataW: Int,
        renderZ: Int, renderW: Int
    ) {
        val o = blockOrigin(renderZ, renderW)
        val c = cs(); val cr = (c * 0.12f).coerceIn(1f, 6f)
        val rect = RectF(o.x + x * c, o.y + y * c, o.x + (x + 1) * c, o.y + (y + 1) * c)

        if (rect.right < 0 || rect.left > width ||
            rect.bottom < 0 || rect.top > height) return

        val cell  = eng.cell(x, y, dataZ, dataW)
        val coord = Coord(x, y, dataZ, dataW)
        val ts    = (c * 0.45f).coerceIn(8f, 32f)

        // Background
        val bg = when {
            failRevealSet.contains(coord) && cell.isMine -> 0xFF7B1010.toInt()
            cell.isFlagged   -> 0xFF3A2E00.toInt()
            cell.isRevealed  -> 0xFF181818.toInt()
            else             -> 0xFF2C2C2C.toInt()
        }
        pCell.style = Paint.Style.FILL; pCell.color = bg
        canvas.drawRoundRect(rect, cr, cr, pCell)

        // Content
        when {
            failRevealSet.contains(coord) && cell.isMine && !cell.isFlagged ->
                drawMineSymbol(canvas, rect)
            failRevealSet.isNotEmpty() && cell.isFlagged && !cell.isMine ->
                drawFlagSymbol(canvas, rect, ts, wrong = true)
            cell.isFlagged ->
                drawFlagSymbol(canvas, rect, ts, wrong = false)
            cell.isRevealed && cell.isMine ->
                drawMineSymbol(canvas, rect)
            cell.isRevealed && cell.adjacentMines > 0 -> {
                pText.color    = numColours[cell.adjacentMines.coerceIn(1, 8)]
                pText.textSize = ts
                canvas.drawText(cell.adjacentMines.toString(),
                    rect.centerX(), rect.centerY() + ts * 0.35f, pText)
            }
        }

        if (rangeHighlight.contains(coord))
            canvas.drawRoundRect(rect, cr, cr, pHighlight)

        canvas.drawRoundRect(rect, cr, cr, pBorder)
    }

    private fun drawMineSymbol(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX(); val cy = rect.centerY(); val r = rect.width() * 0.25f
        pCell.color = 0xFFEF5350.toInt(); pCell.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r, pCell)
        val arm = r * 0.7f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, pMineX)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, pMineX)
    }

    private fun drawFlagSymbol(canvas: Canvas, rect: RectF, ts: Float, wrong: Boolean) {
        pText.textSize = ts * 1.1f
        pText.color    = if (wrong) 0xFF888888.toInt() else 0xFFFFB300.toInt()
        canvas.drawText("⚑", rect.centerX(), rect.centerY() + ts * 0.35f, pText)
        if (wrong) {
            pMineX.color = 0xFFEF5350.toInt()
            canvas.drawLine(rect.left + 4f, rect.top + 4f,
                            rect.right - 4f, rect.bottom - 4f, pMineX)
        }
    }

    private fun drawBlockBorder(canvas: Canvas, eng: GameEngine, z: Int, w: Int) {
        val o  = blockOrigin(z, w); val c = cs()
        val bw = eng.nx * c; val bh = eng.ny * c
        val rr = (c * 0.12f).coerceIn(2f, 8f)
        canvas.drawRoundRect(RectF(o.x, o.y, o.x + bw, o.y + bh), rr, rr, pBlockBrd)
    }

    // ── Axis labels ───────────────────────────────────────────────────────────
    private fun drawAxisLabels(canvas: Canvas, eng: GameEngine) {
        val a  = ax(); val c = cs(); val g = gap()
        val ls = (18f * scaleFactor).coerceIn(10f, 26f)
        pAxis.textSize = ls

        when (viewMode) {
            ViewMode.MODE_4D -> {
                // w axis (top)
                pAxis.color = 0xFF4FC3F7.toInt()
                for (w in 0 until eng.nw) {
                    val bx = offsetX + a + w * (eng.nx * c + g) + (eng.nx * c) / 2f
                    canvas.drawText("w${w}", bx, offsetY + a - ls * 0.4f, pAxis)
                }
                // z axis (left)
                pAxis.color = 0xFF66BB6A.toInt()
                for (z in 0 until eng.nz) {
                    val by = offsetY + a + z * (eng.ny * c + g) + (eng.ny * c) / 2f + ls * 0.35f
                    canvas.drawText("z${z}", offsetX + a * 0.45f, by, pAxis)
                }
                pAxis.color   = 0xFF888888.toInt()
                pAxis.textSize = (ls * 0.7f).coerceIn(8f, 16f)
                canvas.drawText("w→", offsetX + a * 0.5f, offsetY + a * 0.38f, pAxis)
                canvas.drawText("z↓", offsetX + a * 0.18f, offsetY + a + ls, pAxis)
            }
            ViewMode.MODE_3D -> {
                // z axis only (left), w info top-right
                pAxis.color = 0xFF66BB6A.toInt()
                for (z in 0 until eng.nz) {
                    val by = offsetY + a + z * (eng.ny * c + g) + (eng.ny * c) / 2f + ls * 0.35f
                    canvas.drawText("z${z}", offsetX + a * 0.45f, by, pAxis)
                }
                pAxis.color   = 0xFF4FC3F7.toInt()
                pAxis.textSize = (ls * 0.75f).coerceIn(8f, 16f)
                canvas.drawText("w=${wSlice}", offsetX + a + (eng.nx * c) / 2f,
                    offsetY + a - ls * 0.4f, pAxis)
                pAxis.color   = 0xFF888888.toInt()
                canvas.drawText("z↓", offsetX + a * 0.18f, offsetY + a + ls, pAxis)
            }
            ViewMode.MODE_2D -> {
                pAxis.color   = 0xFF4FC3F7.toInt()
                pAxis.textSize = (ls * 0.75f).coerceIn(8f, 16f)
                canvas.drawText("w=${wSlice}  z=${zSlice}",
                    offsetX + a + (eng.nx * c) / 2f, offsetY + a - ls * 0.4f, pAxis)
            }
        }
    }
}
