package com.example.poultrysense.ui.alerts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.poultrysense.R
import com.example.poultrysense.databinding.FragmentAlertsBinding
import com.example.poultrysense.ui.MainActivity

class AlertsFragment : Fragment() {

    private var _binding: FragmentAlertsBinding? = null
    private val binding get() = _binding!!
    private lateinit var alertsAdapter: AlertsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlertsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
    }

    private fun setupRecyclerView() {
        val mainActivity = activity as? MainActivity
        val alerts = mainActivity?.farmAlerts ?: emptyList()

        alertsAdapter = AlertsAdapter(alerts)
        binding.rvAlerts.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = alertsAdapter
        }

        updateEmptyState()
    }

    private fun setupListeners() {
        val mainActivity = activity as? MainActivity

        binding.btnMarkAllRead.setOnClickListener {
            mainActivity?.markAllAlertsRead()
            alertsAdapter.notifyDataSetChanged()
            Toast.makeText(context, "All alerts marked as read", Toast.LENGTH_SHORT).show()
        }

        binding.swipeRefreshLayout.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshLayout.setOnRefreshListener {
            val alerts = mainActivity?.farmAlerts ?: emptyList()
            alertsAdapter.updateData(alerts)
            updateEmptyState()
            binding.swipeRefreshLayout.postDelayed({
                binding.swipeRefreshLayout.isRefreshing = false
            }, 600)
        }
    }

    private fun updateEmptyState() {
        val mainActivity = activity as? MainActivity
        val hasAlerts = mainActivity?.farmAlerts?.isNotEmpty() == true
        binding.tvEmptyAlerts.visibility = if (hasAlerts) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
