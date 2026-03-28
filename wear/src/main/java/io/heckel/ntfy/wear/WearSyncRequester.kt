package io.heckel.ntfy.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.wearsync.WearSyncManager

object WearSyncRequester {
    private const val TAG = "NtfyWearRequester"

    suspend fun requestSyncFromPhone(context: Context) {
        try {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            nodes.forEach { node ->
                Tasks.await(
                    Wearable.getMessageClient(context).sendMessage(
                        node.id,
                        WearSyncManager.REQUEST_SYNC_MESSAGE_PATH,
                        ByteArray(0)
                    )
                )
            }
            Log.d(TAG, "Requested sync from ${nodes.size} connected node(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to request sync from phone", e)
        }
    }
}
