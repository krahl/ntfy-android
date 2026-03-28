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
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.topicShortUrl

class WearSubscriptionAdapter(
    private val appBaseUrl: String,
    private val onClick: (Subscription) -> Unit
) : ListAdapter<Subscription, WearSubscriptionAdapter.ViewHolder>(DiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wear_subscription, parent, false)
        return ViewHolder(view, appBaseUrl, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val appBaseUrl: String,
        private val onClick: (Subscription) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.wear_subscription_card)
        private val title = itemView.findViewById<TextView>(R.id.wear_subscription_title)
        private val subtitle = itemView.findViewById<TextView>(R.id.wear_subscription_subtitle)
        private val counts = itemView.findViewById<TextView>(R.id.wear_subscription_counts)

        fun bind(subscription: Subscription) {
            title.text = displayName(appBaseUrl, subscription)
            subtitle.text = topicShortUrl(subscription.baseUrl, subscription.topic)
            counts.text = if (subscription.totalCount > 0) {
                itemView.context.getString(
                    R.string.wear_subscription_counts,
                    subscription.newCount,
                    subscription.totalCount
                )
            } else {
                itemView.context.getString(R.string.wear_subscription_counts_empty)
            }
            card.setOnClickListener { onClick(subscription) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Subscription>() {
        override fun areItemsTheSame(oldItem: Subscription, newItem: Subscription): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Subscription, newItem: Subscription): Boolean {
            return oldItem == newItem
        }
    }
}
