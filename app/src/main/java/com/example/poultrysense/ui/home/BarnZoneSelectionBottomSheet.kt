package com.example.poultrysense.ui.home

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.poultrysense.data.FarmZoneManager
import com.example.poultrysense.data.models.BarnZone
import com.example.poultrysense.databinding.DialogAddBarnZoneBinding
import com.example.poultrysense.databinding.DialogSelectBarnZoneBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BarnZoneSelectionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogSelectBarnZoneBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: BarnZoneAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSelectBarnZoneBinding.inflate(inflater, container, false)
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

        setupRecyclerView()

        binding.btnCloseSheet.setOnClickListener {
            dismiss()
        }

        binding.btnAddCustomZone.setOnClickListener {
            showAddZoneDialog()
        }
    }

    private fun setupRecyclerView() {
        val context = requireContext()
        val allZones = FarmZoneManager.getAllZones(context)
        val activeZone = FarmZoneManager.getActiveZone(context)

        adapter = BarnZoneAdapter(
            zones = allZones,
            activeZoneId = activeZone.id,
            onZoneSelected = { selectedZone ->
                FarmZoneManager.setActiveZone(requireContext(), selectedZone)
                Toast.makeText(
                    context,
                    "Active Zone: ${selectedZone.fullDisplayName}",
                    Toast.LENGTH_SHORT
                ).show()
                dismiss()
            },
            onZoneDeleted = { zoneToDelete ->
                confirmDeleteZone(zoneToDelete)
            },
            onAssignCamera = { zoneToPair ->
                val ctx = requireContext()
                AlertDialog.Builder(ctx)
                    .setTitle("Pair Camera to ${zoneToPair.barnName}?")
                    .setMessage("Move physical Camera 1 to ${zoneToPair.fullDisplayName}? The live video stream and AI vision analysis will be assigned to this area.")
                    .setPositiveButton("Pair Camera") { _, _ ->
                        FarmZoneManager.assignCameraToZone(ctx, zoneToPair.id)
                        FarmZoneManager.setActiveZone(ctx, zoneToPair)
                        refreshList()
                        Toast.makeText(ctx, "Camera 1 paired to ${zoneToPair.fullDisplayName}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        binding.rvBarnZones.layoutManager = LinearLayoutManager(context)
        binding.rvBarnZones.adapter = adapter
    }

    private fun refreshList() {
        val context = context ?: return
        val allZones = FarmZoneManager.getAllZones(context)
        val activeZone = FarmZoneManager.getActiveZone(context)
        adapter.updateData(allZones, activeZone.id)
    }

    private fun showAddZoneDialog() {
        val context = requireContext()
        val dialogBinding = DialogAddBarnZoneBinding.inflate(LayoutInflater.from(context))

        val addDialog = AlertDialog.Builder(context)
            .setView(dialogBinding.root)
            .create()

        addDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnCloseAddDialog.setOnClickListener {
            addDialog.dismiss()
        }

        dialogBinding.btnSaveCustomZone.setOnClickListener {
            val barnName = dialogBinding.etBarnName.text?.toString()?.trim().orEmpty()
            val zoneName = dialogBinding.etZoneName.text?.toString()?.trim().orEmpty()
            val capacityStr = dialogBinding.etCapacity.text?.toString()?.trim().orEmpty()
            val desc = dialogBinding.etDesc.text?.toString()?.trim().orEmpty()

            if (barnName.isBlank()) {
                dialogBinding.tilBarnName.error = "Barn name is required"
                return@setOnClickListener
            }
            if (zoneName.isBlank()) {
                dialogBinding.tilZoneName.error = "Zone name is required"
                return@setOnClickListener
            }

            val capacity = capacityStr.toIntOrNull() ?: 50
            val hasCamera = dialogBinding.switchAssignCamera.isChecked
            val hasSensor = dialogBinding.switchAssignSensor.isChecked

            val newZone = FarmZoneManager.addCustomZone(
                context = context,
                barnName = barnName,
                zoneName = zoneName,
                capacity = capacity,
                hasCamera = hasCamera,
                hasSensor = hasSensor,
                description = desc.ifBlank { "Custom Poultry Pen" }
            )

            // Switch to the newly created zone automatically
            FarmZoneManager.setActiveZone(context, newZone)
            Toast.makeText(context, "Registered & Activated: ${newZone.fullDisplayName}", Toast.LENGTH_SHORT).show()
            addDialog.dismiss()
            dismiss()
        }

        addDialog.show()
    }

    private fun confirmDeleteZone(zone: BarnZone) {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle("Remove Farm Zone")
            .setMessage("Are you sure you want to delete ${zone.fullDisplayName}? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                FarmZoneManager.deleteCustomZone(context, zone.id)
                refreshList()
                Toast.makeText(context, "Removed ${zone.fullDisplayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BarnZoneSelectionBottomSheet"

        fun newInstance(): BarnZoneSelectionBottomSheet {
            return BarnZoneSelectionBottomSheet()
        }
    }
}
