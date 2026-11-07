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
import com.example.poultrysense.data.FirebaseConfig
import com.example.poultrysense.data.ServerPreferences
import com.example.poultrysense.data.models.AlertSeverity
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.data.models.ScanSession
import com.example.poultrysense.databinding.FragmentAlertsBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Unified Alert Center: Displays both active live Gemini health alerts
 * and flagged birds recorded in completed AI checkup scans.
 */
class AlertsFragment : Fragment() {

    private var _binding: FragmentAlertsBinding? = null
    private val binding get() = _binding!!
    private lateinit var alertsAdapter: AlertGroupAdapter
    private val activeLiveAlerts = mutableListOf<FarmAlert>()

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
        alertsAdapter = AlertGroupAdapter(emptyList()) { alert ->
            // One-tap resolve action for active alerts
            if (!alert.isHistorical && !alert.chickenId.isNullOrBlank()) {
                try {
                    FirebaseConfig.database().getReference("active_alerts")
                        .child("chicken_${alert.chickenId}")
                        .removeValue()
                } catch (_: Exception) {}
                activeLiveAlerts.removeAll { it.id == alert.id }
                render()
            }
        }

        binding.rvAlerts.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = alertsAdapter
        }

        binding.swipeRefreshLayout.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshAndRender()
            binding.swipeRefreshLayout.postDelayed({
                _binding?.swipeRefreshLayout?.let { it.isRefreshing = false }
            }, 600)
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
            val live = fetchActiveAlertsFromCloud()
            activeLiveAlerts.clear()
            activeLiveAlerts.addAll(live)
            CheckupSessionManager.refreshFromCloud(requireContext())
            if (_binding != null) render()
        }
    }

    private fun render() {
        val ctx = context ?: return
        val scanGroups = CheckupSessionManager.getCompletedSessions(ctx)
            .mapNotNull { buildScanGroup(it) }

        val allGroups = mutableListOf<AlertGroup>()

        if (activeLiveAlerts.isNotEmpty()) {
            allGroups.add(
                AlertGroup(
                    id = "live-alerts-group",
                    title = "Live AI Health Alerts",
                    subtitle = "Active multimodal vision & sensor telemetry flags",
                    countLabel = "${activeLiveAlerts.size} active",
                    stats = null,
                    alerts = activeLiveAlerts,
                    emptyMessage = "No active health alerts.",
                    accentColor = Color.parseColor("#E53935"),
                    isScan = false
                )
            )
        }

        allGroups.addAll(scanGroups)

        val totalAlertCount = activeLiveAlerts.size + scanGroups.sumOf { it.alerts.size }
        binding.tvAlertSummary.text = when {
            totalAlertCount == 0 -> "All flock birds healthy • No active alerts"
            totalAlertCount == 1 -> "1 bird alert requiring attention"
            else -> "$totalAlertCount bird alerts requiring attention"
        }

        val hasContent = allGroups.isNotEmpty()
        binding.rvAlerts.visibility = if (hasContent) View.VISIBLE else View.GONE
        binding.tvEmptyAlerts.visibility = if (hasContent) View.GONE else View.VISIBLE
        binding.tvEmptyAlertsTitle.text = "No Alerts"
        binding.tvEmptyAlertsSubtitle.text = "All chickens healthy • No anomalies detected"

        alertsAdapter.updateData(allGroups)
    }

    private suspend fun fetchActiveAlertsFromCloud(): List<FarmAlert> {
        return try {
            withTimeoutOrNull(4_000L) {
                suspendCancellableCoroutine<List<FarmAlert>> { cont ->
                    val ref = FirebaseConfig.database().getReference("active_alerts")
                    val listener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val list = mutableListOf<FarmAlert>()
                            for (child in snapshot.children) {
                                try {
                                    val cid = (child.child("chicken_id").value as? Number)?.toInt()
                                        ?: child.key?.removePrefix("chicken_")?.toIntOrNull()
                                        ?: 0
                                    if (cid <= 0) continue
                                    val tag = ChickenTagHelper.getTagInfo(cid)
                                    val sevStr = child.child("severity").value as? String ?: "High"
                                    val isCrit = sevStr.equals("Critical", ignoreCase = true) || sevStr.equals("High", ignoreCase = true)
                                    val reason = child.child("reason").value as? String ?: ""
                                    val action = child.child("farmer_action").value as? String
                                        ?: child.child("recommendation").value as? String ?: ""
                                    val tier = child.child("anomaly_tier").value as? String ?: "High Concern"
                                    val time = child.child("timestamp").value as? String ?: "Just now"
                                    val climate = child.child("climate").value as? String ?: ""

                                    val desc = buildString {
                                        if (reason.isNotBlank()) append(reason)
                                        if (action.isNotBlank()) {
                                            if (isNotEmpty()) append("\n\nAction: ")
                                            append(action)
                                        }
                                        if (climate.isNotBlank()) {
                                            if (isNotEmpty()) append("\nClimate: ")
                                            append(climate)
                                        }
                                    }

                                    list.add(
                                        FarmAlert(
                                            id = "active-chicken-$cid",
                                            severity = if (isCrit) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
                                            title = "${tag.displayLabel} • $tier",
                                            description = desc.ifBlank { "Health anomaly flagged by Gemini AI" },
                                            timeAgo = "Live",
                                            timestamp = time,
                                            read = false,
                                            isResolved = false,
                                            chickenId = cid.toString(),
                                            location = "Live Camera Feed",
                                            tagColorName = tag.colorName,
                                            tagColorHex = tag.colorHex,
                                            tagNumber = tag.tagNumber,
                                            isHistorical = false
                                        )
                                    )
                                } catch (_: Exception) {}
                            }
                            if (cont.isActive) cont.resume(list)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    }
                    ref.addListenerForSingleValueEvent(listener)
                    cont.invokeOnCancellation { ref.removeEventListener(listener) }
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
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
                    description = "This scan recorded flagged birds. Open History to review the full chicken breakdown.",
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
            title = "${tag.displayLabel} • ${tier}",
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
