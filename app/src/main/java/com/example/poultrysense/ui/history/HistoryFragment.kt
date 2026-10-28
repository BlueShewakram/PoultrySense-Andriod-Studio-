package com.example.poultrysense.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.poultrysense.R
import com.example.poultrysense.data.CheckupSessionManager
import com.example.poultrysense.data.models.ScanSession
import com.example.poultrysense.databinding.FragmentHistoryBinding

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var historyAdapter: HistoryAdapter
    private var allSessions: List<ScanSession> = emptyList()
    private var selectedBarnFilter: String = "ALL"

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
            loadSessions()
            binding.swipeRefreshLayout.postDelayed({
                _binding?.swipeRefreshLayout?.let { it.isRefreshing = false }
            }, 500)
        }

        setupFilterChips()
        loadSessions()
    }

    private fun setupFilterChips() {
        binding.chipGroupBarnFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedBarnFilter = when {
                checkedIds.contains(R.id.chipFilterBarnAlpha) -> "Barn Alpha"
                checkedIds.contains(R.id.chipFilterBarnBeta) -> "Barn Beta"
                else -> "ALL"
            }
            applyFilter()
        }
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
    }

    private fun loadSessions() {
        val ctx = context ?: return
        allSessions = CheckupSessionManager.getCompletedSessions(ctx)
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = if (selectedBarnFilter == "ALL") {
            allSessions
        } else {
            allSessions.filter { it.barnName.contains(selectedBarnFilter, ignoreCase = true) }
        }
        historyAdapter.updateData(filtered)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
