package com.example.egi

import android.content.Context

enum class AppMode {
    FOCUS, CASUAL
}

object EgiPreferences {
    private const val PREFS_NAME = "egi_prefs"
    private const val KEY_MODE = "app_mode"
    private const val KEY_FOCUS_TARGET = "focus_target"
    private const val KEY_CASUAL_WHITELIST = "casual_whitelist"
    private const val KEY_TRUSTED_SSIDS = "trusted_ssids"
    private const val KEY_GEOFENCING_ENABLED = "geofencing_enabled"
    private const val KEY_BANDWIDTH_LIMIT = "bandwidth_limit"
    private const val KEY_SYNC_ENDPOINT = "sync_endpoint"
    private const val KEY_STEALTH_MODE = "stealth_mode"
    private const val KEY_VPN_TUNNEL_MODE = "vpn_tunnel_mode"
    private const val KEY_OUTLINE_KEY = "outline_key"
    private const val KEY_LOCAL_BYPASS = "local_bypass"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_ALLOWED_DOMAINS = "allowed_domains"
    private const val KEY_ROUTER_USER = "router_user"
    private const val KEY_ROUTER_PASS = "router_pass"
    private const val KEY_ROUTER_BRAND = "router_brand"

    fun saveRouterCredentials(context: Context, user: String, pass: String, brand: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_ROUTER_USER, SecurityUtils.encrypt(user))
            putString(KEY_ROUTER_PASS, SecurityUtils.encrypt(pass))
            putString(KEY_ROUTER_BRAND, brand)
        }.apply()
    }

    fun getRouterCredentials(context: Context): Triple<String, String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userEnc = prefs.getString(KEY_ROUTER_USER, "") ?: ""
        val passEnc = prefs.getString(KEY_ROUTER_PASS, "") ?: ""
        val brand = prefs.getString(KEY_ROUTER_BRAND, "Generic") ?: "Generic"
        
        return try {
            Triple(
                if (userEnc.isNotEmpty()) SecurityUtils.decrypt(userEnc) else "",
                if (passEnc.isNotEmpty()) SecurityUtils.decrypt(passEnc) else "",
                brand
            )
        } catch (e: Exception) {
            Triple("", "", "Generic")
        }
    }

    fun saveMode(context: Context, mode: AppMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun isStealthMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_STEALTH_MODE, false)
    }

    fun setStealthMode(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_STEALTH_MODE, enabled).apply()
    }

    fun setVpnTunnelMode(context: Context, isGlobal: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_VPN_TUNNEL_MODE, isGlobal).apply()
    }

    fun isVpnTunnelGlobal(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_VPN_TUNNEL_MODE, true) // Default to GLOBAL
    }

    fun saveOutlineKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedKey = if (key.isNotEmpty()) SecurityUtils.encrypt(key) else ""
        prefs.edit().putString(KEY_OUTLINE_KEY, encryptedKey).apply()
    }

    fun getOutlineKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedKey = prefs.getString(KEY_OUTLINE_KEY, "") ?: ""
        return if (encryptedKey.isNotEmpty()) {
            try {
                SecurityUtils.decrypt(encryptedKey)
            } catch (e: Exception) {
                ""
            }
        } else ""
    }

    fun setLocalBypass(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_LOCAL_BYPASS, enabled).apply()
    }

    fun getLocalBypass(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LOCAL_BYPASS, false)
    }

    fun setAutoStart(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun getAutoStart(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_START, false)
    }

    fun setBandwidthLimit(context: Context, limit: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_BANDWIDTH_LIMIT, limit).apply()
    }

    fun getBandwidthLimit(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BANDWIDTH_LIMIT, 0) // 0 = Unlimited
    }

    fun saveSyncEndpoint(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SYNC_ENDPOINT, url).apply()
    }

    fun getSyncEndpoint(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SYNC_ENDPOINT, null)
    }

    fun isGeofencingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_GEOFENCING_ENABLED, false)
    }

    fun setGeofencingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_GEOFENCING_ENABLED, enabled).apply()
    }

    fun saveTrustedSSIDs(context: Context, ssids: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_TRUSTED_SSIDS, ssids).apply()
    }

    fun getTrustedSSIDs(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_TRUSTED_SSIDS, emptySet()) ?: emptySet()
    }

    fun addTrustedSSID(context: Context, ssid: String) {
        val ssids = getTrustedSSIDs(context).toMutableSet()
        ssids.add(ssid)
        saveTrustedSSIDs(context, ssids)
    }

    fun removeTrustedSSID(context: Context, ssid: String) {
        val ssids = getTrustedSSIDs(context).toMutableSet()
        ssids.remove(ssid)
        saveTrustedSSIDs(context, ssids)
    }

    fun getMode(context: Context): AppMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeName = prefs.getString(KEY_MODE, AppMode.FOCUS.name)
        return AppMode.valueOf(modeName ?: AppMode.FOCUS.name)
    }

    fun saveFocusTarget(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FOCUS_TARGET, packageName).apply()
    }

    fun getFocusTarget(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_FOCUS_TARGET, null)
    }

    fun saveCasualWhitelist(context: Context, packageNames: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_CASUAL_WHITELIST, packageNames).apply()
    }

    fun getCasualWhitelist(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_CASUAL_WHITELIST, emptySet()) ?: emptySet()
    }

    fun getVipList(context: Context): Set<String> {
        val baseList = when (getMode(context)) {
            AppMode.FOCUS -> getFocusTarget(context)?.let { setOf(it) } ?: emptySet()
            AppMode.CASUAL -> getCasualWhitelist(context)
        }
        return PackageUtils.expandPackageList(baseList)
    }

    fun saveAllowedDomains(context: Context, domains: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ALLOWED_DOMAINS, domains).apply()
    }

    fun getAllowedDomains(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ALLOWED_DOMAINS, "") ?: ""
    }
}
