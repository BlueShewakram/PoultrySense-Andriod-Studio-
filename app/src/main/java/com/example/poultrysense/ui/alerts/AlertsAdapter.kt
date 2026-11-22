package com.example.poultrysense.ui.alerts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
        AlertCardBinder.bind(holder.binding, alert, onResolveClick)

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
