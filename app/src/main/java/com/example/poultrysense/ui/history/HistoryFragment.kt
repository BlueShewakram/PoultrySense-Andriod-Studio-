package com.example.poultrysense.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.poultrysense.R
import com.example.poultrysense.data.models.ScanSession
import com.example.poultrysense.databinding.FragmentHistoryBinding

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val sessionHistory = listOf(
        ScanSession(
            id = "session-108",
            sessionNumber = "Scan #108",
            date = "Aug 9, 2026",
            time = "10:30 AM",
            status = "Completed",
            totalFlock = 350,
            healthyCount = 342,
            illCount = 8,
            healthIndex = "97.7%"
        ),
        ScanSession(
            id = "session-107",
            sessionNumber = "Scan #107",
            date = "Aug 8, 2026",
            time = "05:00 PM",
            status = "Completed",
            totalFlock = 350,
            healthyCount = 345,
            illCount = 5,
            healthIndex = "98.5%"
        ),
        ScanSession(
            id = "session-106",
            sessionNumber = "Scan #106",
            date = "Aug 8, 2026",
            time = "09:00 AM",
            status = "Completed",
            totalFlock = 350,
            healthyCount = 348,
            illCount = 2,
            healthIndex = "99.4%"
        )
    )

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

        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = HistoryAdapter(sessionHistory)
        }

        binding.swipeRefreshLayout.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.swipeRefreshLayout.postDelayed({
                binding.swipeRefreshLayout.isRefreshing = false
            }, 600)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
