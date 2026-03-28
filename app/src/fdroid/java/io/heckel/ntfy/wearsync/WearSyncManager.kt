package io.heckel.ntfy.wearsync

import android.content.Context

object WearSyncManager {
    const val SNAPSHOT_DATA_PATH = "/ntfy/snapshot"
    const val REQUEST_SYNC_MESSAGE_PATH = "/ntfy/request-sync"
    const val DATA_KEY_JSON = "json"
    const val DATA_KEY_GENERATED_AT = "generatedAt"

    fun requestSnapshotSync(context: Context, debounceMillis: Long = 800L) {
        // Wear sync is only available in the Play flavor.
    }

    fun requestImmediateSnapshotSync(context: Context) {
        // Wear sync is only available in the Play flavor.
    }

    fun isWatchDevice(context: Context): Boolean = false
}
