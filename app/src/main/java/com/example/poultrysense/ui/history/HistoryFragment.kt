package com.example.poultrysense.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.poultrysense.R
import com.example.poultrysense.data.CheckupSessionManager
import com.example.poultrysense.data.FarmZoneManager
import com.example.poultrysense.data.models.ScanSession
import com.example.poultrysense.databinding.FragmentHistoryBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var historyAdapter: HistoryAdapter
    private var allSessions: List<ScanSession> = emptyList()
    private var selectedBarnFilter: String = ALL_FILTER
    private val barnChipIds = mutableMapOf<Int, String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        historyAdapter = HistoryAdapter(emptyList())

        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = historyAdapter
        }

        binding.swipeRefreshLayout.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshAndLoad()
            binding.swipeRefreshLayout.postDelayed({
                _binding?.swipeRefreshLayout?.let { it.isRefreshing = false }
            }, 500)
        }

        setupFilterChips()

        binding.btnClearHistory.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            if (allSessions.isEmpty()) {
                android.widget.Toast.makeText(ctx, "No history records to clear", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("Clear Scan History?")
                .setMessage("This will remove all past scan records from your phone and cloud history. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear All") { _, _ ->
                    CheckupSessionManager.clearAllSessions(ctx)
                    loadSessions()
                    android.widget.Toast.makeText(ctx, "Scan history cleared", android.widget.Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        refreshAndLoad()
    }

    private fun setupFilterChips() {
        binding.chipGroupBarnFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedBarnFilter = barnChipIds[id] ?: ALL_FILTER
            applyFilter()
        }
    }

    /**
     * The filter row is generated from real barns in the scan history (plus the
     * farmer's currently configured zones) instead of two hardcoded demo barns.
     */
    private fun rebuildBarnFilters() {
        val group = binding.chipGroupBarnFilter
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        barnChipIds.clear()

        val allChip = createFilterChip("All Barns")
        group.addView(allChip)
        barnChipIds[allChip.id] = ALL_FILTER

        val barnNames = (allSessions.map { it.barnName.orEmpty() } +
            FarmZoneManager.getAllZones(requireContext()).map { it.barnName })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        for (name in barnNames) {
            val chip = createFilterChip(name)
            group.addView(chip)
            barnChipIds[chip.id] = name
        }

        val selectedId = barnChipIds.entries
            .firstOrNull { it.value.equals(selectedBarnFilter, ignoreCase = true) }
            ?.key
            ?: allChip.id
        group.check(selectedId)

        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: allChip.id
            selectedBarnFilter = barnChipIds[id] ?: ALL_FILTER
            applyFilter()
        }
    }

    private fun createFilterChip(label: String): Chip {
        val chip = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_filter_chip, binding.chipGroupBarnFilter, false) as Chip
        chip.id = View.generateViewId()
        chip.text = label
        return chip
    }

    override fun onResume() {
        super.onResume()
        refreshAndLoad()
    }

    private fun refreshAndLoad() {
        loadSessions()
        viewLifecycleOwner.lifecycleScope.launch {
            CheckupSessionManager.refreshFromCloud(requireContext())
            if (_binding != null) loadSessions()
        }
    }

    private fun loadSessions() {
        val ctx = context ?: return
        allSessions = CheckupSessionManager.getCompletedSessions(ctx)
        rebuildBarnFilters()
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = if (selectedBarnFilter == ALL_FILTER) {
            allSessions
        } else {
            allSessions.filter { it.barnName.orEmpty().equals(selectedBarnFilter, ignoreCase = true) }
        }
        historyAdapter.updateData(filtered)

        val isEmpty = filtered.isEmpty()
        binding.tvEmptyHistory.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val ALL_FILTER = "ALL"
    }
}
