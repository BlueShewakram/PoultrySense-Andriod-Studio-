package com.example.poultrysense.ui.history

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.poultrysense.data.ChickenTagHelper
import com.example.poultrysense.data.models.ScanSession
import com.example.poultrysense.databinding.ItemHistoryBinding
import com.example.poultrysense.R
import com.google.android.material.chip.Chip

class HistoryAdapter(private var sessions: List<ScanSession>) :
    RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val expandedSessionIds = mutableSetOf<String>()

    class HistoryViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    fun updateData(newSessions: List<ScanSession>) {
        sessions = newSessions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val session = sessions[position]
        val details = session.safeChickenDetails
        val hasDetails = details.isNotEmpty()
        val isExpanded = expandedSessionIds.contains(session.id) && hasDetails

        with(holder.binding) {
            tvHistorySessionNum.text = session.sessionNumber
            tvHistoryStatus.text = session.status
            tvHistoryBarnLocation.text = session.barnDisplayName
            tvHistoryDateTime.text = session.loggedAt
            tvHistoryHealthIndex.text = "Health Index: ${session.healthIndex}"
            tvHistoryTotal.text = session.totalFlock.toString()
            tvHistoryHealthy.text = session.healthyCount.toString()
            tvHistoryFlagged.text = session.illCount.toString()

            // Expandable Tag Dropdown State — only shown when the scan actually
            // recorded per-bird details (never fabricated from a count).
            btnToggleChickenDropdown.visibility = if (hasDetails) View.VISIBLE else View.GONE
            layoutChickenDropdown.visibility = if (isExpanded) View.VISIBLE else View.GONE
            ivDropdownChevron.rotation = if (isExpanded) 180f else 0f

            tvDropdownSummary.text = if (isExpanded) {
                "Hide Chicken Tag Breakdown"
            } else {
                if (session.illCount > 0) {
                    "View Chicken Tags (${session.illCount} Flagged)"
                } else {
                    "View Chicken Tags (All Healthy)"
                }
            }

            // Populate Chicken Tag Chips safely
            chipGroupFlagged.removeAllViews()
            chipGroupNormal.removeAllViews()

            try {
                val flaggedBirds = details.filter { (it.anomalyTier ?: "Normal") != "Normal" || it.anomalyScore >= 40 }
                val normalBirds = details.filter { (it.anomalyTier ?: "Normal") == "Normal" && it.anomalyScore < 40 }

                if (flaggedBirds.isNotEmpty()) {
                    tvFlaggedSectionHeader.visibility = View.VISIBLE
                    chipGroupFlagged.visibility = View.VISIBLE
                    for (bird in flaggedBirds) {
                        val tag = ChickenTagHelper.getTagInfo(bird.chickenId)
                        val chip = createChickenChip(
                            parent = chipGroupFlagged,
                            label = "${tag.emoji} ${tag.shortLabel} • FLAGGED",
                            bgHex = tag.chipBgHex,
                            strokeHex = "#E65100",
                            textHex = tag.chipTextHex,
                            strokeW = 2.5f
                        )
                        chipGroupFlagged.addView(chip)
                    }
                } else {
                    tvFlaggedSectionHeader.visibility = View.GONE
                    chipGroupFlagged.visibility = View.GONE
                }

                if (normalBirds.isNotEmpty()) {
                    tvNormalSectionHeader.visibility = View.VISIBLE
                    chipGroupNormal.visibility = View.VISIBLE
                    for (bird in normalBirds) {
                        val tag = ChickenTagHelper.getTagInfo(bird.chickenId)
                        val chip = createChickenChip(
                            parent = chipGroupNormal,
                            label = "${tag.emoji} ${tag.shortLabel} • Normal",
                            bgHex = tag.chipBgHex,
                            strokeHex = tag.chipBorderHex,
                            textHex = tag.chipTextHex,
                            strokeW = 1.5f
                        )
                        chipGroupNormal.addView(chip)
                    }
                } else {
                    tvNormalSectionHeader.visibility = View.GONE
                    chipGroupNormal.visibility = View.GONE
                }
            } catch (e: Exception) {
                tvFlaggedSectionHeader.visibility = View.GONE
                chipGroupFlagged.visibility = View.GONE
                tvNormalSectionHeader.visibility = View.GONE
                chipGroupNormal.visibility = View.GONE
            }

            btnToggleChickenDropdown.setOnClickListener {
                if (expandedSessionIds.contains(session.id)) {
                    expandedSessionIds.remove(session.id)
                } else {
                    expandedSessionIds.add(session.id)
                }
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(pos)
                }
            }

            // Tap the scan card to acknowledge it in the database. The record is
            // marked reviewed in Firebase but stays visible in History and Alerts.
            holder.itemView.setOnClickListener {
                val ctx = holder.itemView.context
                val uid = com.example.poultrysense.data.UserPreferences.getCachedUser(ctx)?.uid
                if (!uid.isNullOrBlank()) {
                    try {
                        com.example.poultrysense.data.FirebaseConfig.database()
                            .getReference("users").child(uid).child("scan_history").child(session.id)
                            .updateChildren(mapOf("reviewed" to true))
                    } catch (_: Exception) { }
                } else {
                    try {
                        com.example.poultrysense.data.FirebaseConfig.database()
                            .getReference("farms").child("scan_history").child(session.id)
                            .updateChildren(mapOf("reviewed" to true))
                    } catch (_: Exception) { }
                }
                android.widget.Toast.makeText(ctx, "Scan marked as reviewed", android.widget.Toast.LENGTH_SHORT).show()
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

    override fun getItemCount(): Int = sessions.size

    /** Inflate a Material3-themed Chip from the XML layout to avoid the crash
     *  caused by programmatic `Chip(context)` which requires Theme.MaterialComponents. */
    private fun createChickenChip(
        parent: ViewGroup,
        label: String,
        bgHex: String,
        strokeHex: String,
        textHex: String,
        strokeW: Float
    ): Chip {
        val chip = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_filter_chip, parent, false) as Chip
        chip.text = label
        chip.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor(bgHex))
        chip.chipStrokeColor = ColorStateList.valueOf(Color.parseColor(strokeHex))
        chip.chipStrokeWidth = strokeW
        chip.setTextColor(Color.parseColor(textHex))
        chip.textSize = 11.5f
        chip.isCheckable = false
        chip.isClickable = false
        return chip
    }
}
