package io.heckel.ntfy.db

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.validUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class Repository(
    private val appContext: Context,
    private val sharedPrefs: SharedPreferences,
    database: Database
) {
    private val subscriptionDao = database.subscriptionDao()
    private val notificationDao = database.notificationDao()
    private val userDao = database.userDao()
    private val trustedCertificateDao = database.trustedCertificateDao()
    private val clientCertificateDao = database.clientCertificateDao()
    private val customHeaderDao = database.customHeaderDao()

    private val connectionDetails = ConcurrentHashMap<String, ConnectionDetails>()
    private val connectionDetailsLiveData = MutableLiveData<Map<String, ConnectionDetails>>(connectionDetails)
    private val connectionForceReconnectVersions = ConcurrentHashMap<String, Long>() // Base URL -> Number of times "Retry" was pressed

    // TODO Move these into an ApplicationState singleton
    val detailViewSubscriptionId = AtomicLong(0L) // Omg, what a hack ...
    val mediaPlayer = MediaPlayer()

    init {
        Log.d(TAG, "Created $this")
    }

    fun getSubscriptionsLiveData(): LiveData<List<Subscription>> {
        return subscriptionDao
            .listFlow()
            .asLiveData()
            .combineWith(connectionDetailsLiveData) { subscriptionsWithMetadata, _ ->
                toSubscriptionList(subscriptionsWithMetadata.orEmpty())
            }
    }

    fun getSubscriptionIdsWithInstantStatusLiveData(): LiveData<Set<Pair<Long, Boolean>>> {
        return subscriptionDao
            .listFlow()
            .asLiveData()
            .map { list -> list.map { Pair(it.id, it.instant) }.toSet() }
    }

    suspend fun getSubscriptions(): List<Subscription> {
        return toSubscriptionList(subscriptionDao.list())
    }

    suspend fun getSubscriptionIdsWithInstantStatus(): Set<Pair<Long, Boolean>> {
        return subscriptionDao
            .list()
            .map { Pair(it.id, it.instant) }.toSet()
    }

    fun getSubscription(subscriptionId: Long): Subscription? {
        return toSubscription(subscriptionDao.get(subscriptionId))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscription(baseUrl: String, topic: String): Subscription? {
        return toSubscription(subscriptionDao.get(baseUrl, topic))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscriptionByConnectorToken(connectorToken: String): Subscription? {
        return toSubscription(subscriptionDao.getByConnectorToken(connectorToken))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addSubscription(subscription: Subscription) {
        subscriptionDao.add(subscription)
        requestWearSync()
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun updateSubscription(subscription: Subscription) {
        subscriptionDao.update(subscription)
        requestWearSync()
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun removeSubscription(subscription: Subscription) {
        notificationDao.removeAll(subscription.id)
        subscriptionDao.remove(subscription.id)
        updateConnectionDetails(subscription.baseUrl, ConnectionState.NOT_APPLICABLE)
        requestWearSync()
    }

    suspend fun getNotifications(): List<Notification> {
        return notificationDao.list()
    }

    fun getDeletedNotificationsWithAttachments(): List<Notification> {
        return notificationDao.listDeletedWithAttachments()
    }

    fun getActiveIconUris(): Set<String> {
        return notificationDao.listActiveIconUris().toSet()
    }

    fun clearIconUri(uri: String) {
        notificationDao.clearIconUri(uri)
    }

    fun getNotificationsLiveData(subscriptionId: Long): LiveData<List<Notification>> {
        return notificationDao.listFlow(subscriptionId).asLiveData()
    }

    fun getNotificationsFilteredLiveData(subscriptionId: Long, query: String): LiveData<List<Notification>> {
        return notificationDao.listFlowFiltered(subscriptionId, query).asLiveData()
    }

    fun getNotification(notificationId: String): Notification? {
        return notificationDao.get(notificationId)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addNotification(notification: Notification): Boolean {
        val maybeExistingNotification = notificationDao.get(notification.id)
        if (maybeExistingNotification != null || notification.event != ApiService.EVENT_MESSAGE) {
            return false
        }
        // Mark old notifications with the same sequence ID as deleted (this is an update to an existing sequence)
        if (notification.sequenceId.isNotEmpty()) {
            notificationDao.markAsDeletedBySequenceId(notification.subscriptionId, notification.sequenceId)
        }
        subscriptionDao.updateLastNotificationId(notification.subscriptionId, notification.id)
        notificationDao.add(notification)
        requestWearSync()
        return true
    }

    fun updateNotification(notification: Notification) {
        notificationDao.update(notification)
        requestWearSync()
    }

    fun undeleteNotification(notificationId: String) {
        notificationDao.undelete(notificationId)
        requestWearSync()
    }

    fun markAsDeleted(notificationId: String) {
        notificationDao.markAsDeleted(notificationId)
        requestWearSync()
    }

    fun markAsDeletedBySequenceId(subscriptionId: Long, sequenceId: String) {
        notificationDao.markAsDeletedBySequenceId(subscriptionId, sequenceId)
        requestWearSync()
    }

    fun updateLastNotificationId(subscriptionId: Long, notificationId: String) {
        subscriptionDao.updateLastNotificationId(subscriptionId, notificationId)
        requestWearSync()
    }

    /**
     * Returns the lastNotificationId to use as "since" parameter for subscribing.
     * Selects the lastNotificationId from the subscription with the most recent notification.
     * Returns null if no subscriptions have a lastNotificationId.
     */
    fun getLastNotificationId(subscriptionIds: Collection<Long>): String? {
        return subscriptionDao.getLastNotificationId(subscriptionIds)
    }

    fun markAllAsDeleted(subscriptionId: Long) {
        notificationDao.markAllAsDeleted(subscriptionId)
        requestWearSync()
    }

    fun markAllAsRead(subscriptionId: Long) {
        notificationDao.markAllAsRead(subscriptionId)
        requestWearSync()
    }

    fun markAsReadBySequenceId(subscriptionId: Long, sequenceId: String) {
        notificationDao.markAsReadBySequenceId(subscriptionId, sequenceId)
        requestWearSync()
    }

    fun markAsDeletedIfOlderThan(subscriptionId: Long, olderThanTimestamp: Long) {
        notificationDao.markAsDeletedIfOlderThan(subscriptionId, olderThanTimestamp)
        requestWearSync()
    }

    fun removeNotificationsIfOlderThan(subscriptionId: Long, olderThanTimestamp: Long) {
        notificationDao.removeIfOlderThan(subscriptionId, olderThanTimestamp)
        requestWearSync()
    }

    suspend fun getUsers(): List<User> {
        return userDao.list()
    }

    fun getUsersLiveData(): LiveData<List<User>> {
        return userDao.listFlow().asLiveData()
    }

    suspend fun addUser(user: User) {
        userDao.insert(user)
        requestWearSync()
    }

    suspend fun updateUser(user: User) {
        userDao.update(user)
        requestWearSync()
    }

    suspend fun getUser(baseUrl: String): User? {
        return userDao.get(baseUrl)
    }

    suspend fun deleteUser(baseUrl: String) {
        userDao.delete(baseUrl)
        requestWearSync()
    }

    // Trusted certificates

    suspend fun getTrustedCertificates(): List<TrustedCertificate> {
        return trustedCertificateDao.list()
    }

    suspend fun getTrustedCertificate(baseUrl: String): TrustedCertificate? {
        return trustedCertificateDao.get(baseUrl)
    }

    suspend fun addTrustedCertificate(baseUrl: String, pem: String) {
        trustedCertificateDao.insert(TrustedCertificate(baseUrl, pem))
        requestWearSync()
    }

    suspend fun removeTrustedCertificate(baseUrl: String) {
        trustedCertificateDao.delete(baseUrl)
        requestWearSync()
    }

    // Client certificates

    suspend fun getClientCertificates(): List<ClientCertificate> {
        return clientCertificateDao.list()
    }

    suspend fun getClientCertificate(baseUrl: String): ClientCertificate? {
        return clientCertificateDao.get(baseUrl)
    }

    suspend fun addClientCertificate(baseUrl: String, p12Base64: String, password: String) {
        clientCertificateDao.insert(ClientCertificate(baseUrl, p12Base64, password))
        requestWearSync()
    }

    suspend fun removeClientCertificate(baseUrl: String) {
        clientCertificateDao.delete(baseUrl)
        requestWearSync()
    }

    fun getPollWorkerVersion(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_POLL_WORKER_VERSION, 0)
    }

    fun setPollWorkerVersion(version: Int) {
        sharedPrefs.edit {
            putInt(SHARED_PREFS_POLL_WORKER_VERSION, version)
        }
    }

    fun getDeleteWorkerVersion(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_DELETE_WORKER_VERSION, 0)
    }

    fun setDeleteWorkerVersion(version: Int) {
        sharedPrefs.edit {
            putInt(SHARED_PREFS_DELETE_WORKER_VERSION, version)
        }
    }

    fun getAutoRestartWorkerVersion(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_AUTO_RESTART_WORKER_VERSION, 0)
    }

    fun setAutoRestartWorkerVersion(version: Int) {
        sharedPrefs.edit {
            putInt(SHARED_PREFS_AUTO_RESTART_WORKER_VERSION, version)
        }
    }

    fun setMinPriority(minPriority: Int) {
        if (minPriority <= MIN_PRIORITY_ANY) {
            sharedPrefs.edit {
                remove(SHARED_PREFS_MIN_PRIORITY)
            }
        } else {
            sharedPrefs.edit {
                putInt(SHARED_PREFS_MIN_PRIORITY, minPriority)
            }
        }
    }

    fun getMinPriority(): Int {
        return sharedPrefs.getInt(SHARED_PREFS_MIN_PRIORITY, MIN_PRIORITY_ANY)
    }

    fun getAutoDownloadMaxSize(): Long {
        val defaultValue = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            AUTO_DOWNLOAD_NEVER // Need to request permission on older versions
        } else {
            AUTO_DOWNLOAD_DEFAULT
        }
        return sharedPrefs.getLong(SHARED_PREFS_AUTO_DOWNLOAD_MAX_SIZE, defaultValue)
    }

    fun setAutoDownloadMaxSize(maxSize: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_AUTO_DOWNLOAD_MAX_SIZE, maxSize)
        }
    }

    fun getAutoDeleteSeconds(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_AUTO_DELETE_SECONDS, AUTO_DELETE_DEFAULT_SECONDS)
    }

    fun setAutoDeleteSeconds(seconds: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_AUTO_DELETE_SECONDS, seconds)
        }
    }

    fun setDarkMode(mode: Int) {
        if (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            sharedPrefs.edit {
                remove(SHARED_PREFS_DARK_MODE)
            }
        } else {
            sharedPrefs.edit {
                putInt(SHARED_PREFS_DARK_MODE, mode)
            }
        }
        requestWearSync()
    }

    fun getDarkMode(): Int {
        val defaultMode = if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        return sharedPrefs.getInt(SHARED_PREFS_DARK_MODE, defaultMode)
    }

    fun setDynamicColorsEnabled(enabled: Boolean) {
        sharedPrefs.edit(commit = true) {
            putBoolean(SHARED_PREFS_DYNAMIC_COLORS, enabled)
        }
        requestWearSync()
    }

    fun getDynamicColorsEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_DYNAMIC_COLORS, false)
    }

    fun setConnectionProtocol(connectionProtocol: String) {
        sharedPrefs.edit {
            putString(SHARED_PREFS_CONNECTION_PROTOCOL, connectionProtocol)
        }
    }

    fun getConnectionProtocol(): String {
        return sharedPrefs.getString(SHARED_PREFS_CONNECTION_PROTOCOL, null) ?: CONNECTION_PROTOCOL_JSONHTTP
    }

    fun getBroadcastEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_BROADCAST_ENABLED, true) // Enabled by default
    }

    fun setBroadcastEnabled(enabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(SHARED_PREFS_BROADCAST_ENABLED, enabled)
        }
    }

    fun getUnifiedPushEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_UNIFIEDPUSH_ENABLED, true) // Enabled by default
    }

    fun setUnifiedPushEnabled(enabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(SHARED_PREFS_UNIFIEDPUSH_ENABLED, enabled)
        }
    }

    fun getInsistentMaxPriorityEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_INSISTENT_MAX_PRIORITY_ENABLED, false) // Disabled by default
    }

    fun setInsistentMaxPriorityEnabled(enabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(SHARED_PREFS_INSISTENT_MAX_PRIORITY_ENABLED, enabled)
        }
    }

    fun getRecordLogs(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_RECORD_LOGS_ENABLED, false) // Disabled by default
    }

    fun setRecordLogsEnabled(enabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(SHARED_PREFS_RECORD_LOGS_ENABLED, enabled)
        }
    }

    fun getMessageBarEnabled(): Boolean {
        return sharedPrefs.getBoolean(SHARED_PREFS_MESSAGE_BAR_ENABLED, true) // Enabled by default
    }

    fun setMessageBarEnabled(enabled: Boolean) {
        sharedPrefs.edit {
            putBoolean(SHARED_PREFS_MESSAGE_BAR_ENABLED, enabled)
        }
    }

    fun getBatteryOptimizationsRemindTime(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_BATTERY_OPTIMIZATIONS_REMIND_TIME, BATTERY_OPTIMIZATIONS_REMIND_TIME_ALWAYS)
    }

    fun setBatteryOptimizationsRemindTime(timeMillis: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_BATTERY_OPTIMIZATIONS_REMIND_TIME, timeMillis)
        }
    }

    fun getWebSocketRemindTime(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_WEBSOCKET_REMIND_TIME, WEBSOCKET_REMIND_TIME_ALWAYS)
    }

    fun setWebSocketRemindTime(timeMillis: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_WEBSOCKET_REMIND_TIME, timeMillis)
        }
    }

    fun getWebSocketReconnectRemindTime(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_WEBSOCKET_RECONNECT_REMIND_TIME, WEBSOCKET_RECONNECT_REMIND_TIME_ALWAYS)
    }

    fun setWebSocketReconnectRemindTime(timeMillis: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_WEBSOCKET_RECONNECT_REMIND_TIME, timeMillis)
        }
    }

    fun getConnectionAlertSnoozeUntil(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_CONNECTION_ALERT_SNOOZE_UNTIL, CONNECTION_ALERT_SNOOZE_UNTIL_DEFAULT)
    }

    fun setConnectionAlertSnoozeUntil(timeMillis: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_CONNECTION_ALERT_SNOOZE_UNTIL, timeMillis)
        }
    }

    fun getDefaultBaseUrl(): String? {
        return sharedPrefs.getString(SHARED_PREFS_DEFAULT_BASE_URL, null) ?:
            sharedPrefs.getString(SHARED_PREFS_UNIFIED_PUSH_BASE_URL, null) // Fall back to UP URL, removed when default is set!
    }

    fun setDefaultBaseUrl(baseUrl: String) {
        if (baseUrl == "") {
            sharedPrefs.edit {
                remove(SHARED_PREFS_UNIFIED_PUSH_BASE_URL) // Remove legacy key
                    .remove(SHARED_PREFS_DEFAULT_BASE_URL)
            }
        } else {
            sharedPrefs.edit {
                remove(SHARED_PREFS_UNIFIED_PUSH_BASE_URL) // Remove legacy key
                    .putString(SHARED_PREFS_DEFAULT_BASE_URL, baseUrl)
            }
        }
        requestWearSync()
    }

    suspend fun getCustomHeaders(): List<CustomHeader> {
        return customHeaderDao.list()
    }

    suspend fun getCustomHeaders(baseUrl: String): List<CustomHeader> {
        return customHeaderDao.get(baseUrl)
    }

    suspend fun addCustomHeader(header: CustomHeader) {
        customHeaderDao.insert(header)
        requestWearSync()
    }

    suspend fun updateCustomHeader(oldHeader: CustomHeader, newHeader: CustomHeader) {
        customHeaderDao.delete(oldHeader.baseUrl, oldHeader.name)
        customHeaderDao.insert(newHeader)
        requestWearSync()
    }

    suspend fun deleteCustomHeader(header: CustomHeader) {
        customHeaderDao.delete(header.baseUrl, header.name)
        requestWearSync()
    }

    fun isGlobalMuted(): Boolean {
        val mutedUntil = getGlobalMutedUntil()
        return mutedUntil == 1L || (mutedUntil > 1L && mutedUntil > System.currentTimeMillis()/1000)
    }

    fun getGlobalMutedUntil(): Long {
        return sharedPrefs.getLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, 0L)
    }

    fun setGlobalMutedUntil(mutedUntilTimestamp: Long) {
        sharedPrefs.edit {
            putLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, mutedUntilTimestamp)
        }
    }

    fun checkGlobalMutedUntil(): Boolean {
        val mutedUntil = sharedPrefs.getLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, 0L)
        val expired = mutedUntil > 1L && System.currentTimeMillis()/1000 > mutedUntil
        if (expired) {
            sharedPrefs.edit {
                putLong(SHARED_PREFS_MUTED_UNTIL_TIMESTAMP, 0L)
            }
            return true
        }
        return false
    }

    fun getLastShareTopics(): List<String> {
        val topics = sharedPrefs.getString(SHARED_PREFS_LAST_TOPICS, "") ?: ""
        return topics.split("\n").filter { validUrl(it) }
    }

    fun addLastShareTopic(topic: String) {
        val topics = (getLastShareTopics().filterNot { it == topic } + topic).takeLast(LAST_TOPICS_COUNT)
        sharedPrefs.edit {
            putString(SHARED_PREFS_LAST_TOPICS, topics.joinToString(separator = "\n"))
        }
    }

    private fun toSubscriptionList(list: List<SubscriptionWithMetadata>): List<Subscription> {
        return list.map { s ->
            Subscription(
                id = s.id,
                baseUrl = s.baseUrl,
                topic = s.topic,
                instant = s.instant,
                dedicatedChannels = s.dedicatedChannels,
                mutedUntil = s.mutedUntil,
                minPriority = s.minPriority,
                autoDelete = s.autoDelete,
                insistent = s.insistent,
                lastNotificationId = s.lastNotificationId,
                icon = s.icon,
                upAppId = s.upAppId,
                upConnectorToken = s.upConnectorToken,
                displayName = s.displayName,
                totalCount = s.totalCount,
                newCount = s.newCount,
                lastActive = s.lastActive,
                connectionDetails = connectionDetails[s.baseUrl] ?: ConnectionDetails()
            )
        }
    }

    private fun toSubscription(s: SubscriptionWithMetadata?): Subscription? {
        if (s == null) {
            return null
        }
        return Subscription(
            id = s.id,
            baseUrl = s.baseUrl,
            topic = s.topic,
            instant = s.instant,
            dedicatedChannels = s.dedicatedChannels,
            mutedUntil = s.mutedUntil,
            minPriority = s.minPriority,
            autoDelete = s.autoDelete,
            insistent = s.insistent,
            lastNotificationId = s.lastNotificationId,
            icon = s.icon,
            upAppId = s.upAppId,
            upConnectorToken = s.upConnectorToken,
            displayName = s.displayName,
            totalCount = s.totalCount,
            newCount = s.newCount,
            lastActive = s.lastActive,
            connectionDetails = connectionDetails[s.baseUrl] ?: ConnectionDetails()
        )
    }

    fun updateConnectionDetails(baseUrl: String, state: ConnectionState, error: Throwable? = null, nextRetryTime: Long = 0L) {
        val current = connectionDetails[baseUrl]
        val firstErrorTime = when {
            error == null -> 0L
            state == ConnectionState.CONNECTED -> 0L
            current?.firstErrorTime != null && current.firstErrorTime > 0L -> current.firstErrorTime
            else -> System.currentTimeMillis()
        }
        val details = ConnectionDetails(state, error, nextRetryTime, firstErrorTime)
        if (current != details) {
            if (state == ConnectionState.NOT_APPLICABLE && error == null) {
                connectionDetails.remove(baseUrl)
            } else {
                connectionDetails[baseUrl] = details
            }
            connectionDetailsLiveData.postValue(connectionDetails.toMap())
            Log.d(TAG, "Connection details updated for $baseUrl: state=$state, error=${error?.message}, nextRetry=$nextRetryTime")
        }
    }

    fun getConnectionDetailsLiveData(): LiveData<Map<String, ConnectionDetails>> {
        return connectionDetailsLiveData
    }

    fun getConnectionDetails(): Map<String, ConnectionDetails> {
        return connectionDetails.toMap()
    }

    fun getConnectionForceReconnectVersion(baseUrl: String): Long {
        return connectionForceReconnectVersions[baseUrl] ?: 0L
    }

    fun incrementConnectionForceReconnectVersion(baseUrl: String) {
        connectionForceReconnectVersions.compute(baseUrl) { _, current -> (current ?: 0L) + 1 }
        Log.d(TAG, "Connection force reconnect version incremented for $baseUrl: ${connectionForceReconnectVersions[baseUrl]}")
    }

    private fun requestWearSync() {
        io.heckel.ntfy.wearsync.WearSyncManager.requestSnapshotSync(appContext)
    }

    companion object {
        const val SHARED_PREFS_ID = "MainPreferences"
        const val SHARED_PREFS_POLL_WORKER_VERSION = "PollWorkerVersion"
        const val SHARED_PREFS_DELETE_WORKER_VERSION = "DeleteWorkerVersion"
        const val SHARED_PREFS_AUTO_RESTART_WORKER_VERSION = "AutoRestartWorkerVersion"
        const val SHARED_PREFS_MUTED_UNTIL_TIMESTAMP = "MutedUntil"
        const val SHARED_PREFS_MIN_PRIORITY = "MinPriority"
        const val SHARED_PREFS_AUTO_DOWNLOAD_MAX_SIZE = "AutoDownload"
        const val SHARED_PREFS_AUTO_DELETE_SECONDS = "AutoDelete"
        const val SHARED_PREFS_CONNECTION_PROTOCOL = "ConnectionProtocol"
        const val SHARED_PREFS_DARK_MODE = "DarkMode"
        const val SHARED_PREFS_DYNAMIC_COLORS = "DynamicColors"
        const val SHARED_PREFS_BROADCAST_ENABLED = "BroadcastEnabled"
        const val SHARED_PREFS_UNIFIEDPUSH_ENABLED = "UnifiedPushEnabled"
        const val SHARED_PREFS_INSISTENT_MAX_PRIORITY_ENABLED = "InsistentMaxPriority"
        const val SHARED_PREFS_RECORD_LOGS_ENABLED = "RecordLogs"
        const val SHARED_PREFS_MESSAGE_BAR_ENABLED = "MessageBarEnabled"
        const val SHARED_PREFS_BATTERY_OPTIMIZATIONS_REMIND_TIME = "BatteryOptimizationsRemindTime"
        const val SHARED_PREFS_WEBSOCKET_REMIND_TIME = "JsonStreamRemindTime" // "Use WebSocket" banner (used to be JSON stream deprecation banner)
        const val SHARED_PREFS_WEBSOCKET_RECONNECT_REMIND_TIME = "WebSocketReconnectRemindTime"
        const val SHARED_PREFS_UNIFIED_PUSH_BASE_URL = "UnifiedPushBaseURL" // Legacy key required for migration to DefaultBaseURL
        const val SHARED_PREFS_DEFAULT_BASE_URL = "DefaultBaseURL"
        const val SHARED_PREFS_CONNECTION_ALERT_SNOOZE_UNTIL = "ConnectionAlertSnoozeUntil"
        const val CONNECTION_ALERT_SNOOZE_UNTIL_DEFAULT = 0L
        const val CONNECTION_ALERT_NEVER_SHOW = Long.MAX_VALUE
        const val SHARED_PREFS_LAST_TOPICS = "LastTopics"

        private const val LAST_TOPICS_COUNT = 3

        const val MIN_PRIORITY_USE_GLOBAL = 0
        const val MIN_PRIORITY_ANY = 1

        const val MUTED_UNTIL_SHOW_ALL = 0L
        const val MUTED_UNTIL_FOREVER = 1L
        const val MUTED_UNTIL_TOMORROW = 2L

        private const val ONE_MB = 1024 * 1024L
        const val AUTO_DOWNLOAD_NEVER = 0L // Values must match values.xml
        const val AUTO_DOWNLOAD_ALWAYS = 1L
        const val AUTO_DOWNLOAD_DEFAULT = ONE_MB

        private const val ONE_DAY_SECONDS = 24 * 60 * 60L
        const val AUTO_DELETE_USE_GLOBAL = -1L // Values must match values.xml
        const val AUTO_DELETE_NEVER = 0L
        const val AUTO_DELETE_ONE_DAY_SECONDS = ONE_DAY_SECONDS
        const val AUTO_DELETE_THREE_DAYS_SECONDS = 3 * ONE_DAY_SECONDS
        const val AUTO_DELETE_ONE_WEEK_SECONDS = 7 * ONE_DAY_SECONDS
        const val AUTO_DELETE_ONE_MONTH_SECONDS = 30 * ONE_DAY_SECONDS
        const val AUTO_DELETE_THREE_MONTHS_SECONDS = 90 * ONE_DAY_SECONDS
        const val AUTO_DELETE_DEFAULT_SECONDS = AUTO_DELETE_ONE_MONTH_SECONDS

        const val INSISTENT_MAX_PRIORITY_USE_GLOBAL = -1 // Values must match values.xml
        const val INSISTENT_MAX_PRIORITY_ENABLED = 1 // 0 = Disabled (but not needed in code)

        const val CONNECTION_PROTOCOL_JSONHTTP = "jsonhttp"
        const val CONNECTION_PROTOCOL_WS = "ws"

        const val BATTERY_OPTIMIZATIONS_REMIND_TIME_ALWAYS = 1L
        const val BATTERY_OPTIMIZATIONS_REMIND_TIME_NEVER = Long.MAX_VALUE

        const val WEBSOCKET_REMIND_TIME_ALWAYS = 1L
        const val WEBSOCKET_REMIND_TIME_NEVER = Long.MAX_VALUE

        const val WEBSOCKET_RECONNECT_REMIND_TIME_ALWAYS = 1L
        const val WEBSOCKET_RECONNECT_REMIND_TIME_NEVER = Long.MAX_VALUE

        private const val TAG = "NtfyRepository"
        private var instance: Repository? = null

        fun getInstance(context: Context): Repository {
            val appContext = context.applicationContext
            val database = Database.getInstance(appContext)
            val sharedPrefs = appContext.getSharedPreferences(SHARED_PREFS_ID, Context.MODE_PRIVATE)
            return getInstance(appContext, sharedPrefs, database)
        }

        private fun getInstance(context: Context, sharedPrefs: SharedPreferences, database: Database): Repository {
            return synchronized(Repository::class) {
                val newInstance = instance ?: Repository(context, sharedPrefs, database)
                instance = newInstance
                newInstance
            }
        }
    }
}

/* https://stackoverflow.com/a/57079290/1440785 */
fun <T, K, R> LiveData<T>.combineWith(
    liveData: LiveData<K>,
    block: (T?, K?) -> R
): LiveData<R> {
    val result = MediatorLiveData<R>()
    result.addSource(this) {
        result.value = block(this.value, liveData.value)
    }
    result.addSource(liveData) {
        result.value = block(this.value, liveData.value)
    }
    return result
}
