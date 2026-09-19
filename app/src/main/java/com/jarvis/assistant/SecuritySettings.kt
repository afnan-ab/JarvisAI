package com.jarvis.assistant

import android.content.Context

class SecuritySettings(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_security", Context.MODE_PRIVATE)

    fun getPassphrase(): String = prefs.getString("passphrase", "") ?: ""

    fun setPassphrase(value: String) {
        prefs.edit().putString("passphrase", value.trim()).apply()
    }

    fun isEnabled(): Boolean = getPassphrase().isNotBlank()

    fun checkPassphrase(spoken: String): Boolean {
        val saved = getPassphrase().trim().lowercase()
        if (saved.isEmpty()) return true
        return spoken.trim().lowercase().contains(saved)
    }
}
