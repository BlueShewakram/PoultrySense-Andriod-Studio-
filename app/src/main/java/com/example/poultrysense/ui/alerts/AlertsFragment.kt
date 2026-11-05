package com.example.poultrysense.ui.alerts

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.poultrysense.R
import com.example.poultrysense.data.CheckupSessionManager
import com.example.poultrysense.data.ChickenTagHelper
import com.example.poultrysense.data.models.AlertSeverity
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.data.models.ScanSession
import com.example.poultrysense.data.models.TierLabels
import com.example.poultrysense.databinding.FragmentAlertsBinding
import kotlinx.coroutines.launch

/**
 * Alerts are strictly scan-based: every card is a flagged chicken recorded in a
 * completed AI checkup. Live camera/climate notifications are intentionally not
 * shown here.
 */
class AlertsFragment : Fragment() {

    private var _binding: FragmentAlertsBinding? = null
    private val binding get() = _binding!!
    private lateinit var alertsAdapter: AlertGroupAdapter

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
        alertsAdapter = AlertGroupAdapter(emptyList(), null)
        binding.rvAlerts.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = alertsAdapter
        }
        binding.swipeRefreshLayout.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshAndRender()
            binding.swipeRefreshLayout.postDelayed({
                _binding?.swipeRefreshLayout?.let { it.isRefreshing = false }
            }, 500)
        }
        refreshAndRender()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refreshAndRender()
    }

    private fun refreshAndRender() {
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            CheckupSessionManager.refreshFromCloud(requireContext())
            if (_binding != null) render()
        }
    }

    private fun render() {
        val ctx = context ?: return
        val scanGroups = CheckupSessionManager.getCompletedSessions(ctx)
            .mapNotNull { buildScanGroup(it) }

        binding.tvAlertSummary.text = when {
            scanGroups.isEmpty() -> "No flagged birds in completed scans"
            scanGroups.size == 1 -> "1 scan with flagged birds"
            else -> "${scanGroups.size} scans with flagged birds"
        }

        val hasContent = scanGroups.isNotEmpty()
        binding.rvAlerts.visibility = if (hasContent) View.VISIBLE else View.GONE
        binding.tvEmptyAlerts.visibility = if (hasContent) View.GONE else View.VISIBLE
        binding.tvEmptyAlertsTitle.text = "No Scan Alerts"
        binding.tvEmptyAlertsSubtitle.text = "No completed scan has flagged birds yet"

        alertsAdapter.updateData(scanGroups)
    }

    private fun buildScanGroup(session: ScanSession): AlertGroup? {
        val flaggedDetails = session.safeChickenDetails.filter {
            val tier = it.anomalyTier.orEmpty()
            (tier.isNotBlank() && tier != "Normal") || it.anomalyScore >= 40
        }

        val alerts = if (flaggedDetails.isNotEmpty()) {
            flaggedDetails.map { detail -> buildScanChickenAlert(session, detail) }
        } else if (session.illCount > 0) {
            listOf(
                FarmAlert(
                    id = "scan-${session.id}-summary",
                    severity = AlertSeverity.CRITICAL,
                    title = "${session.illCount} bird(s) flagged",
                    description = "This scan recorded flagged birds but not per-bird details. Open History to review the scan totals.",
                    timeAgo = session.time,
                    timestamp = session.loggedAt,
                    read = true,
                    isResolved = true,
                    location = session.barnDisplayName,
                    isHistorical = true,
                    scanId = session.id
                )
            )
        } else {
            return null
        }

        return AlertGroup(
            id = "scan-${session.id}",
            title = session.sessionNumber,
            subtitle = "${session.loggedAt} • ${session.barnDisplayName}",
            countLabel = "${session.illCount} flagged",
            stats = "${session.totalFlock} scanned • ${session.healthyCount} healthy • ${session.illCount} flagged • Health ${session.healthIndex}",
            alerts = alerts,
            emptyMessage = "No flagged birds in this scan.",
            accentColor = Color.parseColor("#1976D2"),
            isScan = true
        )
    }

    private fun buildScanChickenAlert(
        session: ScanSession,
        detail: com.example.poultrysense.data.models.ChickenSessionDetail
    ): FarmAlert {
        val tag = ChickenTagHelper.getTagInfo(detail.chickenId)
        val tier = detail.anomalyTier?.ifBlank { "Needs Attention" } ?: "Needs Attention"
        val critical = tier.equals("High Concern", ignoreCase = true) || detail.anomalyScore >= 70
        val behavior = (detail.behavior?.ifBlank { "Abnormal behavior" } ?: "Abnormal behavior")
            .replaceFirstChar { it.uppercase() }
        val description = buildString {
            append("${behavior} • Anomaly score ${detail.anomalyScore}/100")
            detail.geminiSummary?.takeIf { it.isNotBlank() }?.let {
                append("\n${it}")
            }
        }

        return FarmAlert(
            id = "scan-${session.id}-chicken-${detail.chickenId}",
            severity = if (critical) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
            title = "${tag.displayLabel} • ${TierLabels.forTier(tier)}",
            description = description,
            timeAgo = session.time,
            timestamp = session.loggedAt,
            read = true,
            isResolved = true,
            chickenId = detail.chickenId.toString(),
            location = session.barnDisplayName,
            tagColorName = tag.colorName,
            tagColorHex = tag.colorHex,
            tagNumber = tag.tagNumber,
            isHistorical = true,
            scanId = session.id
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
