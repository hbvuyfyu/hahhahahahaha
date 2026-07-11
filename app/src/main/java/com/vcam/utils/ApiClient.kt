package com.vcam.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {

    private const val PREFS_NAME = "vcam_config"
    private const val KEY_SERVER_URL = "server_url"

    fun getServerUrl(context: Context): String {
        return prefs(context).getString(KEY_SERVER_URL, "") ?: ""
    }

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SERVER_URL, url.trimEnd('/')).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class AuthResult(
        val success: Boolean,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val userId: String? = null,
        val email: String? = null,
        val error: String? = null,
    )

    data class SubscriptionStatus(
        val active: Boolean,
        val planName: String? = null,
        val planNameAr: String? = null,
        val price: Double = 0.0,
        val startsAt: String? = null,
        val expiresAt: String? = null,
        val daysRemaining: Int = 0,
        val error: String? = null,
    )

    data class Plan(
        val id: String,
        val name: String,
        val nameAr: String,
        val price: Double,
        val durationDays: Int,
        val description: String?,
    )

    data class PaymentResult(
        val success: Boolean,
        val trackId: String? = null,
        val paymentUrl: String? = null,
        val payAddress: String? = null,
        val amount: Double = 0.0,
        val error: String? = null,
    )

    data class PaymentStatus(
        val status: String,
        val amount: Double = 0.0,
        val error: String? = null,
    )

    data class PaymentHistoryItem(
        val id: String,
        val trackId: String,
        val amount: Double,
        val currency: String,
        val status: String,
        val planName: String,
        val planNameAr: String,
        val payCurrency: String?,
        val createdAt: String,
    )

    private fun makeRequest(
        url: String,
        method: String = "POST",
        body: JSONObject? = null,
        accessToken: String? = null,
    ): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/json")
            if (accessToken != null) {
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            if (body != null) {
                doOutput = true
                outputStream.bufferedWriter().use { it.write(body.toString()) }
            }
        }

        val responseCode = conn.responseCode
        val responseBody = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}"
        }
        conn.disconnect()

        val json = JSONObject(responseBody.ifEmpty { "{}" })
        if (responseCode !in 200..299) {
            val errorMsg = json.optString("error", "HTTP $responseCode")
            return JSONObject().put("error", errorMsg).put("_status", responseCode)
        }
        return json.put("_status", responseCode)
    }

    suspend fun signup(serverUrl: String, email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("email", email).put("password", password)
            val res = makeRequest("$serverUrl/api/auth/signup", "POST", body)
            if (res.has("error")) {
                AuthResult(false, error = res.getString("error"))
            } else {
                AuthResult(
                    success = true,
                    accessToken = res.optString("accessToken", null),
                    refreshToken = res.optString("refreshToken", null),
                    userId = res.optJSONObject("user")?.optString("id"),
                    email = res.optJSONObject("user")?.optString("email"),
                )
            }
        } catch (e: Exception) {
            AuthResult(false, error = e.message ?: "Network error")
        }
    }

    suspend fun login(serverUrl: String, email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("email", email).put("password", password)
            val res = makeRequest("$serverUrl/api/auth/login", "POST", body)
            if (res.has("error")) {
                AuthResult(false, error = res.getString("error"))
            } else {
                AuthResult(
                    success = true,
                    accessToken = res.optString("accessToken", null),
                    refreshToken = res.optString("refreshToken", null),
                    userId = res.optJSONObject("user")?.optString("id"),
                    email = res.optJSONObject("user")?.optString("email"),
                )
            }
        } catch (e: Exception) {
            AuthResult(false, error = e.message ?: "Network error")
        }
    }

    suspend fun refreshToken(serverUrl: String, refreshToken: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("refreshToken", refreshToken)
            val res = makeRequest("$serverUrl/api/auth/refresh", "POST", body)
            if (res.has("error")) {
                AuthResult(false, error = res.getString("error"))
            } else {
                AuthResult(
                    success = true,
                    accessToken = res.optString("accessToken", null),
                    refreshToken = res.optString("refreshToken", null),
                )
            }
        } catch (e: Exception) {
            AuthResult(false, error = e.message ?: "Network error")
        }
    }

    suspend fun getSubscriptionStatus(serverUrl: String, accessToken: String): SubscriptionStatus = withContext(Dispatchers.IO) {
        try {
            val res = makeRequest("$serverUrl/api/subscription/status", "GET", null, accessToken)
            if (res.has("error")) {
                SubscriptionStatus(active = false, error = res.getString("error"))
            } else {
                val sub = res.optJSONObject("subscription")
                if (res.optBoolean("active") && sub != null) {
                    SubscriptionStatus(
                        active = true,
                        planName = sub.optString("planName"),
                        planNameAr = sub.optString("planNameAr"),
                        price = sub.optDouble("price", 0.0),
                        startsAt = sub.optString("startsAt"),
                        expiresAt = sub.optString("expiresAt"),
                        daysRemaining = sub.optInt("daysRemaining", 0),
                    )
                } else {
                    SubscriptionStatus(active = false)
                }
            }
        } catch (e: Exception) {
            SubscriptionStatus(active = false, error = e.message ?: "Network error")
        }
    }

    suspend fun getPlans(serverUrl: String): List<Plan> = withContext(Dispatchers.IO) {
        try {
            val res = makeRequest("$serverUrl/api/plans", "GET")
            if (res.has("error")) return@withContext emptyList()
            val plansArray = res.optJSONArray("plans") ?: return@withContext emptyList()
            (0 until plansArray.length()).map { i ->
                val p = plansArray.getJSONObject(i)
                Plan(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    nameAr = p.getString("name_ar"),
                    price = p.getDouble("price"),
                    durationDays = p.getInt("duration_days"),
                    description = p.optString("description"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createPayment(serverUrl: String, accessToken: String, planId: String): PaymentResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("planId", planId)
            val res = makeRequest("$serverUrl/api/payments/create", "POST", body, accessToken)
            if (res.has("error")) {
                PaymentResult(false, error = res.getString("error"))
            } else {
                PaymentResult(
                    success = true,
                    trackId = res.optString("trackId"),
                    paymentUrl = res.optString("paymentUrl"),
                    payAddress = res.optString("payAddress", null),
                    amount = res.optDouble("amount", 0.0),
                )
            }
        } catch (e: Exception) {
            PaymentResult(false, error = e.message ?: "Network error")
        }
    }

    suspend fun checkPaymentStatus(serverUrl: String, accessToken: String, trackId: String): PaymentStatus = withContext(Dispatchers.IO) {
        try {
            val res = makeRequest("$serverUrl/api/payments/status/$trackId", "GET", null, accessToken)
            if (res.has("error")) {
                PaymentStatus(status = "pending", error = res.getString("error"))
            } else {
                PaymentStatus(
                    status = res.optString("status", "pending"),
                    amount = res.optDouble("amount", 0.0),
                )
            }
        } catch (e: Exception) {
            PaymentStatus(status = "pending", error = e.message ?: "Network error")
        }
    }

    suspend fun getPaymentHistory(serverUrl: String, accessToken: String): List<PaymentHistoryItem> = withContext(Dispatchers.IO) {
        try {
            val res = makeRequest("$serverUrl/api/payments/history", "GET", null, accessToken)
            if (res.has("error")) return@withContext emptyList()
            val arr = res.optJSONArray("payments") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val plan = p.optJSONObject("plans")
                PaymentHistoryItem(
                    id = p.getString("id"),
                    trackId = p.getString("track_id"),
                    amount = p.getDouble("amount"),
                    currency = p.optString("currency", "USD"),
                    status = p.optString("status", "pending"),
                    planName = plan?.optString("name") ?: "",
                    planNameAr = plan?.optString("name_ar") ?: "",
                    payCurrency = p.optString("pay_currency", null),
                    createdAt = p.optString("created_at"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
