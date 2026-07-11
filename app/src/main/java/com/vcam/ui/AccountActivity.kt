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

class AccountActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmail: TextView
    private lateinit var tvUserId: TextView
    private lateinit var tvSubStatus: TextView
    private lateinit var tvSubPlan: TextView
    private lateinit var tvSubExpiry: TextView
    private lateinit var tvSubDays: TextView
    private lateinit var layoutSubActive: LinearLayout
    private lateinit var layoutSubInactive: LinearLayout
    private lateinit var layoutPayments: LinearLayout
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnSubscribe: MaterialButton
    private lateinit var btnBack: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        progressBar = findViewById(R.id.progress_bar)
        tvEmail = findViewById(R.id.tv_email)
        tvUserId = findViewById(R.id.tv_user_id)
        tvSubStatus = findViewById(R.id.tv_sub_status)
        tvSubPlan = findViewById(R.id.tv_sub_plan)
        tvSubExpiry = findViewById(R.id.tv_sub_expiry)
        tvSubDays = findViewById(R.id.tv_sub_days)
        layoutSubActive = findViewById(R.id.layout_sub_active)
        layoutSubInactive = findViewById(R.id.layout_sub_inactive)
        layoutPayments = findViewById(R.id.layout_payments)
        btnLogout = findViewById(R.id.btn_logout)
        btnSubscribe = findViewById(R.id.btn_subscribe)
        btnBack = findViewById(R.id.btn_back)

        btnBack.setOnClickListener { finish() }

        btnLogout.setOnClickListener {
            SessionManager.clearSession(this)
            startActivity(Intent(this, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        btnSubscribe.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }

        loadProfile()
        loadPaymentHistory()
        checkSubscription()
    }

    override fun onResume() {
        super.onResume()
        checkSubscription()
    }

    private fun loadProfile() {
        tvEmail.text = SessionManager.getUserEmail(this) ?: "—"
        tvUserId.text = SessionManager.getUserId(this) ?: "—"
    }

    private fun checkSubscription() {
        val serverUrl = ApiClient.getServerUrl(this)
        val accessToken = SessionManager.getAccessToken(this)
        if (serverUrl.isEmpty() || accessToken == null) return

        lifecycleScope.launch {
            val status = ApiClient.getSubscriptionStatus(serverUrl, accessToken)
            if (status.active) {
                layoutSubActive.visibility = View.VISIBLE
                layoutSubInactive.visibility = View.GONE
                tvSubStatus.text = getString(R.string.active_badge)
                tvSubStatus.setTextColor(getColor(R.color.color_root_ok))
                tvSubPlan.text = "${status.planNameAr ?: status.planName} — $${String.format("%.0f", status.price)}"
                tvSubExpiry.text = status.expiresAt ?: "—"
                tvSubDays.text = "${status.daysRemaining} ${getString(R.string.days_remaining)}"

                SessionManager.saveSubscription(
                    this@AccountActivity,
                    true,
                    status.expiresAt,
                    status.planName,
                    status.planNameAr,
                    status.price,
                    status.daysRemaining,
                )
            } else {
                layoutSubActive.visibility = View.GONE
                layoutSubInactive.visibility = View.VISIBLE
                tvSubStatus.text = getString(R.string.no_subscription)
                tvSubStatus.setTextColor(getColor(R.color.color_stop))
                SessionManager.saveSubscription(this@AccountActivity, false)
            }
        }
    }

    private fun loadPaymentHistory() {
        val serverUrl = ApiClient.getServerUrl(this)
        val accessToken = SessionManager.getAccessToken(this)
        if (serverUrl.isEmpty() || accessToken == null) return

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val history = ApiClient.getPaymentHistory(serverUrl, accessToken)
            progressBar.visibility = View.GONE
            displayPaymentHistory(history)
        }
    }

    private fun displayPaymentHistory(items: List<ApiClient.PaymentHistoryItem>) {
        layoutPayments.removeAllViews()

        if (items.isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.no_payments)
                setTextColor(getColor(R.color.color_text_hint))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 24)
            }
            layoutPayments.addView(tv)
            return
        }

        for (item in items) {
            val card = layoutInflater.inflate(R.layout.item_payment_history, layoutPayments, false) as CardView
            val tvPlan = card.findViewById<TextView>(R.id.tv_pay_plan)
            val tvAmount = card.findViewById<TextView>(R.id.tv_pay_amount)
            val tvStatus = card.findViewById<TextView>(R.id.tv_pay_status)
            val tvDate = card.findViewById<TextView>(R.id.tv_pay_date)

            tvPlan.text = item.planNameAr.ifEmpty { item.planName }
            tvAmount.text = "$${String.format("%.2f", item.amount)}"
            tvStatus.text = item.status
            tvDate.text = item.createdAt.take(10)

            val colorRes = when (item.status) {
                "paid" -> R.color.color_root_ok
                "pending" -> R.color.color_accent
                else -> R.color.color_stop
            }
            tvStatus.setTextColor(getColor(colorRes))

            layoutPayments.addView(card)
        }
    }
}
