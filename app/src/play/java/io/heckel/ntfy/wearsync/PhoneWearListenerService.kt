package io.heckel.ntfy.wearsync

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.heckel.ntfy.util.Log

class PhoneWearListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearSyncManager.REQUEST_SYNC_MESSAGE_PATH) {
            super.onMessageReceived(messageEvent)
            return
        }
        Log.d(TAG, "Wear device requested a fresh subscription snapshot")
        WearSyncManager.requestImmediateSnapshotSync(applicationContext)
    }

    companion object {
        private const val TAG = "NtfyWearPhoneListener"
    }
}
