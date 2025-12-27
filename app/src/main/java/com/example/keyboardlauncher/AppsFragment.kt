package com.example.keyboardlauncher

import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.fragment.app.Fragment
import com.example.keyboardlauncher.databinding.FragmentAppsBinding
import kotlin.math.ceil

class AppsFragment : Fragment() {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!

    private var layoutDone = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val apps = loadApps()
        val n = apps.size

        binding.rootView.setOnApplyWindowInsetsListener { _, insets ->

            if (layoutDone) return@setOnApplyWindowInsetsListener insets
            layoutDone = true

            val systemBars = insets.getInsets(WindowInsets.Type.systemBars())

            val metrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(metrics)

            val usableWidth = metrics.widthPixels
            val usableHeight =
                metrics.heightPixels - systemBars.top - systemBars.bottom

            // -------- OPTIMAL GRID SEARCH --------
            var bestCellSize = 0
            var bestColumns = 1

            for (columns in 1..n) {
                val rows = ceil(n.toDouble() / columns).toInt()
                val cellWidth = usableWidth / columns
                val cellHeight = usableHeight / rows
                val cellSize = minOf(cellWidth, cellHeight)

                if (cellSize > bestCellSize) {
                    bestCellSize = cellSize
                    bestColumns = columns
                }
            }

            binding.appsRecyclerView.layoutManager =
                NoScrollGridLayoutManager(requireContext(), bestColumns)

            binding.appsRecyclerView.adapter =
                AppAdapter(apps, bestCellSize) {
                    startActivity(it.launchIntent)
                }

            insets
        }
    }

    private fun loadApps(): List<AppInfo> {
        val pm = requireContext().packageManager
        val packages = pm.getInstalledApplications(0)

        return packages.mapNotNull { app ->
            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
            if (launchIntent != null) {
                AppInfo(
                    name = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName,
                    icon = pm.getApplicationIcon(app),
                    launchIntent = launchIntent
                )
            } else null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
