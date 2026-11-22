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
            if (FarmZoneManager.canAddBarn(requireContext())) {
                showAddZoneDialog()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Maximum of ${FarmZoneManager.MAX_BARNS} barns reached. Delete a barn first.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        updateAddButtonState()
    }

    private fun updateAddButtonState() {
        if (_binding == null) return
        val ctx = context ?: return
        val count = FarmZoneManager.getAllZones(ctx).size
        val canAdd = count < FarmZoneManager.MAX_BARNS
        binding.btnAddCustomZone.isEnabled = canAdd
        binding.btnAddCustomZone.alpha = if (canAdd) 1f else 0.5f
        binding.btnAddCustomZone.text = if (canAdd) {
            "+ Add Barn (${count}/${FarmZoneManager.MAX_BARNS})"
        } else {
            "Maximum ${FarmZoneManager.MAX_BARNS} Barns Reached"
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
                val statusText = if (selectedZone.hasCamera && selectedZone.cameraChannelId > 0) {
                    "Monitoring ${selectedZone.barnName} • Camera ${selectedZone.cameraChannelId}"
                } else {
                    "${selectedZone.barnName} active • No camera assigned"
                }
                Toast.makeText(
                    context,
                    statusText,
                    Toast.LENGTH_SHORT
                ).show()
                dismiss()
            },
            onZoneDeleted = { zoneToDelete ->
                confirmDeleteZone(zoneToDelete)
            },
            onAssignCamera = { zoneToPair ->
                val ctx = requireContext()
                val cameraOptions = arrayOf(
                    "📷 Camera 1 (Physical Device 0)",
                    "📷 Camera 2 (Physical Device 1)",
                    "📷 Camera 3 (Physical Device 2)",
                    "🚫 Unassign Camera (Standby)"
                )
                AlertDialog.Builder(ctx)
                    .setTitle("Assign Camera • ${zoneToPair.barnName}")
                    .setItems(cameraOptions) { _, which ->
                        val targetChannel = when (which) {
                            0 -> 1
                            1 -> 2
                            2 -> 3
                            else -> 0
                        }
                        FarmZoneManager.assignCameraToZone(ctx, zoneToPair.id, targetChannel)
                        refreshList()
                        val msg = if (targetChannel > 0) {
                            "Camera $targetChannel assigned to ${zoneToPair.barnName}"
                        } else {
                            "Camera unassigned from ${zoneToPair.barnName}"
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
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
        updateAddButtonState()
    }

    private fun showAddZoneDialog() {
        val context = requireContext()
        if (!FarmZoneManager.canAddBarn(context)) {
            Toast.makeText(
                context,
                "Maximum of ${FarmZoneManager.MAX_BARNS} barns reached. Delete a barn first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
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
            val effectiveZoneName = zoneName.ifBlank { "Main Pen" }
            dialogBinding.tilZoneName.error = null

            val capacity = capacityStr.toIntOrNull() ?: 50
            val hasCamera = dialogBinding.switchAssignCamera.isChecked
            val hasSensor = dialogBinding.switchAssignSensor.isChecked

            val newZone = FarmZoneManager.addCustomZone(
                context = context,
                barnName = barnName,
                zoneName = effectiveZoneName,
                capacity = capacity,
                hasCamera = hasCamera,
                hasSensor = hasSensor,
                description = desc.ifBlank { "Poultry barn" }
            ) ?: run {
                Toast.makeText(
                    context,
                    "Maximum of ${FarmZoneManager.MAX_BARNS} barns reached.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            // Switch to the newly created barn automatically
            FarmZoneManager.setActiveZone(context, newZone)
            Toast.makeText(context, "Barn added & activated: ${newZone.fullDisplayName}", Toast.LENGTH_SHORT).show()
            addDialog.dismiss()
            dismiss()
        }

        addDialog.show()
    }

    private fun confirmDeleteZone(zone: BarnZone) {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle("Remove Barn")
            .setMessage("Are you sure you want to delete ${zone.fullDisplayName}? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val removed = FarmZoneManager.deleteBarn(context, zone.id)
                refreshList()
                Toast.makeText(
                    context,
                    if (removed) "Removed ${zone.barnName}" else "At least one barn is required",
                    Toast.LENGTH_SHORT
                ).show()
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
