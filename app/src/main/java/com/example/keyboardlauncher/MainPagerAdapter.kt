package com.example.keyboardlauncher

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: AppCompatActivity)
    : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> FocusFragment()
            1 -> AppsFragment()
            2 -> NotesFragment()
            else -> AppsFragment()
        }
    }
}