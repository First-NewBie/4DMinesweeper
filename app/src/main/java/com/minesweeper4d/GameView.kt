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

enum class ViewMode { MODE_4D, MODE_3D, MODE_2D }
enum class Tool     { DIG, FLAG, RANGE }

interface GameViewListener {
    fun onMineHit()
    fun onWin()
    fun onCellChanged()
}

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Public state ──────────────────────────────────────────────────────────
    var engine: GameEngine? = null
    var activeTool: Tool = Tool.DIG
    var listener: GameViewListener? = null
    var wSlice = 0
    var zSlice = 0
    var viewMode: ViewMode = ViewMode.MODE_4D
        set(value) {
            if (field != value) { startSweepAnim(); field = value }
        }

    private val rangeHighlight = mutableSetOf<Coord>()
    private val failRevealSet  = mutableSetOf<Coord>()

    // ── Isometric constants ───────────────────────────────────────────────────
    // Tile diamond width/height and vertical depth
    private val BASE_ISO_W = 72f   // full width of top diamond
    private val BASE_ISO_H = 36f   // height of top diamond (= BASE_ISO_W/2)
    private val BASE_ISO_D = 28f   // depth of vertical faces (unrevealed)
    private val BASE_ISO_D_REV = 6f // depth when revealed (shallow)

    // Flat-mode constants (2D / 4D)
    private val BASE_CELL    = 52f
    private val BLOCK_GAP    = 14f
    private val AXIS_MARGIN  = 44f

    private fun isoW() = BASE_ISO_W * scaleFactor
    private fun isoH() = BASE_ISO_H * scaleFactor
    private fun isoD() = BASE_ISO_D * scaleFactor
    private fun isoDR()= BASE_ISO_D_REV * scaleFactor
    private fun cs()   = BASE_CELL * scaleFactor
    private fun gap()  = BLOCK_GAP * scaleFactor
    private fun ax()   = AXIS_MARGIN * scaleFactor

    // ── Paint ─────────────────────────────────────────────────────────────────
    private val pFill  = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL }
    private val pStroke= Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.strokeWidth = 1.2f; it.color = 0x40000000 }
    private val pText  = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.textAlign = Paint.Align.CENTER; it.isFakeBoldText = true }
    private val pAxis  = Paint(Paint.ANTI_ALIAS_FLAG).also { it.textAlign = Paint.Align.CENTER }
    private val pHL    = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0x35FFFFFF; it.style = Paint.Style.FILL }
    private val pMineX = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0xFFEF5350.toInt(); it.style = Paint.Style.STROKE
        it.strokeWidth = 3f; it.strokeCap = Paint.Cap.ROUND }
    private val pSweep = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL }
    private val pBorder= Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.strokeWidth = 1f; it.color = 0xFF383838.toInt() }
    private val pBlockB= Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.strokeWidth = 2f; it.color = 0xFF555555.toInt() }

    private val numColors = intArrayOf(0,
        0xFF4FC3F7.toInt(), 0xFF66BB6A.toInt(), 0xFFEF5350.toInt(), 0xFF7E57C2.toInt(),
        0xFFFF7043.toInt(), 0xFF26C6DA.toInt(), 0xFFEC407A.toInt(), 0xFF9E9E9E.toInt())

    // ── Sweep animation ───────────────────────────────────────────────────────
    private var sweepFrac = 1f
    private var sweepBmp: Bitmap? = null
    private var sweepAnim: ValueAnimator? = null

    private fun startSweepAnim() {
        if (width <= 0 || height <= 0) return
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawContent(Canvas(bmp))
        sweepBmp  = bmp
        sweepFrac = 0f
        sweepAnim?.cancel()
        sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350; interpolator = DecelerateInterpolator()
            addUpdateListener { sweepFrac = it.animatedValue as Float; invalidate() }
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
                invalidate(); return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                offsetX -= dx; offsetY -= dy; invalidate(); return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x, e.y); return true
            }
        })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(e)
        return true
    }

    // ── ISO: grid → screen ────────────────────────────────────────────────────
    // Returns the TOP VERTEX of the top-diamond of cell (gx, gy, gz).
    // Origin is at cell (0, 0, nz-1) top vertex → positioned at (offsetX, offsetY).
    private fun gridToIso(gx: Float, gy: Float, gz: Float): PointF {
        val eng = engine ?: return PointF(0f, 0f)
        val w = isoW(); val h = isoH(); val d = isoD()
        val sx = offsetX + (gx - gy) * w / 2f
        val sy = offsetY + (gx + gy) * h / 2f + (eng.nz - 1 - gz) * d
        return PointF(sx, sy)
    }

    // ── Center grid ───────────────────────────────────────────────────────────
    fun centerGrid() {
        val eng = engine ?: return
        scaleFactor = 1f
        when (viewMode) {
            ViewMode.MODE_3D -> {
                // Center the isometric grid
                val w = isoW(); val h = isoH(); val d = isoD()
                val gridScreenW = (eng.nx + eng.ny) * w / 2f
                val gridScreenH = (eng.nx + eng.ny) * h / 2f + (eng.nz - 1) * d + isoD()
                // Put (0,0,nz-1) top vertex at (viewCenterX, topMargin)
                offsetX = width / 2f
                offsetY = (height - gridScreenH) / 2f + 32f * scaleFactor
            }
            ViewMode.MODE_4D -> {
                val totalW = ax() + eng.nw * (eng.nx * cs() + gap())
                val totalH = ax() + eng.nz * (eng.ny * cs() + gap())
                offsetX = max(0f, (width  - totalW) / 2f)
                offsetY = max(0f, (height - totalH) / 2f)
            }
            ViewMode.MODE_2D -> {
                val totalW = ax() + eng.nx * cs()
                val totalH = ax() + eng.ny * cs()
                offsetX = max(0f, (width  - totalW) / 2f)
                offsetY = max(0f, (height - totalH) / 2f)
            }
        }
        invalidate()
    }

    fun resetView() { scaleFactor = 1f; offsetX = 0f; offsetY = 0f; invalidate() }
    fun clearHighlight() { rangeHighlight.clear(); invalidate() }
    fun revealAllMines() { failRevealSet.addAll(engine?.allMines() ?: emptyList()); invalidate() }

    // ── Touch → action ────────────────────────────────────────────────────────
    private fun handleTap(cx: Float, cy: Float) {
        val eng = engine ?: return
        if (eng.state == GameState.FAIL || eng.state == GameState.WIN) return
        val coord = when (viewMode) {
            ViewMode.MODE_3D -> canvasToCellIso(cx, cy)
            ViewMode.MODE_4D -> canvasToCellFlat4D(cx, cy)
            ViewMode.MODE_2D -> canvasToCellFlat2D(cx, cy)
        } ?: return

        when (activeTool) {
            Tool.DIG -> {
                val cell = eng.cell(coord)
                val hit  = if (cell.isRevealed && cell.adjacentMines > 0)
                               eng.revealIfEnoughFlags(coord) else eng.reveal(coord)
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

    // ── Tap detection ─────────────────────────────────────────────────────────

    private fun canvasToCellIso(cx: Float, cy: Float): Coord? {
        val eng = engine ?: return null
        // Check front-to-back: sort descending x+y then descending z
        data class C3(val x: Int, val y: Int, val z: Int)
        val cells = ArrayList<C3>(eng.nx * eng.ny * eng.nz)
        for (x in 0 until eng.nx) for (y in 0 until eng.ny) for (z in 0 until eng.nz)
            cells.add(C3(x, y, z))
        cells.sortWith(compareByDescending<C3> { it.x + it.y }.thenByDescending { it.z })

        val w = isoW(); val h = isoH()
        for (c in cells) {
            val cell = eng.cell(c.x, c.y, c.z, wSlice)
            val d = if (cell.isRevealed) isoDR() else isoD()
            val sp = gridToIso(c.x.toFloat(), c.y.toFloat(), c.z.toFloat())
            val top   = PointF(sp.x,        sp.y)
            val right = PointF(sp.x + w/2f, sp.y + h/2f)
            val bot   = PointF(sp.x,        sp.y + h)
            val left  = PointF(sp.x - w/2f, sp.y + h/2f)
            val bR    = PointF(right.x, right.y + d)
            val bBot  = PointF(bot.x,   bot.y   + d)
            val bL    = PointF(left.x,  left.y  + d)

            if (pointInPoly(cx, cy, arrayOf(top, right, bot, left)) ||
                pointInPoly(cx, cy, arrayOf(right, bR, bBot, bot)) ||
                pointInPoly(cx, cy, arrayOf(left, bot, bBot, bL))) {
                return Coord(c.x, c.y, c.z, wSlice)
            }
        }
        return null
    }

    private fun canvasToCellFlat4D(cx: Float, cy: Float): Coord? {
        val eng = engine ?: return null
        val bw = eng.nx * cs() + gap(); val bh = eng.ny * cs() + gap()
        val w  = ((cx - offsetX - ax()) / bw).toInt()
        val z  = ((cy - offsetY - ax()) / bh).toInt()
        if (w !in 0 until eng.nw || z !in 0 until eng.nz) return null
        val ix = cx - offsetX - ax() - w * bw
        val iy = cy - offsetY - ax() - z * bh
        if (ix < 0 || ix >= eng.nx * cs() || iy < 0 || iy >= eng.ny * cs()) return null
        return Coord((ix / cs()).toInt(), (iy / cs()).toInt(), z, w)
    }

    private fun canvasToCellFlat2D(cx: Float, cy: Float): Coord? {
        val eng = engine ?: return null
        val ix  = cx - offsetX - ax(); val iy = cy - offsetY - ax()
        if (ix < 0 || ix >= eng.nx * cs() || iy < 0 || iy >= eng.ny * cs()) return null
        return Coord((ix / cs()).toInt(), (iy / cs()).toInt(), zSlice, wSlice)
    }

    // ── Point-in-convex-polygon (CCW winding) ─────────────────────────────────
    private fun pointInPoly(px: Float, py: Float, pts: Array<PointF>): Boolean {
        val n = pts.size
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i + 1) % n]
            if ((b.x - a.x) * (py - a.y) - (b.y - a.y) * (px - a.x) < 0) return false
        }
        return true
    }

    // ── Draw ──────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF0F0F0F.toInt())
        val bmp = sweepBmp
        if (bmp != null && sweepFrac < 1f) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
            val sx = width * sweepFrac
            canvas.save(); canvas.clipRect(0f, 0f, sx, height.toFloat())
            drawContent(canvas); canvas.restore()
            pSweep.color = 0x884FC3F7.toInt()
            canvas.drawRect(sx - 3f, 0f, sx + 3f, height.toFloat(), pSweep)
        } else {
            sweepBmp = null; drawContent(canvas)
        }
    }

    private fun drawContent(canvas: Canvas) {
        val eng = engine ?: return
        when (viewMode) {
            ViewMode.MODE_3D -> drawIso3D(canvas, eng)
            ViewMode.MODE_4D -> { drawAll4D(canvas, eng); drawAxisLabels4D(canvas, eng) }
            ViewMode.MODE_2D -> { drawFlat2D(canvas, eng); drawAxisLabels2D(canvas, eng) }
        }
    }

    // ── ISO 3D renderer ───────────────────────────────────────────────────────
    private fun drawIso3D(canvas: Canvas, eng: GameEngine) {
        // Painter's algorithm: sort back-to-front
        data class C3(val x: Int, val y: Int, val z: Int)
        val cells = ArrayList<C3>(eng.nx * eng.ny * eng.nz)
        for (x in 0 until eng.nx) for (y in 0 until eng.ny) for (z in 0 until eng.nz)
            cells.add(C3(x, y, z))
        cells.sortWith(compareBy<C3> { it.x + it.y }.thenBy { it.z })

        for (c in cells) drawIsoCube(canvas, eng, c.x, c.y, c.z)

        // Axis legend
        val ls = (15f * scaleFactor).coerceIn(8f, 20f)
        pAxis.textSize = ls; pAxis.color = 0xFF888888.toInt()
        canvas.drawText("x→  y↘  z↑  w=${wSlice}", width / 2f, 24f * scaleFactor + 8f, pAxis)
    }

    private fun drawIsoCube(canvas: Canvas, eng: GameEngine, x: Int, y: Int, z: Int) {
        val cell  = eng.cell(x, y, z, wSlice)
        val coord = Coord(x, y, z, wSlice)
        val sp    = gridToIso(x.toFloat(), y.toFloat(), z.toFloat())
        val w     = isoW(); val h = isoH()
        val depth = if (cell.isRevealed) isoDR() else isoD()

        // 7 vertices
        val top   = PointF(sp.x,        sp.y)
        val right = PointF(sp.x + w/2f, sp.y + h/2f)
        val bot   = PointF(sp.x,        sp.y + h)
        val left  = PointF(sp.x - w/2f, sp.y + h/2f)
        val bR    = PointF(right.x, right.y + depth)
        val bBot  = PointF(bot.x,   bot.y   + depth)
        val bL    = PointF(left.x,  left.y  + depth)

        // Cull off-screen
        if (bBot.y < 0 || top.y > height || right.x < 0 || left.x > width) return

        val baseColor = cellBaseColor(cell, coord)
        val isHL      = rangeHighlight.contains(coord)

        // ── Right face ──────────────────────────────────────────────────────
        drawFace(canvas, arrayOf(right, bR, bBot, bot), darken(baseColor, 0.62f), isHL)
        // ── Left face ───────────────────────────────────────────────────────
        drawFace(canvas, arrayOf(left, bot, bBot, bL), darken(baseColor, 0.50f), isHL)
        // ── Top face ────────────────────────────────────────────────────────
        drawFace(canvas, arrayOf(top, right, bot, left), baseColor, isHL)

        // ── Content on top face ─────────────────────────────────────────────
        val fcx = sp.x; val fcy = sp.y + h / 2f   // center of top diamond
        val ts  = (w * 0.28f).coerceIn(8f, 26f)

        when {
            failRevealSet.contains(coord) && cell.isMine && !cell.isFlagged -> {
                drawIsoMine(canvas, fcx, fcy, ts)
            }
            failRevealSet.isNotEmpty() && cell.isFlagged && !cell.isMine -> {
                pText.color = 0xFF888888.toInt(); pText.textSize = ts
                canvas.drawText("⚑", fcx, fcy + ts * 0.35f, pText)
                pMineX.color = 0xFFEF5350.toInt()
                canvas.drawLine(fcx - ts*0.5f, fcy - ts*0.5f, fcx + ts*0.5f, fcy + ts*0.5f, pMineX)
            }
            cell.isFlagged -> {
                pText.color = 0xFFFFB300.toInt(); pText.textSize = ts * 1.1f
                canvas.drawText("⚑", fcx, fcy + ts * 0.35f, pText)
            }
            cell.isRevealed && cell.isMine -> drawIsoMine(canvas, fcx, fcy, ts)
            cell.isRevealed && cell.adjacentMines > 0 -> {
                pText.color = numColors[cell.adjacentMines.coerceIn(1,8)]
                pText.textSize = ts
                canvas.drawText(cell.adjacentMines.toString(), fcx, fcy + ts*0.35f, pText)
            }
        }
    }

    private fun drawFace(canvas: Canvas, pts: Array<PointF>, color: Int, highlight: Boolean) {
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }
        pFill.color = color; canvas.drawPath(path, pFill)
        if (highlight) { pFill.color = 0x25FFFFFF; canvas.drawPath(path, pFill) }
        canvas.drawPath(path, pStroke)
    }

    private fun drawIsoMine(canvas: Canvas, cx: Float, cy: Float, ts: Float) {
        pFill.color = 0xFFEF5350.toInt()
        canvas.drawCircle(cx, cy, ts * 0.45f, pFill)
        val a = ts * 0.3f
        canvas.drawLine(cx-a, cy-a, cx+a, cy+a, pMineX)
        canvas.drawLine(cx+a, cy-a, cx-a, cy+a, pMineX)
    }

    // ── Cell color ────────────────────────────────────────────────────────────
    private fun cellBaseColor(cell: Cell, coord: Coord): Int = when {
        failRevealSet.contains(coord) && cell.isMine -> 0xFF8B1A1A.toInt()
        cell.isFlagged   -> 0xFF4A3A00.toInt()
        cell.isRevealed  -> 0xFF222222.toInt()
        else             -> 0xFF3A3A3A.toInt()
    }

    private fun darken(color: Int, f: Float) = Color.argb(
        Color.alpha(color),
        (Color.red(color)   * f).toInt().coerceIn(0, 255),
        (Color.green(color) * f).toInt().coerceIn(0, 255),
        (Color.blue(color)  * f).toInt().coerceIn(0, 255)
    )

    // ── Flat 4D renderer ──────────────────────────────────────────────────────
    private fun drawAll4D(canvas: Canvas, eng: GameEngine) {
        for (w in 0 until eng.nw) for (z in 0 until eng.nz) {
            drawFlatBlock(canvas, eng, z, w)
            drawFlatBlockBorder(canvas, eng, z, w)
        }
    }

    // ── Flat 2D renderer ──────────────────────────────────────────────────────
    private fun drawFlat2D(canvas: Canvas, eng: GameEngine) {
        drawFlatBlock(canvas, eng, zSlice, wSlice, render_z = 0, render_w = 0)
        drawFlatBlockBorder(canvas, eng, 0, 0)
    }

    private fun flatBlockOrigin(renderZ: Int, renderW: Int): PointF = PointF(
        offsetX + ax() + renderW * (engine!!.nx * cs() + gap()),
        offsetY + ax() + renderZ * (engine!!.ny * cs() + gap())
    )

    private fun drawFlatBlock(
        canvas: Canvas, eng: GameEngine,
        data_z: Int, data_w: Int,
        render_z: Int = data_z, render_w: Int = data_w
    ) {
        val o = flatBlockOrigin(render_z, render_w); val c = cs()
        for (x in 0 until eng.nx) for (y in 0 until eng.ny) {
            val cell  = eng.cell(x, y, data_z, data_w)
            val coord = Coord(x, y, data_z, data_w)
            val rect  = RectF(o.x+x*c, o.y+y*c, o.x+(x+1)*c, o.y+(y+1)*c)
            if (rect.right<0||rect.left>width||rect.bottom<0||rect.top>height) continue
            val cr    = (c*0.12f).coerceIn(1f,6f)
            val ts    = (c*0.45f).coerceIn(8f,32f)

            pFill.color = cellBaseColor(cell, coord)
            canvas.drawRoundRect(rect, cr, cr, pFill)

            when {
                failRevealSet.contains(coord) && cell.isMine && !cell.isFlagged -> {
                    val r = rect.width()*0.25f
                    pFill.color = 0xFFEF5350.toInt()
                    canvas.drawCircle(rect.centerX(), rect.centerY(), r, pFill)
                    val a = r*0.7f
                    canvas.drawLine(rect.centerX()-a,rect.centerY()-a,rect.centerX()+a,rect.centerY()+a,pMineX)
                    canvas.drawLine(rect.centerX()+a,rect.centerY()-a,rect.centerX()-a,rect.centerY()+a,pMineX)
                }
                failRevealSet.isNotEmpty() && cell.isFlagged && !cell.isMine -> {
                    pText.color=0xFF888888.toInt(); pText.textSize=ts
                    canvas.drawText("⚑",rect.centerX(),rect.centerY()+ts*0.35f,pText)
                    canvas.drawLine(rect.left+4f,rect.top+4f,rect.right-4f,rect.bottom-4f,pMineX)
                }
                cell.isFlagged -> {
                    pText.color=0xFFFFB300.toInt(); pText.textSize=ts*1.1f
                    canvas.drawText("⚑",rect.centerX(),rect.centerY()+ts*0.35f,pText)
                }
                cell.isRevealed && cell.isMine -> {
                    val r=rect.width()*0.25f; pFill.color=0xFFEF5350.toInt()
                    canvas.drawCircle(rect.centerX(),rect.centerY(),r,pFill)
                    val a=r*0.7f
                    canvas.drawLine(rect.centerX()-a,rect.centerY()-a,rect.centerX()+a,rect.centerY()+a,pMineX)
                    canvas.drawLine(rect.centerX()+a,rect.centerY()-a,rect.centerX()-a,rect.centerY()+a,pMineX)
                }
                cell.isRevealed && cell.adjacentMines > 0 -> {
                    pText.color=numColors[cell.adjacentMines.coerceIn(1,8)]; pText.textSize=ts
                    canvas.drawText(cell.adjacentMines.toString(),rect.centerX(),rect.centerY()+ts*0.35f,pText)
                }
            }
            if (rangeHighlight.contains(coord)) canvas.drawRoundRect(rect,cr,cr,pHL)
            canvas.drawRoundRect(rect,cr,cr,pBorder)
        }
    }

    private fun drawFlatBlockBorder(canvas: Canvas, eng: GameEngine, z: Int, w: Int) {
        val o = flatBlockOrigin(z, w); val c = cs()
        val rr = (c*0.12f).coerceIn(2f,8f)
        canvas.drawRoundRect(RectF(o.x,o.y,o.x+eng.nx*c,o.y+eng.ny*c),rr,rr,pBlockB)
    }

    // ── Axis labels ───────────────────────────────────────────────────────────
    private fun drawAxisLabels4D(canvas: Canvas, eng: GameEngine) {
        val a=ax(); val c=cs(); val g=gap()
        val ls=(18f*scaleFactor).coerceIn(10f,26f); pAxis.textSize=ls
        pAxis.color=0xFF4FC3F7.toInt()
        for (w in 0 until eng.nw)
            canvas.drawText("w$w",offsetX+a+w*(eng.nx*c+g)+(eng.nx*c)/2f,offsetY+a-ls*0.4f,pAxis)
        pAxis.color=0xFF66BB6A.toInt()
        for (z in 0 until eng.nz)
            canvas.drawText("z$z",offsetX+a*0.45f,offsetY+a+z*(eng.ny*c+g)+(eng.ny*c)/2f+ls*0.35f,pAxis)
        pAxis.color=0xFF888888.toInt(); pAxis.textSize=(ls*0.7f).coerceIn(8f,16f)
        canvas.drawText("w→",offsetX+a*0.5f,offsetY+a*0.38f,pAxis)
        canvas.drawText("z↓",offsetX+a*0.18f,offsetY+a+ls,pAxis)
    }

    private fun drawAxisLabels2D(canvas: Canvas, eng: GameEngine) {
        val a=ax(); val c=cs(); val ls=(16f*scaleFactor).coerceIn(9f,22f)
        pAxis.textSize=(ls*0.75f).coerceIn(8f,16f); pAxis.color=0xFF4FC3F7.toInt()
        canvas.drawText("w=${wSlice}  z=${zSlice}",offsetX+a+(eng.nx*c)/2f,offsetY+a-ls*0.4f,pAxis)
    }
}
