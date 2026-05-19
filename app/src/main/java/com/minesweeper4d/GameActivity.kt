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
    private lateinit var gameView:      GameView
    private lateinit var tvMineCount:   TextView
    private lateinit var tvTimer:       TextView
    private lateinit var tvWinTime:     TextView
    private lateinit var tvLayerAxis:   TextView
    private lateinit var tvLayerNum:    TextView
    private lateinit var labelDimTop:   TextView

    private lateinit var btnDig:   LinearLayout
    private lateinit var btnFlag:  LinearLayout
    private lateinit var btnRange: LinearLayout
    private lateinit var btnMove:  LinearLayout

    private lateinit var seekDimension: SeekBar
    private lateinit var seekLayer:     SeekBar
    private lateinit var rightPanel:    View

    private lateinit var exitOverlay: View
    private lateinit var failOverlay: View
    private lateinit var winOverlay:  View

    // ── Config ────────────────────────────────────────────────────────────────
    private var nx = 3; private var ny = 3
    private var nz = 3; private var nw = 3
    private var minePercent = 20

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
        setupDimSlider()
        setupLayerSlider()
        setupTools()
        setupOverlays()
        selectTool(Tool.DIG)
    }

    override fun onDestroy() { super.onDestroy(); stopTimer() }

    // ── Bind ──────────────────────────────────────────────────────────────────

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
        btnMove       = findViewById(R.id.btnMove)
        seekDimension = findViewById(R.id.seekDimension)
        seekLayer     = findViewById(R.id.seekLayer)
        rightPanel    = findViewById(R.id.rightPanel)
        exitOverlay   = findViewById(R.id.exitOverlay)
        failOverlay   = findViewById(R.id.failOverlay)
        winOverlay    = findViewById(R.id.winOverlay)

        findViewById<View>(R.id.btnHamburger).setOnClickListener { showExitOverlay() }
    }

    private fun setupEngine() {
        engine            = GameEngine(nx, ny, nz, nw, minePercent)
        gameView.engine   = engine
        gameView.listener = this
        // Default view: 3D if nw==1, else 4D
        gameView.viewMode = if (nw > 1) ViewMode.MODE_4D else ViewMode.MODE_3D
        gameView.post { gameView.centerGrid() }
        updateMineCount()
    }

    // ── Dimension slider ──────────────────────────────────────────────────────

    private fun setupDimSlider() {
        val maxDim = if (nw > 1) 2 else 1
        seekDimension.max      = maxDim
        seekDimension.progress = 0
        labelDimTop.text       = if (nw > 1) "4D" else "3D"
        applyDimProgress(0)

        seekDimension.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) applyDimProgress(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun applyDimProgress(progress: Int) {
        val newMode = if (nw > 1) {
            when (progress) { 0 -> ViewMode.MODE_4D; 1 -> ViewMode.MODE_3D; else -> ViewMode.MODE_2D }
        } else {
            when (progress) { 0 -> ViewMode.MODE_3D; else -> ViewMode.MODE_2D }
        }
        gameView.viewMode = newMode
        updateLayerPanel(newMode)
    }

    // ── Layer slider ──────────────────────────────────────────────────────────

    private fun setupLayerSlider() {
        seekLayer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (gameView.viewMode) {
                    ViewMode.MODE_3D -> { gameView.changeLayer(newW = p); tvLayerNum.text = p.toString() }
                    ViewMode.MODE_2D -> { gameView.changeLayer(newZ = p); tvLayerNum.text = p.toString() }
                    ViewMode.MODE_4D -> { gameView.changeLayer(newW = p); tvLayerNum.text = p.toString() }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        updateLayerPanel(gameView.viewMode)
    }

    private fun updateLayerPanel(mode: ViewMode) {
        when (mode) {
            ViewMode.MODE_4D -> {
                rightPanel.visibility = View.VISIBLE
                seekLayer.max = (nw - 1).coerceAtLeast(0)
                tvLayerAxis.text = "w"
                tvLayerNum.text  = gameView.wSlice.toString()
                seekLayer.progress = gameView.wSlice
            }
            ViewMode.MODE_3D -> {
                // In 3D mode with nw>1, slider navigates w (w-slice for the visible cube)
                rightPanel.visibility = if (nw > 1) View.VISIBLE else View.INVISIBLE
                seekLayer.max = (nw - 1).coerceAtLeast(0)
                tvLayerAxis.text = "w"
                tvLayerNum.text  = gameView.wSlice.toString()
                seekLayer.progress = gameView.wSlice
            }
            ViewMode.MODE_2D -> {
                rightPanel.visibility = View.VISIBLE
                seekLayer.max = (nz - 1).coerceAtLeast(0)
                tvLayerAxis.text = "z"
                tvLayerNum.text  = gameView.zSlice.toString()
                seekLayer.progress = gameView.zSlice
            }
        }
    }

    // ── Tool buttons ──────────────────────────────────────────────────────────

    private fun setupTools() {
        btnDig.setOnClickListener   { gameView.clearHighlight(); selectTool(Tool.DIG)   }
        btnFlag.setOnClickListener  { gameView.clearHighlight(); selectTool(Tool.FLAG)  }
        btnRange.setOnClickListener { selectTool(Tool.RANGE) }
        btnMove.setOnClickListener  { gameView.clearHighlight(); selectTool(Tool.MOVE)  }
    }

    private fun selectTool(tool: Tool) {
        gameView.activeTool = tool
        listOf(btnDig, btnFlag, btnRange, btnMove).forEach { it.setBackgroundResource(0) }
        when (tool) {
            Tool.DIG   -> btnDig.setBackgroundResource(R.drawable.tool_selected)
            Tool.FLAG  -> btnFlag.setBackgroundResource(R.drawable.tool_selected)
            Tool.RANGE -> btnRange.setBackgroundResource(R.drawable.tool_selected)
            Tool.MOVE  -> btnMove.setBackgroundResource(R.drawable.tool_selected)
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
        findViewById<View>(R.id.btnWinMenu).setOnClickListener  { goToMenu() }
    }

    private fun showExitOverlay() { stopTimer(); exitOverlay.visibility = View.VISIBLE }
    private fun hideExitOverlay() {
        exitOverlay.visibility = View.GONE
        if (engine.state == GameState.PLAYING && engine.isGenerated) startTimer()
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimer() {
        if (timerRunning) return
        startTimeMs  = System.currentTimeMillis() - elapsedMs
        timerRunning = true; handler.post(timerRunnable)
    }
    private fun stopTimer() { timerRunning = false; handler.removeCallbacks(timerRunnable) }

    // ── GameViewListener ──────────────────────────────────────────────────────

    override fun onCellChanged() {
        if (!timerRunning && engine.isGenerated) startTimer()
        updateMineCount()
    }
    override fun onMineHit() { stopTimer(); gameView.revealAllMines(); failOverlay.visibility = View.VISIBLE }
    override fun onWin()     {
        stopTimer()
        LeaderboardManager.save(this, nx, ny, nz, nw, elapsedMs)
        tvWinTime.text = LeaderboardManager.formatTime(elapsedMs)
        winOverlay.visibility = View.VISIBLE
    }

    private fun updateMineCount() { tvMineCount.text = engine.remainingMines().toString() }

    private fun goToMenu() {
        stopTimer()
        startActivity(Intent(this, MenuActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
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
