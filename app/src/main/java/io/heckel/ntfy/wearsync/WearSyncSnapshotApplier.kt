package io.heckel.ntfy.wearsync

import android.content.Context
import io.heckel.ntfy.db.ClientCertificate
import io.heckel.ntfy.db.CustomHeader
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.TrustedCertificate
import io.heckel.ntfy.db.User

object WearSyncSnapshotApplier {
    suspend fun apply(context: Context, snapshot: WearSyncSnapshot) {
        val repository = Repository.getInstance(context.applicationContext)

        syncSubscriptions(repository, snapshot.subscriptions.map { it.toSubscription() })
        syncUsers(repository, snapshot.users)
        syncCustomHeaders(repository, snapshot.customHeaders)
        syncTrustedCertificates(repository, snapshot.trustedCertificates)
        syncClientCertificates(repository, snapshot.clientCertificates)

        repository.setDefaultBaseUrl(snapshot.prefs.defaultBaseUrl.orEmpty())
        repository.setDarkMode(snapshot.prefs.darkMode)
        repository.setDynamicColorsEnabled(snapshot.prefs.dynamicColorsEnabled)
    }

    private suspend fun syncSubscriptions(repository: Repository, incoming: List<Subscription>) {
        val existing = repository.getSubscriptions().associateBy { it.id }
        val incomingIds = incoming.map { it.id }.toSet()

        incoming.forEach { subscription ->
            val current = existing[subscription.id]
            if (current == null) {
                repository.addSubscription(subscription)
            } else if (!sameSubscription(current, subscription)) {
                repository.updateSubscription(subscription)
            }
        }

        existing.values
            .filter { it.id !in incomingIds }
            .forEach { repository.removeSubscription(it) }
    }

    private suspend fun syncUsers(repository: Repository, incoming: List<User>) {
        val existing = repository.getUsers().associateBy { it.baseUrl }
        val incomingBaseUrls = incoming.map { it.baseUrl }.toSet()

        incoming.forEach { user ->
            val current = existing[user.baseUrl]
            if (current == null) {
                repository.addUser(user)
            } else if (current != user) {
                repository.updateUser(user)
            }
        }

        existing.keys
            .filter { it !in incomingBaseUrls }
            .forEach { repository.deleteUser(it) }
    }

    private suspend fun syncCustomHeaders(repository: Repository, incoming: List<CustomHeader>) {
        val existing = repository.getCustomHeaders().associateBy { it.baseUrl to it.name }
        val incomingKeys = incoming.map { it.baseUrl to it.name }.toSet()

        incoming.forEach { header ->
            val key = header.baseUrl to header.name
            val current = existing[key]
            if (current == null) {
                repository.addCustomHeader(header)
            } else if (current != header) {
                repository.updateCustomHeader(current, header)
            }
        }

        existing.values
            .filter { (it.baseUrl to it.name) !in incomingKeys }
            .forEach { repository.deleteCustomHeader(it) }
    }

    private suspend fun syncTrustedCertificates(repository: Repository, incoming: List<TrustedCertificate>) {
        val existing = repository.getTrustedCertificates().associateBy { it.baseUrl }
        val incomingBaseUrls = incoming.map { it.baseUrl }.toSet()

        incoming.forEach { certificate ->
            val current = existing[certificate.baseUrl]
            if (current == null || current != certificate) {
                repository.addTrustedCertificate(certificate.baseUrl, certificate.pem)
            }
        }

        existing.keys
            .filter { it !in incomingBaseUrls }
            .forEach { repository.removeTrustedCertificate(it) }
    }

    private suspend fun syncClientCertificates(repository: Repository, incoming: List<ClientCertificate>) {
        val existing = repository.getClientCertificates().associateBy { it.baseUrl }
        val incomingBaseUrls = incoming.map { it.baseUrl }.toSet()

        incoming.forEach { certificate ->
            val current = existing[certificate.baseUrl]
            if (current == null || current != certificate) {
                repository.addClientCertificate(certificate.baseUrl, certificate.p12Base64, certificate.password)
            }
        }

        existing.keys
            .filter { it !in incomingBaseUrls }
            .forEach { repository.removeClientCertificate(it) }
    }

    private fun sameSubscription(current: Subscription, incoming: Subscription): Boolean {
        return current.id == incoming.id &&
            current.baseUrl == incoming.baseUrl &&
            current.topic == incoming.topic &&
            current.instant == incoming.instant &&
            current.mutedUntil == incoming.mutedUntil &&
            current.minPriority == incoming.minPriority &&
            current.autoDelete == incoming.autoDelete &&
            current.insistent == incoming.insistent &&
            current.lastNotificationId == incoming.lastNotificationId &&
            current.icon == incoming.icon &&
            current.upAppId == incoming.upAppId &&
            current.upConnectorToken == incoming.upConnectorToken &&
            current.displayName == incoming.displayName &&
            current.dedicatedChannels == incoming.dedicatedChannels
    }
}
