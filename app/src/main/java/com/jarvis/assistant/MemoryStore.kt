package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Long-term memory for Jarvis.
 *
 * Two kinds of memory are kept, on-device, in a plain JSON file:
 *  - a rolling window of recent conversation turns (short-term context
 *    fed back into every API call so replies stay coherent)
 *  - a small set of durable "facts" the user has told Jarvis about
 *    themselves (name, preferences, routines) that persist indefinitely
 *    and get folded into the system prompt every time
 *
 * This is intentionally simple (no Room/SQL) so the whole project builds
 * without extra codegen. Swap in Room later if the history grows large.
 */
class MemoryStore(context: Context) {

    private val file = File(context.filesDir, "jarvis_memory.json")
    private val maxTurns = 40

    private var root: JSONObject = load()

    private fun load(): JSONObject {
        if (!file.exists()) {
            val fresh = JSONObject()
            fresh.put("turns", JSONArray())
            fresh.put("facts", JSONObject())
            fresh.put("mood", 0.0) // -1.0 (rough day) .. +1.0 (great mood)
            return fresh
        }
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            JSONObject().apply {
                put("turns", JSONArray())
                put("facts", JSONObject())
                put("mood", 0.0)
            }
        }
    }

    private fun persist() {
        file.writeText(root.toString())
    }

    fun addTurn(role: String, text: String) {
        val turns = root.getJSONArray("turns")
        val turn = JSONObject()
        turn.put("role", role)
        turn.put("text", text)
        turn.put("ts", System.currentTimeMillis())
        turns.put(turn)

        // Trim to the last N turns so the context sent to the API stays bounded.
        if (turns.length() > maxTurns) {
            val trimmed = JSONArray()
            for (i in (turns.length() - maxTurns) until turns.length()) {
                trimmed.put(turns.get(i))
            }
            root.put("turns", trimmed)
        }
        persist()
    }

    fun recentTurns(): List<Pair<String, String>> {
        val turns = root.getJSONArray("turns")
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until turns.length()) {
            val t = turns.getJSONObject(i)
            out.add(t.getString("role") to t.getString("text"))
        }
        return out
    }

    fun setFact(key: String, value: String) {
        root.getJSONObject("facts").put(key, value)
        persist()
    }

    fun allFacts(): Map<String, String> {
        val facts = root.getJSONObject("facts")
        val out = mutableMapOf<String, String>()
        facts.keys().forEach { k -> out[k] = facts.getString(k) }
        return out
    }

    fun mood(): Double = root.optDouble("mood", 0.0)

    fun adjustMood(delta: Double) {
        val newMood = (mood() + delta).coerceIn(-1.0, 1.0)
        root.put("mood", newMood)
        persist()
    }

    fun clearTurnsOnly() {
        root.put("turns", JSONArray())
        persist()
    }

    fun clearAll() {
        root = JSONObject().apply {
            put("turns", JSONArray())
            put("facts", JSONObject())
            put("mood", 0.0)
        }
        persist()
    }
}
