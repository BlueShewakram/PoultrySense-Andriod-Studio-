package com.example.poultrysense.ui.alerts

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import com.example.poultrysense.R
import com.example.poultrysense.data.ChickenTagHelper
import com.example.poultrysense.data.models.AlertSeverity
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.databinding.ItemAlertBinding

/** Shared renderer used by both the flat adapter and grouped scan cards. */
object AlertCardBinder {

    fun bind(
        binding: ItemAlertBinding,
        alert: FarmAlert,
        onResolveClick: ((FarmAlert) -> Unit)? = null
    ) {
        val context = binding.root.context
        with(binding) {
            tvAlertTitle.text = alert.title
            tvAlertDescription.text = alert.description
            tvAlertTime.text = alert.timeAgo
            if (alert.timestamp.isBlank()) {
                tvAlertTimestamp.visibility = View.GONE
            } else {
                tvAlertTimestamp.visibility = View.VISIBLE
                tvAlertTimestamp.text = alert.timestamp
            }

            if (alert.location != null) {
                layoutAlertLocation.visibility = View.VISIBLE
                tvAlertLocation.text = alert.location
            } else {
                layoutAlertLocation.visibility = View.GONE
            }

            // Physical chicken tag band (identity, not severity)
            val tagInfo = ChickenTagHelper.resolveTag(alert.chickenId, alert.title, alert.description)
            if (tagInfo != null) {
                layoutChickenTag.visibility = View.VISIBLE
                tvChickenTagText.text = "${tagInfo.colorName} TAG #${tagInfo.tagNumber}"

                val density = context.resources.displayMetrics.density
                val chipBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f * density
                    setColor(Color.parseColor(tagInfo.chipBgHex))
                    setStroke((1 * density).toInt(), Color.parseColor(tagInfo.chipBorderHex))
                }
                layoutChickenTag.background = chipBg

                val dotBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(tagInfo.colorHex))
                }
                viewChickenTagDot.background = dotBg
                tvChickenTagText.setTextColor(Color.parseColor(tagInfo.chipTextHex))
            } else {
                layoutChickenTag.visibility = View.GONE
            }

            cardAlert.setCardBackgroundColor(ContextCompat.getColor(context, R.color.white))
            cardAlert.strokeColor = ContextCompat.getColor(context, R.color.border_color)

            when (alert.severity) {
                AlertSeverity.CRITICAL -> {
                    viewAccentBar.setBackgroundResource(R.drawable.bg_alert_accent_critical)
                    tvSeverityChip.text = "CRITICAL"
                    tvSeverityChip.setBackgroundResource(R.drawable.bg_severity_chip_critical)
                    tvSeverityChip.setTextColor(ContextCompat.getColor(context, R.color.white))
                }
                AlertSeverity.WARNING -> {
                    viewAccentBar.setBackgroundResource(R.drawable.bg_alert_accent_warning)
                    tvSeverityChip.text = "WARNING"
                    tvSeverityChip.setBackgroundResource(R.drawable.bg_severity_chip_warning)
                    tvSeverityChip.setTextColor(ContextCompat.getColor(context, R.color.white))
                }
                AlertSeverity.INFO -> {
                    viewAccentBar.setBackgroundResource(R.drawable.bg_alert_accent_healthy)
                    tvSeverityChip.text = "NORMAL"
                    tvSeverityChip.setBackgroundResource(R.drawable.bg_healthy_badge)
                    tvSeverityChip.setTextColor(ContextCompat.getColor(context, R.color.healthy))
                }
            }

            tvAlertTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))

            if (alert.isHistorical) {
                // Completed scans are records, not actionable notifications.
                btnResolveAlert.visibility = View.GONE
                tvResolvedStatus.visibility = View.VISIBLE
                tvResolvedStatus.text = "LOGGED"
                cardAlert.alpha = 1.0f
            } else if (alert.isResolved) {
                btnResolveAlert.visibility = View.GONE
                tvResolvedStatus.visibility = View.VISIBLE
                tvResolvedStatus.text = "RESOLVED"
                cardAlert.alpha = 0.60f
            } else {
                btnResolveAlert.visibility = View.VISIBLE
                tvResolvedStatus.visibility = View.GONE
                cardAlert.alpha = 1.0f
                btnResolveAlert.setOnClickListener {
                    onResolveClick?.invoke(alert)
                }
            }

            cardAlert.setOnClickListener { view ->
                view.animate()
                    .scaleX(0.97f)
                    .scaleY(0.97f)
                    .setDuration(80)
                    .withEndAction {
                        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    }
                    .start()
            }
        }
    }
}
