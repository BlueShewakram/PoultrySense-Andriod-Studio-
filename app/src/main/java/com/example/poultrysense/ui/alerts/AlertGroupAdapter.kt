package com.example.poultrysense.ui.alerts

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.poultrysense.R
import com.example.poultrysense.data.models.FarmAlert
import com.example.poultrysense.databinding.ItemAlertBinding
import com.example.poultrysense.databinding.ItemAlertGroupBinding

/**
 * A logical group of alerts:
 *  - the current live camera alerts,
 *  - all flagged chickens from one completed scan,
 *  - or the current environment alerts.
 */
data class AlertGroup(
    val id: String,
    val title: String,
    val subtitle: String,
    val countLabel: String,
    val stats: String?,
    val alerts: List<FarmAlert>,
    val emptyMessage: String,
    val accentColor: Int,
    val isScan: Boolean = false
)

class AlertGroupAdapter(
    private var groups: List<AlertGroup>,
    private val onResolveClick: ((FarmAlert) -> Unit)?
) : RecyclerView.Adapter<AlertGroupAdapter.GroupViewHolder>() {

    class GroupViewHolder(val binding: ItemAlertGroupBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemAlertGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = groups[position]
        val context = holder.itemView.context
        with(holder.binding) {
            tvGroupTitle.text = group.title
            tvGroupSubtitle.text = group.subtitle
            tvGroupCount.text = group.countLabel
            viewGroupAccent.backgroundTintList = ColorStateList.valueOf(group.accentColor)

            if (group.isScan) {
                tvGroupCount.setBackgroundResource(R.drawable.bg_date_chip)
                tvGroupCount.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            } else {
                tvGroupCount.setBackgroundResource(R.drawable.bg_healthy_badge)
                tvGroupCount.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }

            if (group.stats.isNullOrBlank()) {
                tvGroupStats.visibility = View.GONE
            } else {
                tvGroupStats.visibility = View.VISIBLE
                tvGroupStats.text = group.stats
            }

            layoutGroupAlerts.removeAllViews()
            if (group.alerts.isEmpty()) {
                tvGroupEmpty.visibility = View.VISIBLE
                tvGroupEmpty.text = group.emptyMessage
            } else {
                tvGroupEmpty.visibility = View.GONE
                group.alerts.forEachIndexed { index, alert ->
                    val alertBinding = ItemAlertBinding.inflate(
                        LayoutInflater.from(context),
                        layoutGroupAlerts,
                        false
                    )
                    AlertCardBinder.bind(alertBinding, alert, onResolveClick)
                    layoutGroupAlerts.addView(alertBinding.root)

                    if (index == group.alerts.lastIndex) {
                        (alertBinding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                            it.bottomMargin = 0
                            alertBinding.root.layoutParams = it
                        }
                    }
                }
            }
        }

        holder.itemView.alpha = 0f
        holder.itemView.translationY = 14f
        holder.itemView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setStartDelay((position * 30).toLong())
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    override fun getItemCount(): Int = groups.size

    fun updateData(newGroups: List<AlertGroup>) {
        groups = newGroups
        notifyDataSetChanged()
    }
}
