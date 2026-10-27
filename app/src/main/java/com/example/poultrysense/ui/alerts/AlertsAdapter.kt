package com.example.poultrysense.ui.alerts

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.poultrysense.R
import com.example.poultrysense.data.ChickenTagHelper
import com.example.poultrysense.data.models.AlertSeverity
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.databinding.ItemAlertBinding

class AlertsAdapter(
    private var alerts: List<FarmAlert>,
    private val onResolveClick: ((FarmAlert) -> Unit)? = null
) : RecyclerView.Adapter<AlertsAdapter.AlertViewHolder>() {

    class AlertViewHolder(val binding: ItemAlertBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val binding = ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AlertViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        val alert = alerts[position]
        val context = holder.itemView.context
        val isCritical = alert.severity == AlertSeverity.CRITICAL

        with(holder.binding) {
            tvAlertTitle.text = alert.title
            tvAlertDescription.text = alert.description
            tvAlertTime.text = alert.timeAgo

            if (alert.location != null) {
                layoutAlertLocation.visibility = View.VISIBLE
                tvAlertLocation.text = alert.location
            } else {
                layoutAlertLocation.visibility = View.GONE
            }

            // Physical Chicken Tag Color Band Chip
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

            // Clean white card — severity indicated by left accent bar + small chip only
            cardAlert.setCardBackgroundColor(ContextCompat.getColor(context, R.color.white))
            cardAlert.strokeColor = ContextCompat.getColor(context, R.color.border_color)

            if (isCritical) {
                viewAccentBar.setBackgroundResource(R.drawable.bg_alert_accent_critical)
                tvSeverityChip.text = "CRITICAL"
                tvSeverityChip.setBackgroundResource(R.drawable.bg_severity_chip_critical)
            } else {
                viewAccentBar.setBackgroundResource(R.drawable.bg_alert_accent_warning)
                tvSeverityChip.text = "WARNING"
                tvSeverityChip.setBackgroundResource(R.drawable.bg_severity_chip_warning)
            }

            // Title stays neutral dark — no colored title text
            tvAlertTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))

            // One-Tap Alert Resolution UI state
            if (alert.isResolved) {
                btnResolveAlert.visibility = View.GONE
                tvResolvedStatus.visibility = View.VISIBLE
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

        // Staggered fade-in entrance
        holder.itemView.alpha = 0f
        holder.itemView.translationY = 20f
        holder.itemView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setStartDelay((position * 30).toLong())
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    override fun getItemCount(): Int = alerts.size

    fun updateData(newAlerts: List<FarmAlert>) {
        alerts = newAlerts
        notifyDataSetChanged()
    }
}
