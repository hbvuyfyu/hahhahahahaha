package com.vcam.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vcam.R
import com.vcam.utils.ApiClient
import com.vcam.utils.SessionManager
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnAuth: MaterialButton
    private lateinit var tvToggleMode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView

    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        etEmail = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        btnAuth = findViewById(R.id.btn_auth)
        tvToggleMode = findViewById(R.id.tv_toggle_mode)
        tvStatus = findViewById(R.id.tv_status)
        progressBar = findViewById(R.id.progress_bar)
        tvTitle = findViewById(R.id.tv_title)
        tvSubtitle = findViewById(R.id.tv_subtitle)

        // Check if already logged in
        if (SessionManager.isLoggedIn(this)) {
            goToMain()
            return
        }

        updateMode()

        btnAuth.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty()) {
                etEmail.error = getString(R.string.email_required)
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                etPassword.error = getString(R.string.password_required)
                return@setOnClickListener
            }
            if (password.length < 6) {
                etPassword.error = getString(R.string.password_min_length)
                return@setOnClickListener
            }

            authenticate(email, password)
        }

        tvToggleMode.setOnClickListener {
            isLoginMode = !isLoginMode
            updateMode()
        }
    }

    private fun updateMode() {
        if (isLoginMode) {
            tvTitle.text = getString(R.string.login_title)
            tvSubtitle.text = getString(R.string.login_subtitle)
            btnAuth.text = getString(R.string.login_button)
            tvToggleMode.text = getString(R.string.switch_to_signup)
        } else {
            tvTitle.text = getString(R.string.signup_title)
            tvSubtitle.text = getString(R.string.signup_subtitle)
            btnAuth.text = getString(R.string.signup_button)
            tvToggleMode.text = getString(R.string.switch_to_login)
        }
        tvStatus.visibility = View.GONE
    }

    private fun authenticate(email: String, password: String) {
        setLoading(true)
        tvStatus.visibility = View.GONE

        val serverUrl = ApiClient.getServerUrl(this)
        if (serverUrl.isEmpty()) {
            setLoading(false)
            showError(getString(R.string.server_not_configured))
            return
        }

        lifecycleScope.launch {
            val result = if (isLoginMode) {
                ApiClient.login(serverUrl, email, password)
            } else {
                ApiClient.signup(serverUrl, email, password)
            }

            setLoading(false)

            if (result.success && result.accessToken != null) {
                SessionManager.saveSession(
                    this@AuthActivity,
                    result.accessToken,
                    result.refreshToken ?: "",
                    result.userId ?: "",
                    result.email ?: email,
                )
                goToMain()
            } else {
                showError(result.error ?: getString(R.string.auth_failed))
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnAuth.isEnabled = !loading
        etEmail.isEnabled = !loading
        etPassword.isEnabled = !loading
    }

    private fun showError(msg: String) {
        tvStatus.text = msg
        tvStatus.visibility = View.VISIBLE
    }
}
