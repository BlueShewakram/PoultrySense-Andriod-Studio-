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

            // Populate Chicken Tag Chips
            chipGroupFlagged.removeAllViews()
            chipGroupNormal.removeAllViews()

            val flaggedBirds = details.filter { (it.anomalyTier ?: "Normal") != "Normal" || it.anomalyScore >= 40 }
            val normalBirds = details.filter { (it.anomalyTier ?: "Normal") == "Normal" && it.anomalyScore < 40 }

            if (flaggedBirds.isNotEmpty()) {
                tvFlaggedSectionHeader.visibility = View.VISIBLE
                chipGroupFlagged.visibility = View.VISIBLE
                for (bird in flaggedBirds) {
                    val tag = ChickenTagHelper.getTagInfo(bird.chickenId)
                    val chip = Chip(holder.binding.root.context).apply {
                        text = "${tag.emoji} ${tag.shortLabel} • FLAGGED"
                        chipBackgroundColor = ColorStateList.valueOf(Color.parseColor(tag.chipBgHex))
                        chipStrokeColor = ColorStateList.valueOf(Color.parseColor("#E65100"))
                        chipStrokeWidth = 2.5f
                        setTextColor(Color.parseColor(tag.chipTextHex))
                        textSize = 11.5f
                        isCheckable = false
                        isClickable = false
                    }
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
                    val chip = Chip(holder.binding.root.context).apply {
                        text = "${tag.emoji} ${tag.shortLabel} • Normal"
                        chipBackgroundColor = ColorStateList.valueOf(Color.parseColor(tag.chipBgHex))
                        chipStrokeColor = ColorStateList.valueOf(Color.parseColor(tag.chipBorderHex))
                        chipStrokeWidth = 1.5f
                        setTextColor(Color.parseColor(tag.chipTextHex))
                        textSize = 11.5f
                        isCheckable = false
                        isClickable = false
                    }
                    chipGroupNormal.addView(chip)
                }
            } else {
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
}
