package com.minesweeper4d.db

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LeaderboardEntry(
    val elapsedMs: Long,   // milliseconds
    val dateStr: String,   // formatted display date
    val timestamp: Long    // for sorting
)

object LeaderboardManager {

    private const val PREFS_NAME = "leaderboard_prefs"
    private const val MAX_ENTRIES = 50
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yy.MM.dd HH:mm", Locale.getDefault())

    /** Key format: "lb_NxNyNzNw"  e.g. "lb_4x4x4x4" */
    private fun key(nx: Int, ny: Int, nz: Int, nw: Int) = "lb_${nx}x${ny}x${nz}x${nw}"

    fun save(context: Context, nx: Int, ny: Int, nz: Int, nw: Int, elapsedMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val k = key(nx, ny, nz, nw)
        val existing = load(context, nx, ny, nz, nw).toMutableList()

        existing.add(
            LeaderboardEntry(
                elapsedMs = elapsedMs,
                dateStr = dateFormat.format(Date()),
                timestamp = System.currentTimeMillis()
            )
        )
        existing.sortBy { it.elapsedMs }
        val trimmed = existing.take(MAX_ENTRIES)

        prefs.edit().putString(k, gson.toJson(trimmed)).apply()
    }

    fun load(context: Context, nx: Int, ny: Int, nz: Int, nw: Int): List<LeaderboardEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(key(nx, ny, nz, nw), null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<LeaderboardEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Returns all dimension keys that have at least one entry. */
    fun allKeys(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.keys.filter { it.startsWith("lb_") }.sorted()
    }

    /** Human-readable label from key, e.g. "lb_4x4x4x4" → "4×4×4×4" */
    fun labelFromKey(k: String): String =
        k.removePrefix("lb_").replace("x", "×")

    fun formatTime(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val h = m / 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m % 60, s % 60)
        else String.format("%d:%02d", m, s % 60)
    }
}
