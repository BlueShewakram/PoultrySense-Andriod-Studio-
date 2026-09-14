package com.example.poultrysense.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.poultrysense.R
import com.example.poultrysense.data.CheckupSessionManager
import com.example.poultrysense.databinding.FragmentHistoryBinding

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var historyAdapter: HistoryAdapter

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

        val sessions = CheckupSessionManager.getCompletedSessions(requireContext())
        historyAdapter = HistoryAdapter(sessions)

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
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
    }

    private fun loadSessions() {
        val ctx = context ?: return
        val sessions = CheckupSessionManager.getCompletedSessions(ctx)
        historyAdapter.updateData(sessions)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
