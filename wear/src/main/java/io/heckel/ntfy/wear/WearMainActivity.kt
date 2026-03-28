package io.heckel.ntfy.wear

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
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
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.ui.SubscriptionsViewModel
import io.heckel.ntfy.ui.SubscriptionsViewModelFactory
import io.heckel.ntfy.util.displayName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WearMainActivity : AppCompatActivity() {
    private val repository by lazy { (application as Application).repository }
    private val viewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory(repository)
    }

    private lateinit var adapter: WearSubscriptionAdapter
    private lateinit var refreshLayout: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_main)

        val root = findViewById<LinearLayout>(R.id.wear_main_root)
        val toolbar = findViewById<MaterialToolbar>(R.id.wear_main_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.wear_main_title)

        refreshLayout = findViewById(R.id.wear_main_refresh)
        refreshLayout.setColorSchemeColors(Colors.swipeToRefreshColor(this))
        refreshLayout.setOnRefreshListener {
            requestSync()
        }

        val list = findViewById<RecyclerView>(R.id.wear_main_list)
        val empty = findViewById<android.view.View>(R.id.wear_main_empty)
        val horizontalPadding = resources.getDimensionPixelSize(
            if (resources.configuration.isScreenRound) R.dimen.wear_horizontal_padding_round
            else R.dimen.wear_horizontal_padding
        )
        val listBottomPadding = list.paddingBottom
        adapter = WearSubscriptionAdapter(getString(R.string.app_base_url)) { subscription ->
            openSubscription(subscription)
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = horizontalPadding + bars.left,
                top = bars.top,
                right = horizontalPadding + bars.right,
                bottom = bars.bottom
            )
            list.updatePadding(bottom = listBottomPadding + bars.bottom)
            insets
        }

        viewModel.list().observe(this) { subscriptions ->
            adapter.submitList(subscriptions.toMutableList())
            empty.isVisible = subscriptions.isEmpty()
            refreshLayout.isRefreshing = false
        }

        requestSync()
    }

    override fun onResume() {
        super.onResume()
        if (adapter.currentList.isEmpty()) {
            requestSync()
        }
    }

    private fun requestSync() {
        lifecycleScope.launch {
            refreshLayout.isRefreshing = true
            WearSyncRequester.requestSyncFromPhone(this@WearMainActivity)
            delay(1500)
            refreshLayout.isRefreshing = false
        }
    }

    private fun openSubscription(subscription: Subscription) {
        val intent = Intent(this, WearDetailActivity::class.java)
        intent.putExtra(WearDetailActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
        intent.putExtra(
            WearDetailActivity.EXTRA_SUBSCRIPTION_TITLE,
            displayName(getString(R.string.app_base_url), subscription)
        )
        startActivity(intent)
    }
}
