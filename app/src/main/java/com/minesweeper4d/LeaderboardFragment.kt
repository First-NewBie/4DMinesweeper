package com.minesweeper4d

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minesweeper4d.db.LeaderboardEntry
import com.minesweeper4d.db.LeaderboardManager

class LeaderboardFragment : Fragment() {

    private lateinit var spinner: Spinner
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: LeaderboardAdapter

    private var keys: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_leaderboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        spinner = view.findViewById(R.id.spinnerSize)
        rv      = view.findViewById(R.id.rvLeaderboard)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        adapter = LeaderboardAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rv.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
    }

    override fun onResume() {
        super.onResume()
        loadKeys()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadKeys() {
        keys = LeaderboardManager.allKeys(requireContext())

        if (keys.isEmpty()) {
            spinner.visibility = View.GONE
            rv.visibility      = View.GONE
            tvEmpty.visibility = View.VISIBLE
            return
        }

        spinner.visibility = View.VISIBLE
        rv.visibility      = View.VISIBLE
        tvEmpty.visibility = View.GONE

        val labels = keys.map { LeaderboardManager.labelFromKey(it) }
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinner.adapter = spinnerAdapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                loadEntries(keys[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Show first key by default
        loadEntries(keys[0])
    }

    private fun loadEntries(key: String) {
        // Parse key "lb_NxNyNzNw"
        val parts = key.removePrefix("lb_").split("x")
        if (parts.size != 4) return
        val (nx, ny, nz, nw) = parts.map { it.toIntOrNull() ?: 0 }
        val entries = LeaderboardManager.load(requireContext(), nx, ny, nz, nw)
        adapter.setData(entries)

        if (entries.isEmpty()) {
            rv.visibility      = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            rv.visibility      = View.VISIBLE
            tvEmpty.visibility = View.GONE
        }
    }
}

// ─── RecyclerView Adapter ─────────────────────────────────────────────────────

class LeaderboardAdapter : RecyclerView.Adapter<LeaderboardAdapter.VH>() {

    private val items = mutableListOf<LeaderboardEntry>()

    fun setData(data: List<LeaderboardEntry>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_leaderboard, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(position + 1, items[position])
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvRank = v.findViewById<TextView>(R.id.tvRank)
        private val tvTime = v.findViewById<TextView>(R.id.tvTime)
        private val tvDate = v.findViewById<TextView>(R.id.tvDate)

        fun bind(rank: Int, entry: LeaderboardEntry) {
            tvRank.text = rank.toString()
            tvTime.text = LeaderboardManager.formatTime(entry.elapsedMs)
            tvDate.text = entry.dateStr

            // Top 3 rank colours
            tvRank.setTextColor(
                when (rank) {
                    1    -> 0xFFFFD700.toInt()  // gold
                    2    -> 0xFFC0C0C0.toInt()  // silver
                    3    -> 0xFFCD7F32.toInt()  // bronze
                    else -> 0xFF4FC3F7.toInt()  // accent
                }
            )
        }
    }
}
