package io.heckel.ntfy.wear

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.util.decodeMessage
import io.heckel.ntfy.util.formatDateShort
import io.heckel.ntfy.util.supportedImage

class WearNotificationAdapter(
    private val onClick: (Notification) -> Unit
) : ListAdapter<Notification, WearNotificationAdapter.ViewHolder>(DiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wear_notification, parent, false)
        return ViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onClick: (Notification) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.wear_notification_card)
        private val title = itemView.findViewById<TextView>(R.id.wear_notification_title)
        private val message = itemView.findViewById<TextView>(R.id.wear_notification_message)
        private val meta = itemView.findViewById<TextView>(R.id.wear_notification_meta)

        fun bind(notification: Notification) {
            title.visibility = if (notification.title.isBlank()) View.GONE else View.VISIBLE
            title.text = notification.title
            message.text = decodeMessage(notification)
                .replace("\n", " ")
                .trim()
                .ifBlank { itemView.context.getString(R.string.wear_notification_title_fallback) }
            val parts = mutableListOf(formatDateShort(notification.timestamp))
            if (notification.attachment != null && supportedImage(notification.attachment.type)) {
                parts.add(itemView.context.getString(R.string.wear_notification_indicator_image))
            }
            if (notification.click.isNotBlank()) {
                parts.add(itemView.context.getString(R.string.wear_notification_indicator_link))
            }
            if (!notification.actions.isNullOrEmpty()) {
                parts.add(
                    itemView.context.getString(
                        R.string.wear_notification_indicator_actions,
                        notification.actions.size
                    )
                )
            }
            meta.text = parts.joinToString(" / ")
            card.setOnClickListener { onClick(notification) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Notification>() {
        override fun areItemsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem == newItem
        }
    }
}
