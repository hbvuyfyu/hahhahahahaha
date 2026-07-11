package com.vcam.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vcam.R
import com.vcam.utils.ApiClient
import com.vcam.utils.SessionManager
import kotlinx.coroutines.launch

class SubscriptionActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var layoutPlans: LinearLayout
    private lateinit var layoutCurrentSub: LinearLayout
    private lateinit var tvCurrentPlan: TextView
    private lateinit var tvCurrentPrice: TextView
    private lateinit var tvCurrentExpiry: TextView
    private lateinit var tvDaysRemaining: TextView
    private lateinit var tvNoSubscription: TextView
    private lateinit var btnBack: MaterialButton

    private var plans: List<ApiClient.Plan> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        progressBar = findViewById(R.id.progress_bar)
        layoutPlans = findViewById(R.id.layout_plans)
        layoutCurrentSub = findViewById(R.id.layout_current_sub)
        tvCurrentPlan = findViewById(R.id.tv_current_plan)
        tvCurrentPrice = findViewById(R.id.tv_current_price)
        tvCurrentExpiry = findViewById(R.id.tv_current_expiry)
        tvDaysRemaining = findViewById(R.id.tv_days_remaining)
        tvNoSubscription = findViewById(R.id.tv_no_subscription)
        btnBack = findViewById(R.id.btn_back)

        btnBack.setOnClickListener { finish() }

        loadPlans()
        checkSubscriptionStatus()
    }

    override fun onResume() {
        super.onResume()
        checkSubscriptionStatus()
    }

    private fun loadPlans() {
        val serverUrl = ApiClient.getServerUrl(this)
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, R.string.server_not_configured, Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            plans = ApiClient.getPlans(serverUrl)
            progressBar.visibility = View.GONE
            displayPlans()
        }
    }

    private fun displayPlans() {
        layoutPlans.removeAllViews()

        for (plan in plans) {
            val cardView = layoutInflater.inflate(R.layout.item_plan_card, layoutPlans, false) as CardView

            val tvName = cardView.findViewById<TextView>(R.id.tv_plan_name)
            val tvNameAr = cardView.findViewById<TextView>(R.id.tv_plan_name_ar)
            val tvPrice = cardView.findViewById<TextView>(R.id.tv_plan_price)
            val tvDuration = cardView.findViewById<TextView>(R.id.tv_plan_duration)
            val btnSubscribe = cardView.findViewById<MaterialButton>(R.id.btn_subscribe)

            tvName.text = plan.name
            tvNameAr.text = plan.nameAr
            tvPrice.text = "$${String.format("%.0f", plan.price)}"
            tvDuration.text = if (plan.durationDays == 1) "24 ${getString(R.string.hours)}"
                            else "${plan.durationDays} ${getString(R.string.days)}"

            btnSubscribe.setOnClickListener {
                startPayment(plan)
            }

            layoutPlans.addView(cardView)
        }
    }

    private fun startPayment(plan: ApiClient.Plan) {
        val serverUrl = ApiClient.getServerUrl(this)
        val accessToken = SessionManager.getAccessToken(this)

        if (serverUrl.isEmpty() || accessToken == null) {
            Toast.makeText(this, R.string.server_not_configured, Toast.LENGTH_LONG).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = ApiClient.createPayment(serverUrl, accessToken, plan.id)
            progressBar.visibility = View.GONE

            if (result.success && result.paymentUrl != null) {
                val intent = Intent(this@SubscriptionActivity, PaymentWebViewActivity::class.java).apply {
                    putExtra("payment_url", result.paymentUrl)
                    putExtra("track_id", result.trackId)
                    putExtra("amount", result.amount)
                }
                startActivity(intent)
            } else {
                Toast.makeText(
                    this@SubscriptionActivity,
                    result.error ?: getString(R.string.payment_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkSubscriptionStatus() {
        val serverUrl = ApiClient.getServerUrl(this)
        val accessToken = SessionManager.getAccessToken(this)

        if (serverUrl.isEmpty() || accessToken == null) return

        lifecycleScope.launch {
            val status = ApiClient.getSubscriptionStatus(serverUrl, accessToken)

            if (status.active) {
                layoutCurrentSub.visibility = View.VISIBLE
                tvNoSubscription.visibility = View.GONE

                tvCurrentPlan.text = status.planNameAr ?: status.planName
                tvCurrentPrice.text = "$${String.format("%.0f", status.price)}"
                tvCurrentExpiry.text = status.expiresAt ?: ""
                tvDaysRemaining.text = "${status.daysRemaining} ${getString(R.string.days_remaining)}"

                SessionManager.saveSubscription(
                    this@SubscriptionActivity,
                    true,
                    status.expiresAt,
                    status.planName,
                    status.planNameAr,
                    status.price,
                    status.daysRemaining,
                )
            } else {
                layoutCurrentSub.visibility = View.GONE
                tvNoSubscription.visibility = View.VISIBLE
                SessionManager.saveSubscription(this@SubscriptionActivity, false)
            }
        }
    }
}
