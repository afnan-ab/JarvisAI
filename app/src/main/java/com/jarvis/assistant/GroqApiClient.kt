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

class GroqApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()
    private val model = "llama3-8b-8192"

    suspend fun sendMessage(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((role, text) in history) {
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage))

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext "I couldn't reach the model (HTTP ${response.code}). ${bodyStr.take(200)}"
            }
            val parsed = JSONObject(bodyStr)
            val choices = parsed.optJSONArray("choices") ?: return@withContext "(empty response)"
            if (choices.length() == 0) return@withContext "(empty response)"
            val message = choices.getJSONObject(0).optJSONObject("message")
            message?.optString("content")?.ifBlank { "(empty response)" } ?: "(empty response)"
        }
    }
}
