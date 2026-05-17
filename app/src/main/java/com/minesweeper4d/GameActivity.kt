package com.minesweeper4d

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.minesweeper4d.db.LeaderboardManager

class GameActivity : AppCompatActivity(), GameViewListener {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var gameView:     GameView
    private lateinit var tvMineCount:  TextView
    private lateinit var tvTimer:      TextView
    private lateinit var tvWinTime:    TextView
    private lateinit var tvLayerAxis:  TextView
    private lateinit var tvLayerNum:   TextView
    private lateinit var labelDimTop:  TextView

    private lateinit var btnDig:   LinearLayout
    private lateinit var btnFlag:  LinearLayout
    private lateinit var btnRange: LinearLayout

    private lateinit var seekDimension: SeekBar
    private lateinit var seekLayer:     SeekBar

    private lateinit var exitOverlay: View
    private lateinit var failOverlay: View
    private lateinit var winOverlay:  View

    // ── Config ────────────────────────────────────────────────────────────────
    private var nx = 3; private var ny = 3; private var nz = 3; private var nw = 3
    private var minePercent = 20

    // ── Engine ────────────────────────────────────────────────────────────────
    private lateinit var engine: GameEngine

    // ── Timer ─────────────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var startTimeMs = 0L
    private var elapsedMs   = 0L
    private var timerRunning = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            elapsedMs = System.currentTimeMillis() - startTimeMs
            tvTimer.text = LeaderboardManager.formatTime(elapsedMs)
            handler.postDelayed(this, 500)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        nx          = intent.getIntExtra("nx", 3)
        ny          = intent.getIntExtra("ny", 3)
        nz          = intent.getIntExtra("nz", 3)
        nw          = intent.getIntExtra("nw", 3)
        minePercent = intent.getIntExtra("minePercent", 20)

        bindViews()
        setupEngine()
        setupDimensionSlider()
        setupLayerSlider()
        setupToolButtons()
        setupOverlays()
        selectTool(Tool.DIG)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun bindViews() {
        gameView      = findViewById(R.id.gameView)
        tvMineCount   = findViewById(R.id.tvMineCount)
        tvTimer       = findViewById(R.id.tvTimer)
        tvWinTime     = findViewById(R.id.tvWinTime)
        tvLayerAxis   = findViewById(R.id.tvLayerAxis)
        tvLayerNum    = findViewById(R.id.tvLayerNum)
        labelDimTop   = findViewById(R.id.labelDimTop)
        btnDig        = findViewById(R.id.btnDig)
        btnFlag       = findViewById(R.id.btnFlag)
        btnRange      = findViewById(R.id.btnRange)
        seekDimension = findViewById(R.id.seekDimension)
        seekLayer     = findViewById(R.id.seekLayer)
        exitOverlay   = findViewById(R.id.exitOverlay)
        failOverlay   = findViewById(R.id.failOverlay)
        winOverlay    = findViewById(R.id.winOverlay)

        findViewById<View>(R.id.btnHamburger).setOnClickListener { showExitOverlay() }
    }

    private fun setupEngine() {
        engine            = GameEngine(nx, ny, nz, nw, minePercent)
        gameView.engine   = engine
        gameView.listener = this
        gameView.post     { gameView.centerGrid() }
        updateMineCount()
    }

    // ── Dimension slider (left) ───────────────────────────────────────────────
    // SeekBar: progress 2 = top = 4D, 1 = 3D, 0 = bottom = 2D
    // But Android SeekBar with rotation=270: dragging UP increases progress.
    // We invert: displayed mode = max - progress → 2=4D,1=3D,0=2D at top
    // However since rotation=270 flips direction: progress 0 is top visually.
    // So: progress 0 → 4D (top), progress 1 → 3D, progress 2 → 2D (bottom).

    private fun setupDimensionSlider() {
        // Max dimension depends on nw: if nw==1, 4D==3D so cap at 3D
        val maxDim = if (nw > 1) 2 else 1
        seekDimension.max      = maxDim
        seekDimension.progress = 0  // start at 4D (top)

        // Update top label to reflect max mode
        labelDimTop.text = if (nw > 1) "4D" else "3D"

        applyDimMode(0)  // initial

        seekDimension.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyDimMode(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    /**
     * progress 0 = top of slider = highest dimension (4D or 3D)
     * progress max = bottom = lowest dimension (2D)
     */
    private fun applyDimMode(progress: Int) {
        val maxDim = seekDimension.max
        val newMode = when {
            nw > 1 -> when (progress) {
                0    -> ViewMode.MODE_4D
                1    -> ViewMode.MODE_3D
                else -> ViewMode.MODE_2D
            }
            else -> when (progress) {   // nw==1: only 3D/2D
                0    -> ViewMode.MODE_3D
                else -> ViewMode.MODE_2D
            }
        }

        gameView.viewMode = newMode

        // Update layer slider range + label for new mode
        updateLayerSliderForMode(newMode)
        gameView.post { gameView.centerGrid() }
    }

    // ── Layer slider (right) ──────────────────────────────────────────────────

    private fun setupLayerSlider() {
        seekLayer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyLayerProgress(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        updateLayerSliderForMode(gameView.viewMode)
    }

    /**
     * Reconfigure the layer slider whenever view mode changes.
     * In 4D mode the slider is hidden (all layers visible).
     * In 3D mode the slider navigates w (0 .. nw-1).
     * In 2D mode the slider navigates z (0 .. nz-1).
     */
    private fun updateLayerSliderForMode(mode: ViewMode) {
        val rightPanel = findViewById<View>(R.id.rightPanel)
        when (mode) {
            ViewMode.MODE_4D -> {
                rightPanel.visibility = View.INVISIBLE
            }
            ViewMode.MODE_3D -> {
                rightPanel.visibility = View.VISIBLE
                val maxW = (nw - 1).coerceAtLeast(0)
                seekLayer.max      = maxW
                seekLayer.progress = gameView.wSlice.coerceIn(0, maxW)
                tvLayerAxis.text   = "w"
                tvLayerNum.text    = gameView.wSlice.toString()
            }
            ViewMode.MODE_2D -> {
                rightPanel.visibility = View.VISIBLE
                val maxZ = (nz - 1).coerceAtLeast(0)
                seekLayer.max      = maxZ
                seekLayer.progress = gameView.zSlice.coerceIn(0, maxZ)
                tvLayerAxis.text   = "z"
                tvLayerNum.text    = gameView.zSlice.toString()
            }
        }
    }

    /**
     * SeekBar rotation=270: progress 0 = top visually (first layer).
     * We map directly: layer index = progress.
     */
    private fun applyLayerProgress(progress: Int) {
        when (gameView.viewMode) {
            ViewMode.MODE_4D -> {}
            ViewMode.MODE_3D -> {
                gameView.wSlice  = progress
                tvLayerNum.text  = progress.toString()
                gameView.invalidate()
            }
            ViewMode.MODE_2D -> {
                gameView.zSlice  = progress
                tvLayerNum.text  = progress.toString()
                gameView.invalidate()
            }
        }
    }

    // ── Tool buttons ──────────────────────────────────────────────────────────

    private fun setupToolButtons() {
        btnDig.setOnClickListener  { gameView.clearHighlight(); selectTool(Tool.DIG)   }
        btnFlag.setOnClickListener { gameView.clearHighlight(); selectTool(Tool.FLAG)  }
        btnRange.setOnClickListener { selectTool(Tool.RANGE) }
    }

    private fun selectTool(tool: Tool) {
        gameView.activeTool = tool
        btnDig.setBackgroundResource(0)
        btnFlag.setBackgroundResource(0)
        btnRange.setBackgroundResource(0)
        when (tool) {
            Tool.DIG   -> btnDig.setBackgroundResource(R.drawable.tool_selected)
            Tool.FLAG  -> btnFlag.setBackgroundResource(R.drawable.tool_selected)
            Tool.RANGE -> btnRange.setBackgroundResource(R.drawable.tool_selected)
        }
    }

    // ── Overlays ──────────────────────────────────────────────────────────────

    private fun setupOverlays() {
        exitOverlay.setOnClickListener { }
        findViewById<View>(R.id.btnExitYes).setOnClickListener { goToMenu() }
        findViewById<View>(R.id.btnExitNo).setOnClickListener  { hideExitOverlay() }

        failOverlay.setOnClickListener { }
        findViewById<View>(R.id.btnFailMenu).setOnClickListener { goToMenu() }

        winOverlay.setOnClickListener { }
        findViewById<View>(R.id.btnWinMenu).setOnClickListener { goToMenu() }
    }

    private fun showExitOverlay() {
        stopTimer()
        exitOverlay.visibility = View.VISIBLE
    }

    private fun hideExitOverlay() {
        exitOverlay.visibility = View.GONE
        if (engine.state == GameState.PLAYING && engine.isGenerated) startTimer()
    }

    private fun showFailOverlay() { failOverlay.visibility = View.VISIBLE }

    private fun showWinOverlay() {
        tvWinTime.text = LeaderboardManager.formatTime(elapsedMs)
        winOverlay.visibility = View.VISIBLE
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimer() {
        if (timerRunning) return
        startTimeMs  = System.currentTimeMillis() - elapsedMs  // resume support
        timerRunning = true
        handler.post(timerRunnable)
    }

    private fun stopTimer() {
        timerRunning = false
        handler.removeCallbacks(timerRunnable)
    }

    // ── GameViewListener ──────────────────────────────────────────────────────

    override fun onCellChanged() {
        if (!timerRunning && engine.isGenerated) startTimer()
        updateMineCount()
    }

    override fun onMineHit() {
        stopTimer()
        gameView.revealAllMines()
        showFailOverlay()
    }

    override fun onWin() {
        stopTimer()
        LeaderboardManager.save(this, nx, ny, nz, nw, elapsedMs)
        showWinOverlay()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateMineCount() {
        tvMineCount.text = engine.remainingMines().toString()
    }

    private fun goToMenu() {
        stopTimer()
        startActivity(
            Intent(this, MenuActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            exitOverlay.visibility == View.VISIBLE -> hideExitOverlay()
            failOverlay.visibility == View.VISIBLE ||
            winOverlay.visibility  == View.VISIBLE -> goToMenu()
            else -> showExitOverlay()
        }
    }
}
