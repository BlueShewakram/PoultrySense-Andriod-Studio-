package com.example.poultrysense.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.poultrysense.R
import com.example.poultrysense.data.models.BarnZone
import com.example.poultrysense.databinding.ItemBarnZoneBinding

class BarnZoneAdapter(
    private var zones: List<BarnZone>,
    private var activeZoneId: String,
    private val onZoneSelected: (BarnZone) -> Unit,
    private val onZoneDeleted: ((BarnZone) -> Unit)? = null
) : RecyclerView.Adapter<BarnZoneAdapter.BarnZoneViewHolder>() {

    fun updateData(newZones: List<BarnZone>, newActiveId: String) {
        zones = newZones
        activeZoneId = newActiveId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BarnZoneViewHolder {
        val binding = ItemBarnZoneBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BarnZoneViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BarnZoneViewHolder, position: Int) {
        holder.bind(zones[position])
    }

    override fun getItemCount(): Int = zones.size

    inner class BarnZoneViewHolder(private val binding: ItemBarnZoneBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(zone: BarnZone) {
            val context = binding.root.context
            val isSelected = zone.id == activeZoneId

            binding.tvBarnTitle.text = zone.fullDisplayName
            binding.tvBarnDesc.text = zone.description
            binding.tvCapacityBadge.text = "Cap: ${zone.totalCapacity}"

            // Camera status pill
            if (zone.hasCamera) {
                binding.tvCameraStatus.text = "📷 ${zone.cameraName}"
                binding.tvCameraStatus.setTextColor(ContextCompat.getColor(context, R.color.primary))
                binding.tvCameraStatus.setBackgroundResource(R.drawable.bg_capsule_ai)
            } else {
                binding.tvCameraStatus.text = "📷 No Camera"
                binding.tvCameraStatus.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                binding.tvCameraStatus.setBackgroundResource(R.drawable.bg_date_chip)
            }

            // Sensor status pill
            if (zone.hasSensor) {
                binding.tvSensorStatus.text = "🌡️ ${zone.sensorName}"
                binding.tvSensorStatus.setTextColor(ContextCompat.getColor(context, R.color.healthy))
                binding.tvSensorStatus.setBackgroundResource(R.drawable.bg_healthy_badge)
            } else {
                binding.tvSensorStatus.text = "🌡️ No Sensor"
                binding.tvSensorStatus.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                binding.tvSensorStatus.setBackgroundResource(R.drawable.bg_date_chip)
            }

            // Master Hardware Status Pill
            if (zone.isFullyOnline) {
                binding.tvHardwareStatus.text = "LIVE ONLINE"
                binding.tvHardwareStatus.setBackgroundResource(R.drawable.bg_healthy_badge)
                binding.tvHardwareStatus.setTextColor(ContextCompat.getColor(context, R.color.healthy))
            } else if (zone.hasCamera || zone.hasSensor) {
                binding.tvHardwareStatus.text = "PARTIAL"
                binding.tvHardwareStatus.setBackgroundResource(R.drawable.bg_date_chip)
                binding.tvHardwareStatus.setTextColor(ContextCompat.getColor(context, R.color.warning))
            } else {
                binding.tvHardwareStatus.text = "STANDBY"
                binding.tvHardwareStatus.setBackgroundResource(R.drawable.bg_date_chip)
                binding.tvHardwareStatus.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            }

            // Visual styling when selected
            if (isSelected) {
                binding.cardBarnZone.strokeColor = ContextCompat.getColor(context, R.color.primary)
                binding.cardBarnZone.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
                binding.cardBarnZone.setCardBackgroundColor(ContextCompat.getColor(context, R.color.healthy_container))
                binding.ivSelectedCheck.visibility = View.VISIBLE
                binding.flIconBg.setBackgroundResource(R.drawable.bg_capsule_ai)
                binding.ivBarnIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))
            } else {
                binding.cardBarnZone.strokeColor = ContextCompat.getColor(context, R.color.border_color)
                binding.cardBarnZone.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
                binding.cardBarnZone.setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
                binding.ivSelectedCheck.visibility = View.GONE
                binding.flIconBg.setBackgroundResource(R.drawable.bg_date_chip)
                binding.ivBarnIcon.setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
            }

            // Delete button for custom-created zones
            if (zone.isCustom) {
                binding.btnDeleteZone.visibility = View.VISIBLE
                binding.btnDeleteZone.setOnClickListener {
                    onZoneDeleted?.invoke(zone)
                }
            } else {
                binding.btnDeleteZone.visibility = View.GONE
            }

            binding.cardBarnZone.setOnClickListener {
                onZoneSelected(zone)
            }
        }
    }
}
