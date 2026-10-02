package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray

class RoutineStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_routines", Context.MODE_PRIVATE)

    fun get(name: String): List<String> {
        val raw = prefs.getString("routine_" + name.trim().lowercase(), null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun save(name: String, commands: List<String>) {
        val arr = JSONArray()
        commands.filter { it.isNotBlank() }.forEach { arr.put(it.trim()) }
        prefs.edit().putString("routine_" + name.trim().lowercase(), arr.toString()).apply()
    }

    fun names(): List<String> = prefs.all.keys
        .filter { it.startsWith("routine_") }
        .map { it.removePrefix("routine_") }
        .sorted()
}
