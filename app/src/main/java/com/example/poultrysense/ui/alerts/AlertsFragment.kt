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
import com.example.poultrysense.data.models.AlertSeverity
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.data.models.ScanSession
import com.example.poultrysense.data.models.TierLabels
import com.example.poultrysense.databinding.FragmentAlertsBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch

/**
 * Alerts are scan-based (completed AI checkups) PLUS live AI verdicts pushed by
 * the Python vision engine to Firebase `active_alerts`. Both sources are merged
 * so the farmer sees live flagged birds even before a scan completes.
 */
class AlertsFragment : Fragment() {

    private var _binding: FragmentAlertsBinding? = null
    private val binding get() = _binding!!
    private lateinit var alertsAdapter: AlertGroupAdapter

    private var liveAlertListener: ValueEventListener? = null
    private val liveAlerts = mutableListOf<FarmAlert>()

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
        alertsAdapter = AlertGroupAdapter(emptyList()) { alertToResolve ->
            val cid = alertToResolve.chickenId
            if (!cid.isNullOrBlank()) {
                try {
                    FirebaseConfig.database().getReference("active_alerts")
                        .child("chicken_$cid")
                        .removeValue()
                } catch (_: Exception) {}
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
            }, 500)
        }
        attachLiveAlertsListener()
        refreshAndRender()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refreshAndRender()
    }

    private fun attachLiveAlertsListener() {
        try {
            val ref = FirebaseConfig.database().getReference("active_alerts")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val parsed = mutableListOf<FarmAlert>()
                    for (child in snapshot.children) {
                        val verdict = child.value as? Map<*, *> ?: continue
                        val cid = (verdict["chicken_id"] as? Number)?.toInt()
                            ?: verdict["chicken_id"]?.toString()?.toIntOrNull()
                            ?: child.key?.substringAfter("chicken_")?.toIntOrNull()
                            ?: continue
                        val tier = verdict["anomaly_tier"]?.toString()
                            ?: verdict["tier"]?.toString().orEmpty()
                        val severity = verdict["severity"]?.toString().orEmpty()
                        val summary = verdict["gemini_summary"]?.toString()
                            ?: verdict["reason"]?.toString().orEmpty()
                        val reason = verdict["reason"]?.toString()
                            ?: summary
                        val score = (verdict["anomaly_score"] as? Number)?.toInt()
                            ?: verdict["anomaly_score"]?.toString()?.toIntOrNull()
                            ?: 0
                        val behavior = verdict["behavior"]?.toString().orEmpty()
                        val timestamp = verdict["timestamp"]?.toString().orEmpty()
                        val tag = ChickenTagHelper.getTagInfo(cid)
                        val isNormal = tier.equals("Normal", ignoreCase = true) ||
                            tier.equals("Healthy", ignoreCase = true) ||
                            severity.equals("Low", ignoreCase = true)

                        // Cleared / Normal birds are not active alerts requiring intervention
                        if (isNormal) {
                            continue
                        }

                        val critical = severity.equals("Critical", ignoreCase = true) ||
                            tier.equals("High Concern", ignoreCase = true) ||
                            score >= 70
                        val alertSeverity = when {
                            critical -> AlertSeverity.CRITICAL
                            tier.equals("Suspicious", ignoreCase = true) ||
                                severity.equals("High", ignoreCase = true) ||
                                severity.equals("Medium", ignoreCase = true) ||
                                score >= 40 -> AlertSeverity.WARNING
                            else -> AlertSeverity.INFO
                        }
                        val description = buildString {
                            if (behavior.isNotBlank()) append("${behavior.replaceFirstChar { it.uppercase() }} • ")
                            append("Anomaly score $score/100")
                            if (summary.isNotBlank()) append("\n$summary")
                            else if (reason.isNotBlank()) append("\n$reason")
                        }
                        parsed.add(
                            FarmAlert(
                                id = "live-${child.key}",
                                severity = alertSeverity,
                                title = "${tag.displayLabel} • ${TierLabels.forTier(tier.ifBlank { "Needs Attention" })}",
                                description = description,
                                timeAgo = "Live",
                                timestamp = timestamp,
                                read = false,
                                isResolved = false,
                                chickenId = cid.toString(),
                                location = null,
                                tagColorName = tag.colorName,
                                tagColorHex = tag.colorHex,
                                tagNumber = tag.tagNumber,
                                isHistorical = false,
                                scanId = null
                            )
                        )
                    }
                    liveAlerts.clear()
                    liveAlerts.addAll(parsed)
                    if (_binding != null) render()
                }

                override fun onCancelled(error: DatabaseError) { /* ignore */ }
            }
            ref.addValueEventListener(listener)
            liveAlertListener = listener
        } catch (_: Exception) { }
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
            .toMutableList()

        // Prepend a live AI alerts group when the vision engine has flagged birds.
        if (liveAlerts.isNotEmpty()) {
            val liveGroup = AlertGroup(
                id = "live-ai-alerts",
                title = "Live AI Alerts",
                subtitle = "Flagged by the vision engine (not yet in a completed scan)",
                countLabel = "${liveAlerts.size} flagged",
                stats = null,
                alerts = liveAlerts.toList(),
                emptyMessage = "No live alerts.",
                accentColor = Color.parseColor("#E53935"),
                isScan = false
            )
            scanGroups.add(0, liveGroup)
        }

        binding.tvAlertSummary.text = when {
            scanGroups.isEmpty() -> "No flagged birds in completed scans"
            scanGroups.size == 1 && liveAlerts.isEmpty() -> "1 scan with flagged birds"
            else -> "${scanGroups.size} alert group(s)"
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
        val isNormal = tier.equals("Normal", ignoreCase = true) || tier.equals("Healthy", ignoreCase = true)
        val critical = !isNormal && (tier.equals("High Concern", ignoreCase = true) || detail.anomalyScore >= 70)
        val alertSeverity = when {
            isNormal -> AlertSeverity.INFO
            critical -> AlertSeverity.CRITICAL
            else -> AlertSeverity.WARNING
        }
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
            severity = alertSeverity,
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
        liveAlertListener?.let {
            try { FirebaseConfig.database().getReference("active_alerts").removeEventListener(it) } catch (_: Exception) { }
        }
        liveAlertListener = null
        _binding = null
    }
}