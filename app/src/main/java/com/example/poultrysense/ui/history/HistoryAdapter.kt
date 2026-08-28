package com.example.poultrysense.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.poultrysense.data.models.ScanSession
import com.example.poultrysense.databinding.ItemHistoryBinding

class HistoryAdapter(private val sessions: List<ScanSession>) :
    RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val session = sessions[position]
        with(holder.binding) {
            tvHistorySessionNum.text = session.sessionNumber
            tvHistoryStatus.text = session.status
            tvHistoryDateTime.text = "${session.date} • ${session.time}"
            tvHistoryHealthIndex.text = "Normal Rate: ${session.healthIndex}"
            tvHistoryTotal.text = session.totalFlock.toString()
            tvHistoryHealthy.text = session.healthyCount.toString()
            tvHistoryFlagged.text = session.illCount.toString()
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

        holder.itemView.setOnClickListener { view ->
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

    override fun getItemCount(): Int = sessions.size
}
