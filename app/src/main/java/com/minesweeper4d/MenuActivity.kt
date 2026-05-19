package com.minesweeper4d

import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        val tabLayout  = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager  = findViewById<ViewPager2>(R.id.viewPager)

        viewPager.adapter = MenuPagerAdapter(this)
        viewPager.isUserInputEnabled = true

        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = if (pos == 0) "게임" else "순위표"
        }.attach()
    }

    // ── Pager adapter ─────────────────────────────────────────────────────────

    private inner class MenuPagerAdapter(activity: AppCompatActivity)
        : FragmentStateAdapter(activity) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment =
            if (position == 0) GameSetupFragment() else LeaderboardFragment()
    }
}
