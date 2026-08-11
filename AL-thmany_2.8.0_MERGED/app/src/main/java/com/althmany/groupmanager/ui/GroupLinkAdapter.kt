package com.althmany.groupmanager.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.althmany.groupmanager.R
import com.althmany.groupmanager.databinding.ItemGroupLinkBinding
import com.althmany.groupmanager.model.GroupLink
import com.althmany.groupmanager.model.LinkResultCode
import com.althmany.groupmanager.model.LinkStatus

class GroupLinkAdapter(
    private val onOpen: (GroupLink) -> Unit,
    private val onCopy: (GroupLink) -> Unit,
    private val onDelete: (GroupLink) -> Unit
) : ListAdapter<GroupLink, GroupLinkAdapter.LinkViewHolder>(DiffCallback) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkViewHolder {
        val binding = ItemGroupLinkBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LinkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LinkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LinkViewHolder(
        private val binding: ItemGroupLinkBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GroupLink) {
            val context = binding.root.context
            binding.positionText.text = context.getString(R.string.link_number, item.position + 1)
            binding.urlText.text = item.url
            binding.attemptsText.text = context.getString(R.string.open_attempts, item.openAttempts)

            val resultText = item.resultCode?.let { code ->
                val label = context.getString(
                    when (code) {
                        LinkResultCode.JOIN_ACTION_COMPLETED -> R.string.result_join_action_completed
                        LinkResultCode.REQUEST_SENT -> R.string.result_request_sent
                        LinkResultCode.ALREADY_MEMBER -> R.string.result_already_member
                        LinkResultCode.GROUP_FULL -> R.string.result_group_full
                        LinkResultCode.INVALID_OR_EXPIRED -> R.string.result_invalid_or_expired
                        LinkResultCode.REMOVED_OR_BANNED -> R.string.result_removed_or_banned
                        LinkResultCode.WHATSAPP_REJECTED -> R.string.result_whatsapp_rejected
                        LinkResultCode.RESTRICTED -> R.string.result_restricted
                        LinkResultCode.OPEN_FAILED -> R.string.result_open_failed
                        LinkResultCode.BROWSER_FALLBACK -> R.string.result_browser_fallback
                        LinkResultCode.UNKNOWN_SCREEN -> R.string.result_unknown_screen
                        LinkResultCode.ACTION_TIMEOUT -> R.string.result_action_timeout
                        LinkResultCode.USER_SKIPPED -> R.string.result_user_skipped
                        LinkResultCode.MANUAL_JOINED -> R.string.result_manual_joined
                    }
                )
                label
            }
            binding.resultDetailText.text = resultText.orEmpty()
            binding.resultDetailText.visibility = if (resultText.isNullOrBlank()) View.GONE else View.VISIBLE

            val (labelRes, colorRes) = when (item.status) {
                LinkStatus.PENDING -> R.string.status_pending to R.color.status_pending
                LinkStatus.OPENED -> R.string.status_opened to R.color.status_opened
                LinkStatus.JOINED -> R.string.status_joined to R.color.status_joined
                LinkStatus.REQUESTED -> R.string.status_requested to R.color.status_requested
                LinkStatus.SKIPPED -> R.string.status_skipped to R.color.status_skipped
                LinkStatus.FAILED -> R.string.status_failed to R.color.status_failed
            }
            val statusColor = ContextCompat.getColor(context, colorRes)
            binding.statusText.text = context.getString(labelRes)
            binding.statusText.setTextColor(statusColor)
            binding.statusText.background = GradientDrawable().apply {
                cornerRadius = context.resources.displayMetrics.density * 14f
                setColor(ColorUtils.setAlphaComponent(statusColor, 28))
                setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), statusColor)
            }

            binding.openButton.isEnabled = item.status != LinkStatus.JOINED &&
                item.status != LinkStatus.REQUESTED &&
                item.status != LinkStatus.SKIPPED
            binding.openButton.alpha = if (binding.openButton.isEnabled) 1f else 0.45f
            binding.openButton.setOnClickListener { onOpen(item) }
            binding.copyButton.setOnClickListener { onCopy(item) }
            binding.deleteButton.setOnClickListener { onDelete(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<GroupLink>() {
        override fun areItemsTheSame(oldItem: GroupLink, newItem: GroupLink): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: GroupLink, newItem: GroupLink): Boolean =
            oldItem == newItem
    }
}
