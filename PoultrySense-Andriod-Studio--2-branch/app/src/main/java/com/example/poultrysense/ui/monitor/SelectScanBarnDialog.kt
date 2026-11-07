package com.example.poultrysense.ui.monitor

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.poultrysense.R
import com.example.poultrysense.data.FarmZoneManager
import com.example.poultrysense.data.models.BarnZone
import com.example.poultrysense.databinding.DialogSelectScanBarnBinding
import com.example.poultrysense.databinding.ItemScanBarnChoiceBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SelectScanBarnDialog(
    private val onBarnSelectedForScan: (BarnZone) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogSelectScanBarnBinding? = null
    private val binding get() = _binding!!

    private var selectedZone: BarnZone? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSelectScanBarnBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        val allZones = FarmZoneManager.getAllZones(context)
        val activeZone = FarmZoneManager.getActiveZone(context)

        // Default selection: active zone, or the zone with live camera
        selectedZone = allZones.find { it.hasCamera } ?: activeZone

        val adapter = ScanBarnChoiceAdapter(allZones, selectedZone?.id ?: "") { chosen ->
            selectedZone = chosen
            updateUiState()
        }

        binding.rvScanBarnChoices.layoutManager = LinearLayoutManager(context)
        binding.rvScanBarnChoices.adapter = adapter

        updateUiState()

        binding.btnCancelScanChoice.setOnClickListener {
            dismiss()
        }

        binding.btnConfirmStartScan.setOnClickListener {
            val barn = selectedZone ?: return@setOnClickListener
            // If the user selected a standby barn, reassign Camera 1 to this barn
            if (!barn.hasCamera) {
                FarmZoneManager.assignCameraToZone(requireContext(), barn.id)
            }
            FarmZoneManager.setActiveZone(requireContext(), barn)
            onBarnSelectedForScan(barn)
            dismiss()
        }
    }

    private fun updateUiState() {
        val barn = selectedZone ?: return
        if (barn.hasCamera) {
            binding.cardCameraWarning.visibility = View.GONE
            binding.btnConfirmStartScan.text = "Start AI Scan on ${barn.barnName}"
            binding.btnConfirmStartScan.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
        } else {
            binding.cardCameraWarning.visibility = View.VISIBLE
            binding.tvCameraWarningDesc.text =
                "No physical camera is currently assigned to ${barn.fullDisplayName}. Tap below to move Camera 1 here and start scanning."
            binding.btnConfirmStartScan.text = "Move Camera & Start Scan"
            binding.btnConfirmStartScan.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#E65100"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─── Inline Choice Adapter ───
    private class ScanBarnChoiceAdapter(
        private val zones: List<BarnZone>,
        private var selectedId: String,
        private val onSelect: (BarnZone) -> Unit
    ) : RecyclerView.Adapter<ScanBarnChoiceAdapter.ChoiceViewHolder>() {

        class ChoiceViewHolder(val binding: ItemScanBarnChoiceBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChoiceViewHolder {
            val binding = ItemScanBarnChoiceBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ChoiceViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ChoiceViewHolder, position: Int) {
            val zone = zones[position]
            val isSelected = zone.id == selectedId
            val context = holder.binding.root.context

            with(holder.binding) {
                tvScanBarnTitle.text = zone.fullDisplayName
                tvScanBarnCapacity.text = "${zone.totalCapacity} Birds"
                rbScanBarn.isChecked = isSelected

                if (isSelected) {
                    cardScanBarnChoice.strokeColor = ContextCompat.getColor(context, R.color.primary)
                    cardScanBarnChoice.strokeWidth = 4
                    cardScanBarnChoice.setCardBackgroundColor(Color.parseColor("#F0FDF4"))
                } else {
                    cardScanBarnChoice.strokeColor = Color.parseColor("#CBD5E1")
                    cardScanBarnChoice.strokeWidth = 2
                    cardScanBarnChoice.setCardBackgroundColor(Color.WHITE)
                }

                // Camera status badge
                if (zone.hasCamera) {
                    badgeScanCamera.text = "📷 Cam 1 Active"
                    badgeScanCamera.setBackgroundResource(R.drawable.bg_healthy_badge)
                    badgeScanCamera.setTextColor(ContextCompat.getColor(context, R.color.primary))
                } else {
                    badgeScanCamera.text = "📷 No Camera (Standby)"
                    badgeScanCamera.setBackgroundResource(R.drawable.bg_date_chip)
                    badgeScanCamera.setTextColor(Color.parseColor("#64748B"))
                }

                // Sensor status badge
                if (zone.hasSensor) {
                    badgeScanSensor.text = "🌡️ ESP32 #1 Active"
                    badgeScanSensor.setTextColor(Color.parseColor("#1E3A8A"))
                } else {
                    badgeScanSensor.text = "🌡️ No Sensor"
                    badgeScanSensor.setTextColor(Color.parseColor("#94A3B8"))
                }

                root.setOnClickListener {
                    selectedId = zone.id
                    notifyDataSetChanged()
                    onSelect(zone)
                }
            }
        }

        override fun getItemCount(): Int = zones.size
    }
}

