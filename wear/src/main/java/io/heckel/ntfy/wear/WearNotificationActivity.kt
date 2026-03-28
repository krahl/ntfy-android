package io.heckel.ntfy.wear

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.stfalcon.imageviewer.StfalconImageViewer
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Attachment
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.isMarkdown
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.util.MarkwonFactory
import io.heckel.ntfy.util.copyToClipboard
import io.heckel.ntfy.util.decodeMessage
import io.heckel.ntfy.util.formatDateShort
import io.heckel.ntfy.util.supportedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.bettermovementmethod.BetterLinkMovementMethod
import androidx.core.net.toUri

class WearNotificationActivity : AppCompatActivity() {
    private val repository by lazy { (application as Application).repository }
    private val markwon by lazy { MarkwonFactory.createForMessage(this) }
    private var attachmentBitmap: android.graphics.Bitmap? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var previewImage: ImageView
    private lateinit var openImageButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_notification)

        val root = findViewById<LinearLayout>(R.id.wear_notification_root)
        val toolbar = findViewById<MaterialToolbar>(R.id.wear_notification_toolbar)
        val horizontalPadding = resources.getDimensionPixelSize(
            if (resources.configuration.isScreenRound) R.dimen.wear_horizontal_padding_round
            else R.dimen.wear_horizontal_padding
        )
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.wear_notification_title_fallback)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = horizontalPadding + bars.left,
                top = bars.top,
                right = horizontalPadding + bars.right,
                bottom = bars.bottom
            )
            insets
        }

        progressBar = findViewById(R.id.wear_notification_image_progress)
        previewImage = findViewById(R.id.wear_notification_image)
        openImageButton = findViewById(R.id.wear_notification_open_image)

        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
        if (notificationId.isNullOrBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val notification = withContext(Dispatchers.IO) {
                repository.getNotification(notificationId)
            } ?: run {
                finish()
                return@launch
            }
            bindNotification(notification)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindNotification(notification: Notification) {
        findViewById<TextView>(R.id.wear_notification_date).text = formatDateShort(notification.timestamp)

        val titleView = findViewById<TextView>(R.id.wear_notification_title_text)
        titleView.isVisible = notification.title.isNotBlank()
        titleView.text = notification.title

        val messageView = findViewById<TextView>(R.id.wear_notification_message)
        val message = decodeMessage(notification)
        if (notification.isMarkdown()) {
            markwon.setMarkdown(messageView, message)
        } else {
            messageView.text = message
        }
        messageView.movementMethod = BetterLinkMovementMethod.getInstance()

        val attachmentInfo = findViewById<TextView>(R.id.wear_notification_attachment)
        attachmentInfo.isVisible = notification.attachment != null
        attachmentInfo.text = notification.attachment?.let {
            getString(R.string.wear_notification_attachment, it.name)
        }

        val openLinkButton = findViewById<MaterialButton>(R.id.wear_notification_open_link)
        openLinkButton.isVisible = notification.click.isNotBlank()
        openLinkButton.setOnClickListener {
            openUrl(notification.click)
        }

        val actionsHeading = findViewById<TextView>(R.id.wear_notification_actions_heading)
        val actionContainer = findViewById<LinearLayout>(R.id.wear_notification_actions)
        actionContainer.removeAllViews()
        val actions = notification.actions.orEmpty()
        actionsHeading.isVisible = actions.isNotEmpty()
        actionContainer.isVisible = actions.isNotEmpty()
        actions.forEach { action ->
            val button = MaterialButton(this).apply {
                text = action.label
                setOnClickListener { runAction(action) }
            }
            actionContainer.addView(button)
        }

        val attachment = notification.attachment
        if (attachment != null && supportedImage(attachment.type)) {
            bindImageAttachment(attachment)
        } else {
            progressBar.isVisible = false
            previewImage.isVisible = false
            openImageButton.isVisible = false
        }
    }

    private fun bindImageAttachment(attachment: Attachment) {
        openImageButton.isVisible = true
        openImageButton.setOnClickListener {
            lifecycleScope.launch {
                openImage(attachment)
            }
        }
        previewImage.setOnClickListener {
            lifecycleScope.launch {
                openImage(attachment)
            }
        }
        lifecycleScope.launch {
            progressBar.isVisible = true
            try {
                attachmentBitmap = withContext(Dispatchers.IO) {
                    WearAttachmentLoader.loadBitmap(this@WearNotificationActivity, attachment)
                }
                previewImage.setImageBitmap(attachmentBitmap)
                previewImage.isVisible = true
            } catch (_: Exception) {
                previewImage.isVisible = false
            } finally {
                progressBar.isVisible = false
            }
        }
    }

    private suspend fun openImage(attachment: Attachment) {
        try {
            progressBar.isVisible = true
            val bitmap = attachmentBitmap ?: withContext(Dispatchers.IO) {
                WearAttachmentLoader.loadBitmap(this@WearNotificationActivity, attachment)
            }
            attachmentBitmap = bitmap
            StfalconImageViewer.Builder(this, listOf(bitmap)) { imageView, image ->
                imageView.setImageBitmap(image)
            }
                .allowZooming(true)
                .withHiddenStatusBar(false)
                .show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.wear_notification_open_image_failed, e.message ?: getString(R.string.wear_error_unknown)),
                Toast.LENGTH_LONG
            ).show()
        } finally {
            progressBar.isVisible = false
        }
    }

    private fun runAction(action: Action) {
        when (action.action) {
            NotificationService.ACTION_VIEW -> openUrl(action.url)
            NotificationService.ACTION_COPY -> {
                val value = action.value ?: return
                copyToClipboard(this, action.label, value)
            }
            else -> {
                Toast.makeText(this, R.string.wear_notification_action_unsupported, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openUrl(url: String?) {
        if (url.isNullOrBlank()) {
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: url, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }
}
