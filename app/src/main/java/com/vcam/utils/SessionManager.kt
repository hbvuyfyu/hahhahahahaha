package com.vcam.utils

import android.content.Context
import android.content.SharedPreferences

object SessionManager {
    private const val PREFS_NAME = "vcam_session"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_SUBSCRIPTION_ACTIVE = "sub_active"
    private const val KEY_SUBSCRIPTION_EXPIRES = "sub_expires"
    private const val KEY_SUBSCRIPTION_PLAN = "sub_plan"
    private const val KEY_SUBSCRIPTION_PLAN_AR = "sub_plan_ar"
    private const val KEY_SUBSCRIPTION_PRICE = "sub_price"
    private const val KEY_SUBSCRIPTION_DAYS = "sub_days"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveSession(context: Context, accessToken: String, refreshToken: String, userId: String, email: String) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .apply()
    }

    fun getAccessToken(context: Context): String? {
        return prefs(context).getString(KEY_ACCESS_TOKEN, null)
    }

    fun getRefreshToken(context: Context): String? {
        return prefs(context).getString(KEY_REFRESH_TOKEN, null)
    }

    fun getUserId(context: Context): String? {
        return prefs(context).getString(KEY_USER_ID, null)
    }

    fun getUserEmail(context: Context): String? {
        return prefs(context).getString(KEY_USER_EMAIL, null)
    }

    fun isLoggedIn(context: Context): Boolean {
        return getAccessToken(context) != null
    }

    fun saveSubscription(context: Context, active: Boolean, expiresAt: String? = null, planName: String? = null, planNameAr: String? = null, price: Double = 0.0, daysRemaining: Int = 0) {
        prefs(context).edit()
            .putBoolean(KEY_SUBSCRIPTION_ACTIVE, active)
            .putString(KEY_SUBSCRIPTION_EXPIRES, expiresAt)
            .putString(KEY_SUBSCRIPTION_PLAN, planName)
            .putString(KEY_SUBSCRIPTION_PLAN_AR, planNameAr)
            .putFloat(KEY_SUBSCRIPTION_PRICE, price.toFloat())
            .putInt(KEY_SUBSCRIPTION_DAYS, daysRemaining)
            .apply()
    }

    fun isSubscriptionActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SUBSCRIPTION_ACTIVE, false)
    }

    fun getSubscriptionExpiresAt(context: Context): String? {
        return prefs(context).getString(KEY_SUBSCRIPTION_EXPIRES, null)
    }

    fun getSubscriptionPlanName(context: Context): String? {
        return prefs(context).getString(KEY_SUBSCRIPTION_PLAN, null)
    }

    fun getSubscriptionPlanNameAr(context: Context): String? {
        return prefs(context).getString(KEY_SUBSCRIPTION_PLAN_AR, null)
    }

    fun getSubscriptionPrice(context: Context): Double {
        return prefs(context).getFloat(KEY_SUBSCRIPTION_PRICE, 0f).toDouble()
    }

    fun getSubscriptionDaysRemaining(context: Context): Int {
        return prefs(context).getInt(KEY_SUBSCRIPTION_DAYS, 0)
    }

    fun clearSession(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
