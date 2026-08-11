package com.althmany.groupmanager.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.althmany.groupmanager.R
import com.althmany.groupmanager.databinding.ItemHistorySessionBinding
import com.althmany.groupmanager.model.SessionStatus
import com.althmany.groupmanager.model.SessionSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class HistoryAdapter(
    private val onDelete: (SessionSummary) -> Unit
) : ListAdapter<SessionSummary, HistoryAdapter.HistoryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        return HistoryViewHolder(
            ItemHistorySessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(
        private val binding: ItemHistorySessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SessionSummary) {
            val context = binding.root.context
            val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(context.resources.configuration.locales[0])
                .withZone(ZoneId.systemDefault())

            binding.dateText.text = formatter.format(Instant.ofEpochMilli(item.createdAt))
            binding.sourceText.text = item.sourceLabel
            binding.statsText.text = context.getString(
                R.string.history_stats,
                item.totalCount,
                item.joinedCount,
                item.requestedCount,
                item.skippedCount,
                item.failedCount
            )
            binding.statusText.text = when {
                item.isActive -> context.getString(R.string.history_active)
                item.status == SessionStatus.COMPLETED -> context.getString(R.string.history_completed)
                else -> context.getString(R.string.history_abandoned)
            }
            binding.deleteButton.isEnabled = !item.isActive
            binding.deleteButton.alpha = if (item.isActive) 0.35f else 1f
            binding.deleteButton.setOnClickListener { onDelete(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<SessionSummary>() {
        override fun areItemsTheSame(oldItem: SessionSummary, newItem: SessionSummary): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: SessionSummary, newItem: SessionSummary): Boolean =
            oldItem == newItem
    }
}
