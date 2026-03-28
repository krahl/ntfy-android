package io.heckel.ntfy.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.heckel.ntfy.db.Attachment
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.extractBaseUrl
import io.heckel.ntfy.util.readBitmapFromUriOrNull

object WearAttachmentLoader {
    suspend fun loadBitmap(context: Context, attachment: Attachment): Bitmap {
        attachment.contentUri?.readBitmapFromUriOrNull(context)?.let { return it }

        val baseUrl = extractBaseUrl(attachment.url)
        val repository = Repository.getInstance(context)
        val user = repository.getUser(baseUrl)
        val customHeaders = repository.getCustomHeaders(baseUrl)
        val request = HttpUtil.requestBuilder(attachment.url, user, customHeaders).build()
        HttpUtil.longCallClient(context, baseUrl).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected response ${response.code}")
            }
            return BitmapFactory.decodeStream(response.body.byteStream())
                ?: throw Exception("Unable to decode image")
        }
    }
}
