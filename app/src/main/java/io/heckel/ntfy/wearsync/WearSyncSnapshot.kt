package io.heckel.ntfy.wearsync

import io.heckel.ntfy.db.ClientCertificate
import io.heckel.ntfy.db.ConnectionDetails
import io.heckel.ntfy.db.CustomHeader
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.TrustedCertificate
import io.heckel.ntfy.db.User

data class WearSyncSnapshot(
    val generatedAt: Long,
    val subscriptions: List<WearSyncSubscription>,
    val users: List<User>,
    val customHeaders: List<CustomHeader>,
    val trustedCertificates: List<TrustedCertificate>,
    val clientCertificates: List<ClientCertificate>,
    val prefs: WearSyncPrefs
)

data class WearSyncPrefs(
    val defaultBaseUrl: String?,
    val darkMode: Int,
    val dynamicColorsEnabled: Boolean
)

data class WearSyncSubscription(
    val id: Long,
    val baseUrl: String,
    val topic: String,
    val instant: Boolean,
    val mutedUntil: Long,
    val minPriority: Int,
    val autoDelete: Long,
    val insistent: Int,
    val lastNotificationId: String?,
    val icon: String?,
    val upAppId: String?,
    val upConnectorToken: String?,
    val displayName: String?,
    val dedicatedChannels: Boolean
) {
    fun toSubscription(): Subscription {
        return Subscription(
            id = id,
            baseUrl = baseUrl,
            topic = topic,
            instant = instant,
            mutedUntil = mutedUntil,
            minPriority = minPriority,
            autoDelete = autoDelete,
            insistent = insistent,
            lastNotificationId = lastNotificationId,
            icon = icon,
            upAppId = upAppId,
            upConnectorToken = upConnectorToken,
            displayName = displayName,
            dedicatedChannels = dedicatedChannels,
            totalCount = 0,
            newCount = 0,
            lastActive = 0,
            connectionDetails = ConnectionDetails()
        )
    }
}

fun Subscription.toWearSyncSubscription(): WearSyncSubscription {
    return WearSyncSubscription(
        id = id,
        baseUrl = baseUrl,
        topic = topic,
        instant = instant,
        mutedUntil = mutedUntil,
        minPriority = minPriority,
        autoDelete = autoDelete,
        insistent = insistent,
        lastNotificationId = lastNotificationId,
        icon = null,
        upAppId = upAppId,
        upConnectorToken = upConnectorToken,
        displayName = displayName,
        dedicatedChannels = dedicatedChannels
    )
}
