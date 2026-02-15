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

    fun saveMode(context: Context, mode: AppMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODE, mode.name).apply()
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
        return when (getMode(context)) {
            AppMode.FOCUS -> getFocusTarget(context)?.let { setOf(it) } ?: emptySet()
            AppMode.CASUAL -> getCasualWhitelist(context)
        }
    }
}
