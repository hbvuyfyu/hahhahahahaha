package com.vcam.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vcam.R
import com.vcam.utils.ApiClient
import com.vcam.utils.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PaymentWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnDone: MaterialButton

    private var trackId: String? = null
    private var paymentConfirmed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_webview)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)
        tvStatus = findViewById(R.id.tv_payment_status)
        btnDone = findViewById(R.id.btn_done)

        val paymentUrl = intent.getStringExtra("payment_url") ?: ""
        trackId = intent.getStringExtra("track_id")

        if (paymentUrl.isEmpty()) {
            tvStatus.text = getString(R.string.payment_failed)
            tvStatus.visibility = View.VISIBLE
            return
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        webView.loadUrl(paymentUrl)

        btnDone.setOnClickListener {
            if (paymentConfirmed) {
                goToMain()
            } else {
                checkPaymentStatus()
            }
        }

        startPolling()
    }

    private fun startPolling() {
        val tid = trackId ?: return
        val serverUrl = ApiClient.getServerUrl(this)
        val accessToken = SessionManager.getAccessToken(this)
        if (serverUrl.isEmpty() || accessToken == null) return

        lifecycleScope.launch {
            var attempts = 0
            while (attempts < 120 && !paymentConfirmed && !isFinishing) {
                delay(5000)
                attempts++
                val status = ApiClient.checkPaymentStatus(serverUrl, accessToken, tid)
                if (status.status == "paid") {
                    paymentConfirmed = true
                    runOnUiThread {
                        tvStatus.text = getString(R.string.payment_success)
                        tvStatus.setTextColor(getColor(R.color.color_root_ok))
                        tvStatus.visibility = View.VISIBLE
                        btnDone.text = getString(R.string.continue_to_app)
                        btnDone.backgroundTintList = androidx.core.content.res.ResourcesCompat.getColorStateList(resources, R.color.color_root_ok, theme)
                    }
                    break
                } else if (status.status == "expired" || status.status == "failed") {
                    runOnUiThread {
                        tvStatus.text = getString(R.string.payment_failed)
                        tvStatus.setTextColor(getColor(R.color.color_stop))
                        tvStatus.visibility = View.VISIBLE
                        btnDone.text = getString(R.string.retry_payment)
                    }
                    break
                }
            }
        }
    }

    private fun checkPaymentStatus() {
        val tid = trackId ?: return
        val serverUrl = ApiClient.getServerUrl(this)
        val accessToken = SessionManager.getAccessToken(this)
        if (serverUrl.isEmpty() || accessToken == null) return

        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val status = ApiClient.checkPaymentStatus(serverUrl, accessToken, tid)
            progressBar.visibility = View.GONE

            if (status.status == "paid") {
                paymentConfirmed = true
                tvStatus.text = getString(R.string.payment_success)
                tvStatus.setTextColor(getColor(R.color.color_root_ok))
                tvStatus.visibility = View.VISIBLE
                btnDone.text = getString(R.string.continue_to_app)
                btnDone.backgroundTintList = androidx.core.content.res.ResourcesCompat.getColorStateList(resources, R.color.color_root_ok, theme)
            } else {
                tvStatus.text = getString(R.string.payment_pending)
                tvStatus.setTextColor(getColor(R.color.color_accent))
                tvStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
