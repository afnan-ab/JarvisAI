package com.jarvis.assistant

import android.content.Context

class VoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE)

    fun getPitch(): Float = prefs.getFloat("pitch", 0.85f)
    fun getRate(): Float = prefs.getFloat("rate", 0.95f)
    fun getPreferMale(): Boolean = prefs.getBoolean("prefer_male", true)
    fun getRecognitionLang(): String = prefs.getString("recognition_lang", "auto") ?: "auto"
    fun getProfile(): String = prefs.getString("voice_profile", "cinematic") ?: "cinematic"
    fun isCompanionEnabled(): Boolean = prefs.getBoolean("companion_enabled", false)


    fun save(pitch: Float, rate: Float, preferMale: Boolean) {
        prefs.edit()
            .putFloat("pitch", pitch)
            .putFloat("rate", rate)
            .putBoolean("prefer_male", preferMale)
            .apply()
    }

    fun setRecognitionLang(lang: String) {
        prefs.edit().putString("recognition_lang", lang).apply()
    }

    fun saveProfile(profile: String) {
        prefs.edit().putString("voice_profile", profile).apply()
    }

    fun setCompanionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("companion_enabled", enabled).apply()
    }
}
