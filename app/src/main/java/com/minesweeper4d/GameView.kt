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
    var wSlice = 0; private set
    var zSlice = 0; private set

    // ── View mode (bitmap-sweep transition) ───────────────────────────────────
    var viewMode: ViewMode = ViewMode.MODE_3D
        set(value) {
            if (field == value) return
            captureSweepBitmap()
            field = value
            startSweepAnim()
            post { centerGrid() }
        }

    private var sweepBmp: Bitmap? = null
    private var sweepFrac = 1f
    private var sweepAnim: ValueAnimator? = null

    private fun captureSweepBitmap() {
        if (width <= 0 || height <= 0) return
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawContent(Canvas(bmp))
        sweepBmp = bmp
    }

    private fun startSweepAnim() {
        sweepFrac = 0f
        sweepAnim?.cancel()
        sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450; interpolator = DecelerateInterpolator()
            addUpdateListener { sweepFrac = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ── Layer alpha (sine-eased) ───────────────────────────────────────────────
    private var prevZSlice = 0; private var prevWSlice = 0
    private var layerT = 1f
    private var layerAnim: ValueAnimator? = null

    fun changeLayer(newZ: Int? = null, newW: Int? = null) {
        prevZSlice = zSlice; prevWSlice = wSlice
        if (newZ != null) zSlice = newZ
        if (newW != null) wSlice = newW
        layerT = 0f; layerAnim?.cancel()
        layerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280; interpolator = LinearInterpolator()
            addUpdateListener { layerT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun layerAlpha(isCurrent: Boolean, wasPrev: Boolean): Float {
        val target = if (isCurrent) 1f else 0.18f
        val from   = if (wasPrev)   1f else 0.18f
        val t      = sin(PI.toFloat() / 2f * layerT)
        return from + (target - from) * t
    }

    private fun layerAlphaInt(idx: Int, cur: Int, prev: Int) =
        (layerAlpha(idx == cur, idx == prev) * 255).toInt().coerceIn(0, 255)

    // ── Highlights ────────────────────────────────────────────────────────────
    private val rangeHL   = mutableSetOf<Coord>()
    private val failMines = mutableSetOf<Coord>()

    // ── ISO constants ─────────────────────────────────────────────────────────
    private val BASE_W  = 72f; private val BASE_H  = 36f
    private val BASE_D  = 28f; private val BASE_DR = 5f
    private val BG      = 0xFF0F0F0F.toInt()

    private fun iW() = BASE_W * scaleFactor
    private fun iH() = BASE_H * scaleFactor
    private fun iD() = BASE_D * scaleFactor
    private fun iDR()= BASE_DR * scaleFactor

    // ── Paint ─────────────────────────────────────────────────────────────────
    private val pFill  = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL }
    private val pEdge  = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.STROKE; it.strokeWidth = 1.4f; it.color = 0x55000000 }
    private val pText  = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.textAlign = Paint.Align.CENTER; it.isFakeBoldText = true }
    private val pInfo  = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.textAlign = Paint.Align.CENTER; it.color = 0xFF555555.toInt() }
    private val pMineX = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = 0xFFEF5350.toInt(); it.style = Paint.Style.STROKE
        it.strokeWidth = 3f; it.strokeCap = Paint.Cap.ROUND }

    private val numColors = intArrayOf(0,
        0xFF4FC3F7.toInt(), 0xFF66BB6A.toInt(), 0xFFEF5350.toInt(), 0xFF7E57C2.toInt(),
        0xFFFF7043.toInt(), 0xFF26C6DA.toInt(), 0xFFEC407A.toInt(), 0xFF9E9E9E.toInt())

    // ── Pan / zoom / rotate ───────────────────────────────────────────────────
    private var scaleFactor = 1f
    private var offsetX = 0f; private var offsetY = 0f
    private var azimuth = (PI / 4.0).toFloat()

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
                if (activeTool == Tool.MOVE) {
                    azimuth -= dx * 0.013f; invalidate()
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

    // ── Projection ────────────────────────────────────────────────────────────
    private fun toScreen(gx: Float, gy: Float, gz: Float,
                          offX: Float = 0f, offY: Float = 0f): PointF {
        val eng = engine ?: return PointF(offsetX, offsetY)
        val cosA = cos(azimuth); val sinA = sin(azimuth)
        val rx = gx * cosA - gy * sinA
        val ry = gx * sinA + gy * cosA
        return PointF(
            offsetX + offX + rx * iW() / 2f,
            offsetY + offY + ry * iH() / 2f + (eng.nz - 1 - gz) * iD()
        )
    }

    // ── Center grid ───────────────────────────────────────────────────────────
    fun centerGrid() {
        val eng = engine ?: return
        val cubeW = (eng.nx + eng.ny) * iW() / 2f
        val cubeH = (eng.nx + eng.ny) * iH() / 2f + (eng.nz - 1) * iD() + iD()
        when (viewMode) {
            ViewMode.MODE_4D -> {
                // Single merged cube centered
                offsetX = width / 2f
                offsetY = max(30f, (height - cubeH) / 2f)
            }
            ViewMode.MODE_3D -> {
                // Spread cubes: offset so center cube is in center
                val totalW = cubeW + eng.nw * cubeW * 0.7f
                offsetX = (width - totalW) / 2f + cubeW * 0.85f
                offsetY = max(30f, (height - cubeH) / 2f)
            }
            ViewMode.MODE_2D -> {
                // Staircase of current w: offset for staircase extent
                offsetX = width / 2f + iW() * 0.3f * (eng.nz - 1) / 2f
                offsetY = max(30f, (height - cubeH - iD() * 1.2f * (eng.nz - 1)) / 2f + 30f)
            }
        }
        invalidate()
    }

    fun resetView()      { scaleFactor = 1f; centerGrid() }
    fun clearHighlight() { rangeHL.clear(); invalidate() }
    fun revealAllMines() { failMines.addAll(engine?.allMines() ?: emptyList()); invalidate() }

    // ── Tap handling ──────────────────────────────────────────────────────────
    private fun handleTap(cx: Float, cy: Float) {
        val eng = engine ?: return
        if (eng.state == GameState.FAIL || eng.state == GameState.WIN) return
        val coord = when (viewMode) {
            ViewMode.MODE_4D -> tapMerged(cx, cy, eng)
            ViewMode.MODE_3D -> tapSpread(cx, cy, eng)
            ViewMode.MODE_2D -> tapStaircase(cx, cy, eng)
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
                eng.toggleFlag(coord); if (eng.state == GameState.WIN) listener?.onWin()
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

    // Hit test — only current wSlice/zSlice is tappable per mode
    private fun tapMerged(cx: Float, cy: Float, eng: GameEngine): Coord? {
        // 4D: tap on current wSlice (topmost, drawn last)
        for (c in sorted3D(eng, front = true)) {
            val cell = eng.cell(c[0], c[1], c[2], wSlice)
            val sp   = toScreen(c[0].toFloat(), c[1].toFloat(), c[2].toFloat())
            if (cubeHit(cx, cy, sp, if (cell.isRevealed) iDR() else iD()))
                return Coord(c[0], c[1], c[2], wSlice)
        }
        return null
    }

    private fun tapSpread(cx: Float, cy: Float, eng: GameEngine): Coord? {
        // 3D: tap on current wSlice cube, any z (but only current z-layer is active)
        val (offX, offY) = wCubeOffset(wSlice, eng)
        for (c in sorted3D(eng, front = true)) {
            if (c[2] != zSlice) continue   // only current z-layer interactive
            val cell = eng.cell(c[0], c[1], c[2], wSlice)
            val sp   = toScreen(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), offX, offY)
            if (cubeHit(cx, cy, sp, if (cell.isRevealed) iDR() else iD()))
                return Coord(c[0], c[1], zSlice, wSlice)
        }
        return null
    }

    private fun tapStaircase(cx: Float, cy: Float, eng: GameEngine): Coord? {
        // 2D: tap on current zSlice layer only
        val (offX, offY, _) = staircaseOffset(zSlice)
        val iw = iW(); val ih = iH()
        for (c in sorted3D(eng, front = true)) {
            if (c[2] != zSlice) continue
            val sp  = toScreen(c[0].toFloat(), c[1].toFloat(), 0f, offX, offY)
            val top = PointF(sp.x, sp.y)
            val rgt = PointF(sp.x + iw/2f, sp.y + ih/2f)
            val bot = PointF(sp.x,          sp.y + ih)
            val lft = PointF(sp.x - iw/2f, sp.y + ih/2f)
            if (pip(cx, cy, arrayOf(top, rgt, bot, lft)))
                return Coord(c[0], c[1], zSlice, wSlice)
        }
        return null
    }

    private fun cubeHit(cx: Float, cy: Float, sp: PointF, depth: Float): Boolean {
        val iw = iW(); val ih = iH()
        val top = PointF(sp.x, sp.y);             val rgt = PointF(sp.x+iw/2f, sp.y+ih/2f)
        val bot = PointF(sp.x, sp.y+ih);           val lft = PointF(sp.x-iw/2f, sp.y+ih/2f)
        val bR  = PointF(rgt.x, rgt.y+depth);     val bB  = PointF(bot.x, bot.y+depth)
        val bL  = PointF(lft.x, lft.y+depth)
        return pip(cx,cy,arrayOf(top,rgt,bot,lft)) ||
               pip(cx,cy,arrayOf(rgt,bR,bB,bot)) ||
               pip(cx,cy,arrayOf(lft,bot,bB,bL))
    }

    private fun pip(px: Float, py: Float, pts: Array<PointF>): Boolean {
        val n = pts.size
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i+1)%n]
            if ((b.x-a.x)*(py-a.y)-(b.y-a.y)*(px-a.x) < 0) return false
        }
        return true
    }

    // ── Offsets ───────────────────────────────────────────────────────────────
    private fun wCubeOffset(w: Int, eng: GameEngine): Pair<Float, Float> {
        val relW  = w - wSlice
        val stepX = ((eng.nx + eng.ny) * iW() / 2f * 0.72f + iW() * 0.2f) * relW
        val stepY = eng.ny * iH() / 2f * 0.45f * relW
        return Pair(stepX, stepY)
    }

    // Returns (screenOffX, screenOffY, zVisual) for staircase
    private fun staircaseOffset(z: Int): Triple<Float, Float, Float> {
        val relZ = z - zSlice
        return Triple(iW() * 0.42f * relZ, -iD() * 1.18f * relZ, 0f)
    }

    // ── Sort cells back→front ─────────────────────────────────────────────────
    private fun sorted3D(eng: GameEngine, front: Boolean = false): List<IntArray> {
        val sinA = sin(azimuth); val cosA = cos(azimuth)
        val list = ArrayList<IntArray>(eng.nx * eng.ny * eng.nz)
        for (x in 0 until eng.nx) for (y in 0 until eng.ny) for (z in 0 until eng.nz)
            list.add(intArrayOf(x, y, z))
        val cmp = compareBy<IntArray> { (it[0]*sinA + it[1]*cosA).toLong() }.thenBy { it[2] }
        list.sortWith(if (front) cmp.reversed() else cmp)
        return list
    }

    // ── Draw ──────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BG)
        val bmp = sweepBmp
        if (bmp != null && sweepFrac < 1f) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
            val sx = width * sweepFrac
            canvas.save(); canvas.clipRect(0f, 0f, sx, height.toFloat())
            drawContent(canvas); canvas.restore()
            pFill.color = 0x884FC3F7.toInt()
            canvas.drawRect(sx-3f, 0f, sx+3f, height.toFloat(), pFill)
        } else {
            sweepBmp = null; drawContent(canvas)
        }
    }

    private fun drawContent(canvas: Canvas) {
        val eng = engine ?: return
        when (viewMode) {
            ViewMode.MODE_4D -> draw4D(canvas, eng)
            ViewMode.MODE_3D -> draw3D(canvas, eng)
            ViewMode.MODE_2D -> draw2D(canvas, eng)
        }
    }

    // ── MODE_4D: single merged cube, w-layer brightness ───────────────────────
    private fun draw4D(canvas: Canvas, eng: GameEngine) {
        // Draw non-current w-slices first (back), current w last (front/on top)
        val wOrder = (0 until eng.nw).sortedBy { if (it == wSlice) 1 else 0 }
        for (w in wOrder) {
            val alpha = layerAlphaInt(w, wSlice, prevWSlice)
            val lp    = Paint().apply { this.alpha = alpha }
            canvas.saveLayer(null, lp)
            for (c in sorted3D(eng)) {
                // Range dimming: dim non-highlighted if range active
                val rangeDim = rangeAlpha(Coord(c[0], c[1], c[2], w))
                drawCube(canvas, eng, c[0], c[1], c[2], w, 0f, 0f, alpha, rangeDim)
            }
            canvas.restore()
        }
        label(canvas, "4D  w=${wSlice}")
    }

    // ── MODE_3D: w-cubes spread, z-layer brightness ───────────────────────────
    private fun draw3D(canvas: Canvas, eng: GameEngine) {
        // Determine w draw order (wSlice drawn last = on top of others)
        val wOrder = (0 until eng.nw).sortedBy { if (it == wSlice) 1 else 0 }
        for (w in wOrder) {
            val (offX, offY) = wCubeOffset(w, eng)
            // Non-current w-cubes: slightly dimmed but still visible
            val wAlpha = if (w == wSlice) 255 else 140
            val lp = Paint().apply { this.alpha = wAlpha }
            canvas.saveLayer(null, lp)
            // Draw each z-layer within this cube with z-brightness
            for (z in 0 until eng.nz) {
                val zA = layerAlphaInt(z, zSlice, prevZSlice)
                val zP = Paint().apply { this.alpha = zA }
                canvas.saveLayer(null, zP)
                for (c in sorted3D(eng)) {
                    if (c[2] != z) continue
                    val rangeDim = rangeAlpha(Coord(c[0], c[1], z, w))
                    drawCube(canvas, eng, c[0], c[1], z, w, offX, offY, zA, rangeDim)
                }
                canvas.restore()
            }
            canvas.restore()
        }
        label(canvas, "3D  w=${wSlice}  z=${zSlice}")
    }

    // ── MODE_2D: staircase of current w's z-layers, z-brightness ─────────────
    private fun draw2D(canvas: Canvas, eng: GameEngine) {
        // Draw from far layer to near layer (back to front)
        // "Far" in staircase = high z (goes upper-right), "near" = low z
        val zOrder = (eng.nz-1 downTo 0)
        for (z in zOrder) {
            val zA = layerAlphaInt(z, zSlice, prevZSlice)
            val (offX, offY, _) = staircaseOffset(z)
            val zP = Paint().apply { this.alpha = zA }
            canvas.saveLayer(null, zP)
            // Flat tile layer (use z=0 in projection, offset handles height)
            val sinA = sin(azimuth); val cosA = cos(azimuth)
            val tiles = ArrayList<IntArray>(eng.nx * eng.ny)
            for (x in 0 until eng.nx) for (y in 0 until eng.ny)
                tiles.add(intArrayOf(x, y))
            tiles.sortWith(compareBy { (it[0]*sinA + it[1]*cosA).toLong() })
            for (t in tiles) {
                val rangeDim = rangeAlpha(Coord(t[0], t[1], z, wSlice))
                drawFlatTile(canvas, eng, t[0], t[1], z, wSlice, offX, offY, zA, rangeDim)
            }
            canvas.restore()
        }
        label(canvas, "2D  w=${wSlice}  z=${zSlice}")
    }

    // ── Range alpha (dim non-highlighted cells) ───────────────────────────────
    // Returns 1f = full, 0.2f = dimmed (when range is active and cell not in range)
    private fun rangeAlpha(coord: Coord): Float {
        if (rangeHL.isEmpty()) return 1f
        return if (rangeHL.contains(coord)) 1f else 0.2f
    }

    // ── Draw one isometric cube ───────────────────────────────────────────────
    private fun drawCube(
        canvas: Canvas, eng: GameEngine,
        x: Int, y: Int, z: Int, w: Int,
        offX: Float, offY: Float,
        layerAlphaV: Int, rangeAlphaV: Float
    ) {
        val cell  = eng.cell(x, y, z, w)
        val coord = Coord(x, y, z, w)
        val sp    = toScreen(x.toFloat(), y.toFloat(), z.toFloat(), offX, offY)
        val iw    = iW(); val ih = iH()
        val depth = if (cell.isRevealed) iDR() else iD()

        if (sp.x+iw/2f<0||sp.x-iw/2f>width||sp.y+ih+depth<0||sp.y>height) return

        val top = PointF(sp.x,        sp.y)
        val rgt = PointF(sp.x+iw/2f,  sp.y+ih/2f)
        val bot = PointF(sp.x,        sp.y+ih)
        val lft = PointF(sp.x-iw/2f,  sp.y+ih/2f)
        val bR  = PointF(rgt.x, rgt.y+depth)
        val bB  = PointF(bot.x, bot.y+depth)
        val bL  = PointF(lft.x, lft.y+depth)

        val base = baseColor(cell, coord)
        // Blend toward background for range dimming
        val effBase = blendToBg(base, rangeAlphaV)
        val hl      = rangeHL.isNotEmpty() && rangeHL.contains(coord)

        drawFace(canvas, arrayOf(rgt, bR, bB, bot), darken(blendToBg(darken(base,0.62f), rangeAlphaV), 1f), hl)
        drawFace(canvas, arrayOf(lft, bot, bB, bL), darken(blendToBg(darken(base,0.50f), rangeAlphaV), 1f), hl)
        drawFace(canvas, arrayOf(top, rgt, bot, lft), effBase, hl)

        val fcx = sp.x; val fcy = sp.y+ih/2f
        val ts  = (iw*0.28f).coerceIn(8f, 26f)
        cellContent(canvas, cell, coord, fcx, fcy, ts)
    }

    // ── Draw one flat tile (for 2D staircase) ─────────────────────────────────
    private fun drawFlatTile(
        canvas: Canvas, eng: GameEngine,
        x: Int, y: Int, z: Int, w: Int,
        offX: Float, offY: Float,
        layerAlphaV: Int, rangeAlphaV: Float
    ) {
        val cell  = eng.cell(x, y, z, w)
        val coord = Coord(x, y, z, w)
        // Render at gz=0 in projection; offY handles layer separation
        val sp  = toScreen(x.toFloat(), y.toFloat(), 0f, offX, offY)
        val iw  = iW(); val ih = iH()
        val depth = iDR() * 0.6f  // flat tile

        if (sp.x+iw/2f<0||sp.x-iw/2f>width||sp.y+ih+depth<0||sp.y>height) return

        val top = PointF(sp.x,        sp.y)
        val rgt = PointF(sp.x+iw/2f,  sp.y+ih/2f)
        val bot = PointF(sp.x,        sp.y+ih)
        val lft = PointF(sp.x-iw/2f,  sp.y+ih/2f)
        val bR  = PointF(rgt.x, rgt.y+depth)
        val bB  = PointF(bot.x, bot.y+depth)
        val bL  = PointF(lft.x, lft.y+depth)

        val base    = baseColor(cell, coord)
        val effBase = blendToBg(base, rangeAlphaV)
        val hl      = rangeHL.isNotEmpty() && rangeHL.contains(coord)

        drawFace(canvas, arrayOf(rgt, bR, bB, bot), blendToBg(darken(base,0.62f), rangeAlphaV), hl)
        drawFace(canvas, arrayOf(lft, bot, bB, bL), blendToBg(darken(base,0.50f), rangeAlphaV), hl)
        drawFace(canvas, arrayOf(top, rgt, bot, lft), effBase, hl)

        val fcx = sp.x; val fcy = sp.y+ih/2f
        val ts  = (iw*0.28f).coerceIn(8f, 26f)
        cellContent(canvas, cell, coord, fcx, fcy, ts)
    }

    // ── Face drawing ──────────────────────────────────────────────────────────
    private fun drawFace(canvas: Canvas, pts: Array<PointF>, color: Int, hl: Boolean) {
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }
        pFill.color = color; canvas.drawPath(path, pFill)
        if (hl) { pFill.color = 0x30FFFFFF; canvas.drawPath(path, pFill) }
        canvas.drawPath(path, pEdge)
    }

    // ── Cell content ──────────────────────────────────────────────────────────
    private fun cellContent(canvas: Canvas, cell: Cell, coord: Coord,
                             cx: Float, cy: Float, ts: Float) {
        when {
            failMines.contains(coord) && cell.isMine && !cell.isFlagged -> mine(canvas, cx, cy, ts)
            failMines.isNotEmpty() && cell.isFlagged && !cell.isMine -> {
                pText.color = 0xFF888888.toInt(); pText.textSize = ts
                canvas.drawText("⚑", cx, cy+ts*0.35f, pText)
                pMineX.color = 0xFFEF5350.toInt()
                canvas.drawLine(cx-ts*.5f,cy-ts*.5f,cx+ts*.5f,cy+ts*.5f, pMineX)
            }
            cell.isFlagged -> {
                pText.color = 0xFFFFB300.toInt(); pText.textSize = ts*1.1f
                canvas.drawText("⚑", cx, cy+ts*0.35f, pText)
            }
            cell.isRevealed && cell.isMine -> mine(canvas, cx, cy, ts)
            cell.isRevealed && cell.adjacentMines > 0 -> {
                pText.color = numColors[cell.adjacentMines.coerceIn(1,8)]
                pText.textSize = ts
                canvas.drawText(cell.adjacentMines.toString(), cx, cy+ts*0.35f, pText)
            }
        }
    }

    private fun mine(canvas: Canvas, cx: Float, cy: Float, ts: Float) {
        pFill.color = 0xFFEF5350.toInt()
        canvas.drawCircle(cx, cy, ts*0.45f, pFill)
        val a = ts*0.3f
        canvas.drawLine(cx-a,cy-a,cx+a,cy+a,pMineX); canvas.drawLine(cx+a,cy-a,cx-a,cy+a,pMineX)
    }

    // ── Colour helpers ────────────────────────────────────────────────────────
    private fun baseColor(cell: Cell, coord: Coord): Int = when {
        failMines.contains(coord) && cell.isMine -> 0xFF8B1A1A.toInt()
        cell.isFlagged   -> 0xFF4A3A00.toInt()
        cell.isRevealed  -> 0xFF1E1E1E.toInt()
        else             -> 0xFF383838.toInt()
    }

    private fun darken(c: Int, f: Float) = Color.argb(
        Color.alpha(c),
        (Color.red(c)*f).toInt().coerceIn(0,255),
        (Color.green(c)*f).toInt().coerceIn(0,255),
        (Color.blue(c)*f).toInt().coerceIn(0,255))

    /** Blend color toward background (simulates transparency without saveLayer overhead). */
    private fun blendToBg(c: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f); val ia = 1f - a
        val br = Color.red(BG); val bg2 = Color.green(BG); val bb = Color.blue(BG)
        return Color.rgb(
            (Color.red(c)*a   + br*ia).toInt().coerceIn(0,255),
            (Color.green(c)*a + bg2*ia).toInt().coerceIn(0,255),
            (Color.blue(c)*a  + bb*ia).toInt().coerceIn(0,255))
    }

    private fun label(canvas: Canvas, text: String) {
        val ls = (13f*scaleFactor).coerceIn(8f, 18f)
        pInfo.textSize = ls
        canvas.drawText(text, width/2f, ls+6f, pInfo)
    }
}
