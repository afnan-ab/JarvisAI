package com.jarvis.assistant

import android.content.Context

class VoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE)

    fun getPitch(): Float = prefs.getFloat("pitch", 0.85f)
    fun getRate(): Float = prefs.getFloat("rate", 0.95f)
    fun getPreferMale(): Boolean = prefs.getBoolean("prefer_male", true)

    fun save(pitch: Float, rate: Float, preferMale: Boolean) {
        prefs.edit()
            .putFloat("pitch", pitch)
            .putFloat("rate", rate)
            .putBoolean("prefer_male", preferMale)
            .apply()
    }
}
