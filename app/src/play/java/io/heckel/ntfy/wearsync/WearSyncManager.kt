package io.heckel.ntfy.wearsync

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object WearSyncManager {
    private const val TAG = "NtfyWearSync"
    const val SNAPSHOT_DATA_PATH = "/ntfy/snapshot"
    const val REQUEST_SYNC_MESSAGE_PATH = "/ntfy/request-sync"
    const val DATA_KEY_JSON = "json"
    const val DATA_KEY_GENERATED_AT = "generatedAt"

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSyncJob: Job? = null

    fun requestSnapshotSync(context: Context, debounceMillis: Long = 800L) {
        val appContext = context.applicationContext
        if (isWatchDevice(appContext)) {
            return
        }
        synchronized(this) {
            pendingSyncJob?.cancel()
            pendingSyncJob = scope.launch {
                delay(debounceMillis)
                pushSnapshot(appContext)
            }
        }
    }

    fun requestImmediateSnapshotSync(context: Context) {
        val appContext = context.applicationContext
        if (isWatchDevice(appContext)) {
            return
        }
        scope.launch {
            pushSnapshot(appContext)
        }
    }

    private suspend fun pushSnapshot(context: Context) {
        try {
            val repository = Repository.getInstance(context)
            val snapshot = WearSyncSnapshot(
                generatedAt = System.currentTimeMillis(),
                subscriptions = repository.getSubscriptions().map { it.toWearSyncSubscription() },
                notifications = repository.getNotifications(),
                users = repository.getUsers(),
                customHeaders = repository.getCustomHeaders(),
                trustedCertificates = repository.getTrustedCertificates(),
                clientCertificates = repository.getClientCertificates(),
                prefs = WearSyncPrefs(
                    defaultBaseUrl = repository.getDefaultBaseUrl(),
                    darkMode = repository.getDarkMode(),
                    dynamicColorsEnabled = repository.getDynamicColorsEnabled()
                )
            )
            val request = PutDataMapRequest.create(SNAPSHOT_DATA_PATH).apply {
                dataMap.putLong(DATA_KEY_GENERATED_AT, snapshot.generatedAt)
                dataMap.putString(DATA_KEY_JSON, gson.toJson(snapshot))
            }
            Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()))
            Log.d(TAG, "Pushed Wear snapshot with ${snapshot.subscriptions.size} subscription(s) and ${snapshot.notifications.size} notification(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to push Wear snapshot", e)
        }
    }

    fun isWatchDevice(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    }
}
