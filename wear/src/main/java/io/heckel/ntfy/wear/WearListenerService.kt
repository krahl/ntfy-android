package io.heckel.ntfy.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.wearsync.WearSyncManager
import io.heckel.ntfy.wearsync.WearSyncSnapshot
import io.heckel.ntfy.wearsync.WearSyncSnapshotApplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WearListenerService : WearableListenerService() {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) {
                return@forEach
            }
            if (event.dataItem.uri.path != WearSyncManager.SNAPSHOT_DATA_PATH) {
                return@forEach
            }
            val json = DataMapItem.fromDataItem(event.dataItem)
                .dataMap
                .getString(WearSyncManager.DATA_KEY_JSON)
                ?: return@forEach
            scope.launch {
                try {
                    val snapshot = gson.fromJson(json, WearSyncSnapshot::class.java)
                    WearSyncSnapshotApplier.apply(applicationContext, snapshot)
                    Log.d(TAG, "Applied Wear snapshot generated at ${snapshot.generatedAt}")
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to apply Wear snapshot", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "NtfyWearListener"
    }
}
