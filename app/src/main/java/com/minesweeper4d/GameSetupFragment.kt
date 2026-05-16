package com.minesweeper4d

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment

class GameSetupFragment : Fragment() {

    // ── Current settings ──────────────────────────────────────────────────────
    private var nx = 3; private var ny = 3; private var nz = 3; private var nw = 3
    private var minePercent = 20   // 5–85

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvNx: TextView
    private lateinit var tvNy: TextView
    private lateinit var tvNz: TextView
    private lateinit var tvNw: TextView
    private lateinit var tvMinePercent: TextView
    private lateinit var tvGridInfo: TextView
    private lateinit var seekMine: SeekBar

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_game_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind
        tvNx         = view.findViewById(R.id.tvNx)
        tvNy         = view.findViewById(R.id.tvNy)
        tvNz         = view.findViewById(R.id.tvNz)
        tvNw         = view.findViewById(R.id.tvNw)
        tvMinePercent= view.findViewById(R.id.tvMinePercent)
        tvGridInfo   = view.findViewById(R.id.tvGridInfo)
        seekMine     = view.findViewById(R.id.seekMinePercent)

        // SeekBar range: 0–80 → maps to 5–85%
        seekMine.progress = minePercent - 5
        seekMine.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                minePercent = p + 5
                updateMineLabel()
                updateGridInfo()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Dimension buttons
        setupDimButtons(view, R.id.btnXMinus, R.id.btnXPlus, ::nx) { nx = it; tvNx.text = it.toString() }
        setupDimButtons(view, R.id.btnYMinus, R.id.btnYPlus, ::ny) { ny = it; tvNy.text = it.toString() }
        setupDimButtons(view, R.id.btnZMinus, R.id.btnZPlus, ::nz) { nz = it; tvNz.text = it.toString() }
        setupDimButtons(view, R.id.btnWMinus, R.id.btnWPlus, ::nw) { nw = it; tvNw.text = it.toString() }

        // Start
        view.findViewById<View>(R.id.btnStart).setOnClickListener { startGame() }

        // Initial display
        refreshAllLabels()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setupDimButtons(
        view: View,
        minusId: Int, plusId: Int,
        getter: () -> Int,
        setter: (Int) -> Unit
    ) {
        view.findViewById<ImageButton>(minusId).setOnClickListener {
            val v = (getter() - 1).coerceAtLeast(2)
            setter(v)
            updateGridInfo()
        }
        view.findViewById<ImageButton>(plusId).setOnClickListener {
            val v = (getter() + 1).coerceAtMost(8)
            setter(v)
            updateGridInfo()
        }
    }

    private fun refreshAllLabels() {
        tvNx.text = nx.toString()
        tvNy.text = ny.toString()
        tvNz.text = nz.toString()
        tvNw.text = nw.toString()
        updateMineLabel()
        updateGridInfo()
    }

    private fun updateMineLabel() {
        tvMinePercent.text = "$minePercent%"
    }

    private fun updateGridInfo() {
        val total = nx * ny * nz * nw
        val mines = (total * minePercent / 100).coerceAtLeast(1)
        tvGridInfo.text = "전체 ${total}칸 · 지뢰 약 ${mines}개"
    }

    private fun startGame() {
        val intent = Intent(requireContext(), GameActivity::class.java).apply {
            putExtra("nx", nx)
            putExtra("ny", ny)
            putExtra("nz", nz)
            putExtra("nw", nw)
            putExtra("minePercent", minePercent)
        }
        startActivity(intent)
    }
}
