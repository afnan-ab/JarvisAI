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

/**
 * Thin client for the Anthropic Messages API.
 *
 * IMPORTANT: you must supply your own API key (BuildConfig / a settings
 * screen / local.properties - do NOT hardcode it and do NOT ship it in a
 * public APK). Get one at https://console.anthropic.com
 */
class ClaudeApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun sendMessage(
        systemPrompt: String,
        history: List<Pair<String, String>>, // "user"/"assistant" -> text
        userMessage: String
    ): String = withContext(Dispatchers.IO) {

        val messages = JSONArray()
        for ((role, text) in history) {
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMessage))

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 1024)
            put("system", systemPrompt)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext "I couldn't reach the model (HTTP ${response.code}). ${bodyStr.take(200)}"
            }
            val parsed = JSONObject(bodyStr)
            val content = parsed.optJSONArray("content") ?: return@withContext "(empty response)"
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") {
                    sb.append(block.optString("text"))
                }
            }
            sb.toString().ifBlank { "(empty response)" }
        }
    }
}
