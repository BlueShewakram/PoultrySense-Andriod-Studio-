package com.example.poultrysense.ui.alerts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.poultrysense.R
import com.example.poultrysense.data.models.AlertSeverity
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.databinding.ItemAlertBinding

class AlertsAdapter(private var alerts: List<FarmAlert>) :
    RecyclerView.Adapter<AlertsAdapter.AlertViewHolder>() {

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

            if (isCritical) {
                cardAlert.setCardBackgroundColor(ContextCompat.getColor(context, R.color.critical_container))
                cardAlert.strokeColor = ContextCompat.getColor(context, R.color.critical_stroke)
                ivAlertIcon.setImageResource(R.drawable.ic_error)
                ivAlertIcon.setColorFilter(ContextCompat.getColor(context, R.color.critical))
                tvAlertTitle.setTextColor(ContextCompat.getColor(context, R.color.critical))
            } else {
                cardAlert.setCardBackgroundColor(ContextCompat.getColor(context, R.color.warning_container))
                cardAlert.strokeColor = ContextCompat.getColor(context, R.color.warning_stroke)
                ivAlertIcon.setImageResource(R.drawable.ic_warning_amber)
                ivAlertIcon.setColorFilter(ContextCompat.getColor(context, R.color.warning))
                tvAlertTitle.setTextColor(ContextCompat.getColor(context, R.color.warning))
            }

            cardAlert.setOnClickListener { view ->
                view.animate()
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(80)
                    .withEndAction {
                        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    }
                    .start()
            }
        }

        holder.itemView.alpha = 0f
        holder.itemView.translationY = 24f
        holder.itemView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240)
            .setStartDelay((position * 35).toLong())
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    override fun getItemCount(): Int = alerts.size

    fun updateData(newAlerts: List<FarmAlert>) {
        alerts = newAlerts
        notifyDataSetChanged()
    }
}
