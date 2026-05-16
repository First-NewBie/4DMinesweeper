package com.minesweeper4d

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.minesweeper4d.db.LeaderboardManager

class GameActivity : AppCompatActivity(), GameViewListener {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var gameView: GameView
    private lateinit var tvMineCount: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvWinTime: TextView

    private lateinit var btnDig: LinearLayout
    private lateinit var btnFlag: LinearLayout
    private lateinit var btnRange: LinearLayout

    private lateinit var exitOverlay: View
    private lateinit var failOverlay: View
    private lateinit var winOverlay: View

    // ── Game state ────────────────────────────────────────────────────────────
    private lateinit var engine: GameEngine
    private var nx = 3; private var ny = 3; private var nz = 3; private var nw = 3
    private var minePercent = 20

    // ── Timer ─────────────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var startTimeMs = 0L
    private var elapsedMs = 0L
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

        // Read config from intent
        nx = intent.getIntExtra("nx", 3)
        ny = intent.getIntExtra("ny", 3)
        nz = intent.getIntExtra("nz", 3)
        nw = intent.getIntExtra("nw", 3)
        minePercent = intent.getIntExtra("minePercent", 20)

        bindViews()
        setupEngine()
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
        gameView    = findViewById(R.id.gameView)
        tvMineCount = findViewById(R.id.tvMineCount)
        tvTimer     = findViewById(R.id.tvTimer)
        tvWinTime   = findViewById(R.id.tvWinTime)
        btnDig      = findViewById(R.id.btnDig)
        btnFlag     = findViewById(R.id.btnFlag)
        btnRange    = findViewById(R.id.btnRange)
        exitOverlay = findViewById(R.id.exitOverlay)
        failOverlay = findViewById(R.id.failOverlay)
        winOverlay  = findViewById(R.id.winOverlay)

        findViewById<View>(R.id.btnHamburger).setOnClickListener {
            showExitOverlay()
        }
    }

    private fun setupEngine() {
        engine = GameEngine(nx, ny, nz, nw, minePercent)
        gameView.engine = engine
        gameView.listener = this
        gameView.post { gameView.centerGrid() }
        updateMineCount()
    }

    private fun setupToolButtons() {
        btnDig.setOnClickListener {
            gameView.clearHighlight()
            selectTool(Tool.DIG)
        }
        btnFlag.setOnClickListener {
            gameView.clearHighlight()
            selectTool(Tool.FLAG)
        }
        btnRange.setOnClickListener {
            selectTool(Tool.RANGE)
        }
    }

    private fun setupOverlays() {
        // Exit overlay
        exitOverlay.setOnClickListener { /* consume touches */ }
        findViewById<View>(R.id.btnExitYes).setOnClickListener { goToMenu() }
        findViewById<View>(R.id.btnExitNo).setOnClickListener  { hideExitOverlay() }

        // Fail overlay
        failOverlay.setOnClickListener { /* consume */ }
        findViewById<View>(R.id.btnFailMenu).setOnClickListener { goToMenu() }

        // Win overlay
        winOverlay.setOnClickListener { /* consume */ }
        findViewById<View>(R.id.btnWinMenu).setOnClickListener { goToMenu() }
    }

    // ── Tool selection ────────────────────────────────────────────────────────

    private fun selectTool(tool: Tool) {
        gameView.activeTool = tool

        // Reset backgrounds
        val unselected = null  // default transparent
        btnDig.setBackgroundResource(0)
        btnFlag.setBackgroundResource(0)
        btnRange.setBackgroundResource(0)

        // Highlight selected
        when (tool) {
            Tool.DIG   -> btnDig.setBackgroundResource(R.drawable.tool_selected)
            Tool.FLAG  -> btnFlag.setBackgroundResource(R.drawable.tool_selected)
            Tool.RANGE -> btnRange.setBackgroundResource(R.drawable.tool_selected)
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimer() {
        if (timerRunning) return
        startTimeMs = System.currentTimeMillis()
        timerRunning = true
        handler.post(timerRunnable)
    }

    private fun stopTimer() {
        timerRunning = false
        handler.removeCallbacks(timerRunnable)
    }

    // ── GameViewListener ──────────────────────────────────────────────────────

    override fun onCellChanged() {
        // Start timer on first action
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

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateMineCount() {
        tvMineCount.text = engine.remainingMines().toString()
    }

    private fun showExitOverlay() {
        stopTimer()
        exitOverlay.visibility = View.VISIBLE
    }

    private fun hideExitOverlay() {
        exitOverlay.visibility = View.GONE
        // Resume timer only if game still running
        if (engine.state == GameState.PLAYING && engine.isGenerated) startTimer()
    }

    private fun showFailOverlay() {
        failOverlay.visibility = View.VISIBLE
    }

    private fun showWinOverlay() {
        tvWinTime.text = LeaderboardManager.formatTime(elapsedMs)
        winOverlay.visibility = View.VISIBLE
    }

    private fun goToMenu() {
        stopTimer()
        val intent = Intent(this, MenuActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    // ── Back button ───────────────────────────────────────────────────────────

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
