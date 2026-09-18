package com.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()
    private val model = "gemini-3.6-flash"

    suspend fun sendMessage(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {

        val contents = JSONArray()
        for ((role, text) in history) {
            val geminiRole = if (role == "assistant") "model" else "user"
            contents.put(JSONObject().apply {
                put("role", geminiRole)
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
        })

        val body = JSONObject().apply {
            put("contents", contents)
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext "I couldn't reach the model (HTTP ${response.code}). ${bodyStr.take(200)}"
            }
            val parsed = JSONObject(bodyStr)
            val candidates = parsed.optJSONArray("candidates") ?: return@withContext "(empty response)"
            if (candidates.length() == 0) return@withContext "(empty response)"
            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts") ?: return@withContext "(empty response)"
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text"))
            }
            sb.toString().ifBlank { "(empty response)" }
        }
    }
}
