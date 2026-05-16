package com.minesweeper4d

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ─── Tool enum ────────────────────────────────────────────────────────────────

enum class Tool { DIG, FLAG, RANGE }

// ─── Callback interface ───────────────────────────────────────────────────────

interface GameViewListener {
    fun onMineHit()
    fun onWin()
    fun onCellChanged()  // mine count / flag count changed → update UI
}

// ─── GameView ────────────────────────────────────────────────────────────────

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Public state ──────────────────────────────────────────────────────────

    var engine: GameEngine? = null
    var activeTool: Tool = Tool.DIG
    var listener: GameViewListener? = null

    // highlighted cells (range view)
    private val rangeHighlight = mutableSetOf<Coord>()
    // mine-reveal overlay after fail
    private val failRevealSet = mutableSetOf<Coord>()

    // ── Layout constants ──────────────────────────────────────────────────────

    private val BASE_CELL = 52f      // dp-ish base cell size
    private val BLOCK_GAP = 12f      // gap between z-w blocks
    private val AXIS_MARGIN = 48f    // left/top margin for axis labels
    private val AXIS_LABEL_SIZE = 24f

    // ── Paint objects ─────────────────────────────────────────────────────────

    private val paintCell      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBlockBrd  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintAxis      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintHighlight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintMineX     = Paint(Paint.ANTI_ALIAS_FLAG)

    // Number colours (index = adjacent count 1-8)
    private val numColours = intArrayOf(
        0,
        0xFF4FC3F7.toInt(), // 1 – light blue
        0xFF66BB6A.toInt(), // 2 – green
        0xFFEF5350.toInt(), // 3 – red
        0xFF7E57C2.toInt(), // 4 – purple
        0xFFFF7043.toInt(), // 5 – deep orange
        0xFF26C6DA.toInt(), // 6 – cyan
        0xFFEC407A.toInt(), // 7 – pink
        0xFF9E9E9E.toInt()  // 8 – grey
    )

    init {
        paintBorder.style   = Paint.Style.STROKE
        paintBorder.strokeWidth = 1f
        paintBorder.color   = 0xFF383838.toInt()

        paintBlockBrd.style  = Paint.Style.STROKE
        paintBlockBrd.strokeWidth = 2f
        paintBlockBrd.color  = 0xFF555555.toInt()

        paintText.textAlign  = Paint.Align.CENTER
        paintText.isFakeBoldText = true

        paintAxis.color     = 0xFF888888.toInt()
        paintAxis.textSize  = AXIS_LABEL_SIZE
        paintAxis.textAlign = Paint.Align.CENTER

        paintHighlight.color = 0x30FFFFFF
        paintHighlight.style = Paint.Style.FILL

        paintMineX.color = 0xFFEF5350.toInt()
        paintMineX.style = Paint.Style.STROKE
        paintMineX.strokeWidth = 3f
        paintMineX.strokeCap = Paint.Cap.ROUND
    }

    // ── Pan / zoom state ──────────────────────────────────────────────────────

    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prev = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.25f, 4f)
                // Keep focal point steady
                val focusX = detector.focusX
                val focusY = detector.focusY
                offsetX = focusX - (focusX - offsetX) * (scaleFactor / prev)
                offsetY = focusY - (focusY - offsetY) * (scaleFactor / prev)
                clampOffset()
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                offsetX -= dx
                offsetY -= dy
                clampOffset()
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    private fun cellSize()  = BASE_CELL * scaleFactor
    private fun blockGap()  = BLOCK_GAP * scaleFactor
    private fun axisMargin() = AXIS_MARGIN * scaleFactor

    /** Top-left canvas position of block (z, w). */
    private fun blockOrigin(z: Int, w: Int): PointF {
        val eng = engine ?: return PointF(0f, 0f)
        val cs = cellSize(); val gap = blockGap(); val ax = axisMargin()
        return PointF(
            offsetX + ax + w * (eng.nx * cs + gap),
            offsetY + ax + z * (eng.ny * cs + gap)
        )
    }

    /** Canvas rect for cell (x,y,z,w). */
    private fun cellRect(x: Int, y: Int, z: Int, w: Int): RectF {
        val orig = blockOrigin(z, w)
        val cs = cellSize()
        return RectF(orig.x + x * cs, orig.y + y * cs,
                     orig.x + (x + 1) * cs, orig.y + (y + 1) * cs)
    }

    /** Convert canvas coords to cell Coord, or null if outside grid. */
    private fun canvasToCell(cx: Float, cy: Float): Coord? {
        val eng = engine ?: return null
        val cs = cellSize(); val gap = blockGap(); val ax = axisMargin()
        // Adjusted for offset + axis margin
        val rx = cx - offsetX - ax
        val ry = cy - offsetY - ax
        val blockW = eng.nx * cs + gap
        val blockH = eng.ny * cs + gap
        val w = (rx / blockW).toInt()
        val z = (ry / blockH).toInt()
        if (w !in 0 until eng.nw || z !in 0 until eng.nz) return null
        val innerX = rx - w * blockW
        val innerY = ry - z * blockH
        if (innerX < 0 || innerX >= eng.nx * cs) return null
        if (innerY < 0 || innerY >= eng.ny * cs) return null
        val x = (innerX / cs).toInt()
        val y = (innerY / cs).toInt()
        if (x !in 0 until eng.nx || y !in 0 until eng.ny) return null
        return Coord(x, y, z, w)
    }

    // ── Clamp pan so grid doesn't drift too far ───────────────────────────────

    private fun clampOffset() {
        val eng = engine ?: return
        val totalW = AXIS_MARGIN * scaleFactor + eng.nw * (eng.nx * cellSize() + blockGap())
        val totalH = AXIS_MARGIN * scaleFactor + eng.nz * (eng.ny * cellSize() + blockGap())
        val minOX = min(0f, (width - totalW) )
        val minOY = min(0f, (height - totalH))
        offsetX = offsetX.coerceIn(minOX, max(0f, totalW - width))
        offsetY = offsetY.coerceIn(minOY, max(0f, totalH - height))
    }

    // ── Initial centering ─────────────────────────────────────────────────────

    fun resetView() {
        scaleFactor = 1f
        offsetX = 0f
        offsetY = 0f
        invalidate()
    }

    fun centerGrid() {
        val eng = engine ?: return
        val totalW = AXIS_MARGIN * scaleFactor + eng.nw * (eng.nx * cellSize() + blockGap())
        val totalH = AXIS_MARGIN * scaleFactor + eng.nz * (eng.ny * cellSize() + blockGap())
        offsetX = max(0f, (width - totalW) / 2f)
        offsetY = max(0f, (height - totalH) / 2f)
        invalidate()
    }

    // ── Touch → action ────────────────────────────────────────────────────────

    private fun handleTap(cx: Float, cy: Float) {
        val eng = engine ?: return
        if (eng.state == GameState.FAIL || eng.state == GameState.WIN) return
        val coord = canvasToCell(cx, cy) ?: return

        when (activeTool) {
            Tool.DIG -> {
                val cell = eng.cell(coord)
                val hit = if (cell.isRevealed && cell.adjacentMines > 0) {
                    eng.revealIfEnoughFlags(coord)
                } else {
                    eng.reveal(coord)
                }
                if (hit) {
                    failRevealSet.addAll(eng.allMines())
                    listener?.onMineHit()
                } else if (eng.state == GameState.WIN) {
                    listener?.onWin()
                }
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

    // ── Public controls ───────────────────────────────────────────────────────

    fun clearHighlight() {
        rangeHighlight.clear()
        invalidate()
    }

    fun revealAllMines() {
        val eng = engine ?: return
        failRevealSet.addAll(eng.allMines())
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val eng = engine ?: return

        canvas.drawColor(0xFF0F0F0F.toInt())

        // Draw all cells
        for (w in 0 until eng.nw) {
            for (z in 0 until eng.nz) {
                drawBlock(canvas, eng, z, w)
                drawBlockBorder(canvas, eng, z, w)
            }
        }

        // Axis labels
        drawAxisLabels(canvas, eng)
    }

    private fun drawBlock(canvas: Canvas, eng: GameEngine, z: Int, w: Int) {
        for (x in 0 until eng.nx) {
            for (y in 0 until eng.ny) {
                drawCell(canvas, eng, x, y, z, w)
            }
        }
    }

    private fun drawCell(canvas: Canvas, eng: GameEngine, x: Int, y: Int, z: Int, w: Int) {
        val rect = cellRect(x, y, z, w)
        // Cull cells outside the visible area
        if (rect.right < 0 || rect.left > width || rect.bottom < 0 || rect.top > height) return

        val cell = eng.cell(x, y, z, w)
        val coord = Coord(x, y, z, w)
        val cs = cellSize()
        val textSize = (cs * 0.45f).coerceIn(8f, 32f)
        val cornerR = (cs * 0.12f).coerceIn(1f, 6f)

        // ── Background ──
        val bgColor = when {
            failRevealSet.contains(coord) && cell.isMine -> 0xFF7B1010.toInt()
            cell.isFlagged        -> 0xFF3A2E00.toInt()
            cell.isRevealed       -> 0xFF181818.toInt()
            else                  -> 0xFF2C2C2C.toInt()
        }
        paintCell.style = Paint.Style.FILL
        paintCell.color = bgColor
        canvas.drawRoundRect(rect, cornerR, cornerR, paintCell)

        // ── Content ──
        when {
            // Failed mine reveal
            failRevealSet.contains(coord) && cell.isMine && !cell.isFlagged -> {
                drawMineSymbol(canvas, rect)
            }
            // Wrongly flagged after fail
            failRevealSet.isNotEmpty() && cell.isFlagged && !cell.isMine -> {
                drawFlagSymbol(canvas, rect, textSize, wrong = true)
            }
            cell.isFlagged -> {
                drawFlagSymbol(canvas, rect, textSize, wrong = false)
            }
            cell.isRevealed && cell.isMine -> {
                drawMineSymbol(canvas, rect)
            }
            cell.isRevealed && cell.adjacentMines > 0 -> {
                paintText.color = numColours[cell.adjacentMines.coerceIn(1, 8)]
                paintText.textSize = textSize
                canvas.drawText(
                    cell.adjacentMines.toString(),
                    rect.centerX(), rect.centerY() + textSize * 0.35f,
                    paintText
                )
            }
            else -> { /* empty revealed or hidden */ }
        }

        // ── Range highlight overlay ──
        if (rangeHighlight.contains(coord)) {
            canvas.drawRoundRect(rect, cornerR, cornerR, paintHighlight)
        }

        // ── Border ──
        paintBorder.style = Paint.Style.STROKE
        canvas.drawRoundRect(rect, cornerR, cornerR, paintBorder)
    }

    private fun drawMineSymbol(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX(); val cy = rect.centerY()
        val r = rect.width() * 0.25f
        paintCell.color = 0xFFEF5350.toInt()
        paintCell.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r, paintCell)
        // Cross lines
        val arm = r * 0.7f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, paintMineX)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, paintMineX)
    }

    private fun drawFlagSymbol(canvas: Canvas, rect: RectF, textSize: Float, wrong: Boolean) {
        paintText.textSize = textSize * 1.1f
        paintText.color = if (wrong) 0xFF888888.toInt() else 0xFFFFB300.toInt()
        canvas.drawText("⚑", rect.centerX(), rect.centerY() + textSize * 0.35f, paintText)
        if (wrong) {
            // Strike-through
            paintMineX.color = 0xFFEF5350.toInt()
            canvas.drawLine(rect.left + 4f, rect.top + 4f,
                            rect.right - 4f, rect.bottom - 4f, paintMineX)
        }
    }

    private fun drawBlockBorder(canvas: Canvas, eng: GameEngine, z: Int, w: Int) {
        val orig = blockOrigin(z, w)
        val cs = cellSize()
        val bw = eng.nx * cs
        val bh = eng.ny * cs
        val rr = (cs * 0.12f).coerceIn(2f, 8f)
        val bRect = RectF(orig.x, orig.y, orig.x + bw, orig.y + bh)
        canvas.drawRoundRect(bRect, rr, rr, paintBlockBrd)
    }

    private fun drawAxisLabels(canvas: Canvas, eng: GameEngine) {
        val ax = axisMargin()
        val cs = cellSize(); val gap = blockGap()
        val labelSize = (AXIS_LABEL_SIZE * scaleFactor).coerceIn(10f, 28f)
        paintAxis.textSize = labelSize

        // w axis labels (top row)
        for (w in 0 until eng.nw) {
            val bx = offsetX + ax + w * (eng.nx * cs + gap) + (eng.nx * cs) / 2f
            val by = offsetY + ax - labelSize * 0.4f
            paintAxis.color = 0xFF4FC3F7.toInt()
            canvas.drawText("w${w}", bx, by, paintAxis)
        }

        // z axis labels (left column)
        for (z in 0 until eng.nz) {
            val bx = offsetX + ax * 0.45f
            val by = offsetY + ax + z * (eng.ny * cs + gap) + (eng.ny * cs) / 2f + labelSize * 0.35f
            paintAxis.color = 0xFF66BB6A.toInt()
            canvas.drawText("z${z}", bx, by, paintAxis)
        }

        // Corner label
        paintAxis.color = 0xFF888888.toInt()
        paintAxis.textSize = (labelSize * 0.75f).coerceIn(8f, 18f)
        canvas.drawText("w→", offsetX + ax * 0.5f, offsetY + ax * 0.35f, paintAxis)
        canvas.drawText("z↓", offsetX + ax * 0.18f, offsetY + ax + labelSize, paintAxis)
    }
}
