package io.heckel.ntfy.wear

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.Poller
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.ui.DetailViewModel
import io.heckel.ntfy.ui.DetailViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WearDetailActivity : AppCompatActivity() {
    private val repository by lazy { (application as Application).repository }
    private val api by lazy { ApiService(this) }
    private val poller by lazy { Poller(api, repository) }
    private val viewModel by viewModels<DetailViewModel> {
        DetailViewModelFactory(repository)
    }

    private lateinit var adapter: WearNotificationAdapter
    private lateinit var refreshLayout: SwipeRefreshLayout
    private var subscriptionId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_detail)

        subscriptionId = intent.getLongExtra(EXTRA_SUBSCRIPTION_ID, 0L)
        if (subscriptionId == 0L) {
            finish()
            return
        }

        val root = findViewById<android.view.View>(R.id.wear_detail_root)
        val toolbar = findViewById<MaterialToolbar>(R.id.wear_detail_toolbar)
        val titleView = findViewById<TextView>(R.id.wear_detail_title)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
        titleView.text = intent.getStringExtra(EXTRA_SUBSCRIPTION_TITLE) ?: getString(R.string.wear_main_title)

        refreshLayout = findViewById(R.id.wear_detail_refresh)
        refreshLayout.setColorSchemeColors(Colors.swipeToRefreshColor(this))
        refreshLayout.setOnRefreshListener {
            lifecycleScope.launch {
                refreshNotifications(showToast = true)
            }
        }

        val list = findViewById<RecyclerView>(R.id.wear_detail_list)
        val empty = findViewById<TextView>(R.id.wear_detail_empty)
        val horizontalPadding = resources.getDimensionPixelSize(
            if (resources.configuration.isScreenRound) R.dimen.wear_horizontal_padding_round
            else R.dimen.wear_horizontal_padding
        )
        val listBottomPadding = list.paddingBottom
        adapter = WearNotificationAdapter { notification ->
            val intent = Intent(this, WearNotificationActivity::class.java)
            intent.putExtra(WearNotificationActivity.EXTRA_NOTIFICATION_ID, notification.id)
            startActivity(intent)
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val roundTopPadding = resources.getDimensionPixelSize(
                if (resources.configuration.isScreenRound) R.dimen.wear_toolbar_top_padding_round else 0
            )
            view.updatePadding(
                left = horizontalPadding + bars.left,
                top = bars.top + roundTopPadding,
                right = horizontalPadding + bars.right,
                bottom = bars.bottom
            )
            list.updatePadding(bottom = listBottomPadding + bars.bottom)
            insets
        }

        viewModel.list(subscriptionId).observe(this) { notifications ->
            adapter.submitList(notifications.toMutableList())
            empty.isVisible = notifications.isEmpty()
            refreshLayout.isRefreshing = false
        }

        lifecycleScope.launch {
            refreshLayout.isRefreshing = true
            refreshNotifications(showToast = false)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private suspend fun refreshNotifications(showToast: Boolean) {
        try {
            val subscription = withContext(Dispatchers.IO) {
                repository.getSubscription(subscriptionId)
            } ?: return
            val newNotifications = withContext(Dispatchers.IO) {
                poller.poll(subscription)
            }
            if (showToast) {
                Toast.makeText(
                    this,
                    getString(R.string.wear_detail_refresh_done, newNotifications.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            if (showToast) {
                Toast.makeText(
                    this,
                    getString(R.string.wear_detail_refresh_failed, e.message ?: getString(R.string.wear_error_unknown)),
                    Toast.LENGTH_LONG
                ).show()
            }
        } finally {
            refreshLayout.isRefreshing = false
        }
    }

    companion object {
        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        const val EXTRA_SUBSCRIPTION_TITLE = "subscriptionTitle"
    }
}
